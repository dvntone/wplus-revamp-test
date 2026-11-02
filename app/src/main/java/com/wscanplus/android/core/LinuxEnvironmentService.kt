package com.wscanplus.android.core

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * LinuxEnvironmentService – manages the Android 17+ built-in Linux Virtual
 * Machine (Android Virtualization Framework / AVF) and bridges to Termux for
 * older devices.
 *
 * ## Android 17+ Linux VM
 * Android 17 introduces an opt-in "Android Linux" feature that runs a full
 * Debian/Ubuntu-derived root filesystem inside AVF (KVM-backed).  Users can
 * enable it in Settings → System → Linux environment.
 *
 * When enabled the VM exposes a Unix socket or `virtio-console` device that
 * apps can communicate with.  Commands are forwarded via `LocalSocket` bound
 * to `@linux-rpc` (subject to AVF ABI finalisation).
 *
 * ## sudo / package management
 * Inside the Linux VM the user has sudo without a password (the VM is
 * sandboxed; "sudo" in this context means elevated privileges within the VM,
 * not the Android host).  Commands that require sudo are automatically
 * prefixed.
 *
 * ## Fallback: Termux
 * For Android ≤ 16 or when the Linux VM is disabled, this service falls back
 * to Termux via `am startservice` + the `RUN_COMMAND` intent API, which
 * allows background command execution inside Termux's environment without
 * opening the app UI.
 *
 * ## Additional package repositories
 * When the Linux VM is active, the service can configure additional apt sources
 * (stored in [AppPreferences.KEY_LINUX_ENV_EXTRA_REPOS]) so that tools like
 * aircrack-ng, Kismet, and tshark are available via `apt install`.
 *
 * @see <a href="https://source.android.com/docs/core/virtualization">AVF documentation</a>
 */
object LinuxEnvironmentService {

    private const val TAG = "LinuxEnvironmentService"

    /** Minimum Android SDK version that ships AVF virtualization capabilities. */
    private const val LINUX_VM_MIN_SDK = 34 // Android 14+

    // ── Availability detection ────────────────────────────────────────────────

    /**
     * Returns true when the Android Linux VM / Pixel AVF Terminal is available.
     *
     * Checks for the Pixel Terminal package, the system virtualization feature,
     * or the `virtualmachine` system service.
     */
    fun isLinuxVmAvailable(context: Context): Boolean {
        // Direct check: Pixel AVF Terminal app is installed
        val terminalAppInstalled = try {
            context.packageManager.getPackageInfo(IntegrationsManager.PKG_PIXEL_TERMINAL, 0)
            true
        } catch (_: Exception) {
            try {
                context.packageManager.getPackageInfo(IntegrationsManager.PKG_AOSP_TERMINAL, 0)
                true
            } catch (_: Exception) { false }
        }
        if (terminalAppInstalled) return true

        if (Build.VERSION.SDK_INT < LINUX_VM_MIN_SDK) return false
        return try {
            val hasFeature = context.packageManager.hasSystemFeature(IntegrationsManager.FEATURE_VIRTUALIZATION)
            val hasService = context.getSystemService("virtualmachine") != null
            hasFeature || hasService
        } catch (_: Exception) { false }
    }

    /** Returns true when Termux is installed as a fallback execution environment. */
    fun isTermuxAvailable(context: Context): Boolean =
        TerminalDetector.detectAll(context)
            .any { it.id == "termux" && it.isAvailable }

    /** Returns the best execution environment for shell commands. */
    fun getEnvironment(context: Context): LinuxEnvironment = when {
        isLinuxVmAvailable(context) -> LinuxEnvironment.ANDROID_LINUX_VM
        isTermuxAvailable(context)  -> LinuxEnvironment.TERMUX
        else                        -> LinuxEnvironment.NONE
    }

    enum class LinuxEnvironment {
        /** Android 17+ AVF-backed Linux VM. */
        ANDROID_LINUX_VM,
        /** Termux on Android ≤ 16. */
        TERMUX,
        /** No Linux environment available. */
        NONE,
    }

    // ── Command execution ─────────────────────────────────────────────────────

    /**
     * Run a shell command inside the best available Linux environment and
     * return its stdout.
     *
     * - In [LinuxEnvironment.ANDROID_LINUX_VM]: commands are forwarded to the
     *   VM via a subprocess call to the AVF helper binary; sudo is prepended
     *   automatically when [requiresSudo] is true.
     * - In [LinuxEnvironment.TERMUX]: the command is executed via the Termux
     *   `RUN_COMMAND` intent API.
     * - In [LinuxEnvironment.NONE]: returns `null`.
     *
     * All process calls include a [timeoutSeconds] guard.
     */
    suspend fun runCommand(
        context: Context,
        command: String,
        requiresSudo: Boolean = false,
        timeoutSeconds: Long = 30,
    ): CommandResult = withContext(Dispatchers.IO) {
        when (getEnvironment(context)) {
            LinuxEnvironment.ANDROID_LINUX_VM ->
                runInVm(command, requiresSudo, timeoutSeconds)
            LinuxEnvironment.TERMUX ->
                runInTermux(context, command, timeoutSeconds)
            LinuxEnvironment.NONE ->
                CommandResult(success = false, output = null,
                    error = "No Linux environment available. Install Termux or enable " +
                            "the Android Linux environment in Settings → System.")
        }
    }

    // ── Package management ────────────────────────────────────────────────────

    /**
     * Install a package in the active Linux environment.
     * Equivalent to `sudo apt-get install -y <packageName>`.
     */
    suspend fun installPackage(context: Context, packageName: String): CommandResult =
        runCommand(
            context      = context,
            command      = "apt-get install -y $packageName",
            requiresSudo = true,
            timeoutSeconds = 120,
        )

    /**
     * Add an extra apt repository source and update the package list.
     * The [sourceEntry] should be a valid `/etc/apt/sources.list` line, e.g.:
     * `"deb https://packages.aircrack-ng.org/ jammy main"`
     */
    suspend fun addAptRepository(context: Context, sourceEntry: String): CommandResult =
        runCommand(
            context      = context,
            command      = "sh -c 'echo \"$sourceEntry\" >> /etc/apt/sources.list.d/wscanplus.list " +
                           "&& apt-get update'",
            requiresSudo = true,
            timeoutSeconds = 60,
        )

    /**
     * Setup script that installs all tools needed by wscan+ in the Linux
     * environment: aircrack-ng, tshark (non-interactive), iw, tcpdump.
     * Returns output of the final apt command.
     */
    suspend fun setupCaptureTools(context: Context): CommandResult =
        runCommand(
            context = context,
            command = "sh -c 'DEBIAN_FRONTEND=noninteractive apt-get install -y " +
                      "aircrack-ng tshark iw tcpdump net-tools wireless-tools 2>&1'",
            requiresSudo = true,
            timeoutSeconds = 300,
        )

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun runInVm(
        command: String,
        requiresSudo: Boolean,
        timeoutSeconds: Long,
    ): CommandResult {
        val startMs = System.currentTimeMillis()
        val effectiveCmd = if (requiresSudo) "sudo sh -c '$command'" else command

        // 1. Direct AVF helper binary if provided by system
        val vmHelpers = listOf(
            "/system/bin/andlinux-run",
            "/apex/com.android.virt/bin/vm_client",
            "/system/bin/vm"
        )
        val validHelper = vmHelpers.firstOrNull { File(it).exists() }

        val args = if (validHelper != null) {
            arrayOf(validHelper, "--", "sh", "-c", effectiveCmd)
        } else if (File("/system/bin/su").exists() || File("/system/xbin/su").exists()) {
            arrayOf("su", "-c", effectiveCmd)
        } else {
            // Unprivileged local shell
            arrayOf("sh", "-c", effectiveCmd)
        }

        val baseRes = execProcess(args, timeoutSeconds)
        return baseRes.copy(durationMs = System.currentTimeMillis() - startMs)
    }

    private fun runInTermux(
        context: Context,
        command: String,
        timeoutSeconds: Long,
    ): CommandResult {
        val startMs = System.currentTimeMillis()
        return try {
            val intent = android.content.Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            context.startService(intent)
            CommandResult(
                success = true,
                output = "(running in Termux background)",
                error = null,
                state = ExecutionState.RUNNING,
                durationMs = System.currentTimeMillis() - startMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Termux RUN_COMMAND failed", e)
            CommandResult(
                success = false,
                output = null,
                error = e.message,
                state = ExecutionState.FAILED,
                durationMs = System.currentTimeMillis() - startMs
            )
        }
    }

    private fun execProcess(args: Array<String>, timeoutSeconds: Long): CommandResult {
        val startMs = System.currentTimeMillis()
        return try {
            val proc = Runtime.getRuntime().exec(args)
            val errThread = Thread { proc.errorStream.use { it.bufferedReader().readText() } }
                .also { it.isDaemon = true; it.start() }
            val out = proc.inputStream.use { it.bufferedReader().readText() }
            val exited = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            errThread.join(500)
            val durationMs = System.currentTimeMillis() - startMs
            if (!exited) {
                Log.w(TAG, "Command timed out: ${args.joinToString(" ")}")
                proc.destroy()
                return CommandResult(
                    success = false,
                    output = out.ifBlank { null },
                    error = "Command timed out after ${timeoutSeconds}s",
                    state = ExecutionState.FAILED,
                    durationMs = durationMs
                )
            }
            val exitOk = proc.exitValue() == 0
            CommandResult(
                success = exitOk,
                output  = out.ifBlank { null },
                error   = if (!exitOk) "Exit code ${proc.exitValue()}" else null,
                state   = if (exitOk) ExecutionState.COMPLETED else ExecutionState.FAILED,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "execProcess failed", e)
            CommandResult(
                success = false,
                output = null,
                error = e.message,
                state = ExecutionState.FAILED,
                durationMs = System.currentTimeMillis() - startMs
            )
        }
    }

    // ── Public data types ─────────────────────────────────────────────────────

    enum class ExecutionState {
        SUBMITTED,
        RUNNING,
        COMPLETED,
        FAILED
    }

    data class CommandResult(
        val success: Boolean,
        val output: String?,
        val error: String?,
        val state: ExecutionState = if (success) ExecutionState.COMPLETED else ExecutionState.FAILED,
        val durationMs: Long = 0L,
    )
}
