package com.wscanplus.android.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * IntegrationsManager – coordinates native Pixel AVF (Android Virtualization Framework)
 * Terminal, Shizuku user-space ADB service, Root Superuser access, and Wireless ADB integrations.
 */
object IntegrationsManager {

    private const val TAG = "IntegrationsManager"

    // ── Pixel AVF Terminal constants ──────────────────────────────────────────
    const val PKG_PIXEL_TERMINAL = "com.google.android.virtualmachine.terminal"
    const val PKG_AOSP_TERMINAL = "com.android.virtualmachine.terminal"
    const val FEATURE_VIRTUALIZATION = "android.software.virtualization"

    // ── Root manager packages ─────────────────────────────────────────────────
    private const val PKG_MAGISK = "com.topjohnwu.magisk"
    private const val PKG_KERNELSU = "me.weishu.kernelsu"
    private const val PKG_APATCH = "org.bmax.apatch"

    // ── 1. Pixel AVF Terminal Integration ─────────────────────────────────────

    data class PixelAvfInfo(
        val isPixelDevice: Boolean,
        val isAvfSupported: Boolean,
        val installedTerminalPkg: String?,
        val isTerminalAppInstalled: Boolean,
        val androidVersion: String,
        val description: String,
    )

    fun getPixelAvfInfo(context: Context): PixelAvfInfo {
        val isPixel = Build.MANUFACTURER.equals("Google", ignoreCase = true) ||
                Build.BRAND.equals("google", ignoreCase = true)

        val hasVirtualizationFeature = try {
            context.packageManager.hasSystemFeature(FEATURE_VIRTUALIZATION)
        } catch (_: Exception) {
            false
        }

        val hasVirtualMachineService = try {
            context.getSystemService("virtualmachine") != null ||
                    Class.forName("android.system.virtualmachine.VirtualMachineManager") != null
        } catch (_: Exception) {
            false
        }

        val avfSupported = hasVirtualizationFeature || hasVirtualMachineService || Build.VERSION.SDK_INT >= 34

        val installedPkg = when {
            isPackageInstalled(context, PKG_PIXEL_TERMINAL) -> PKG_PIXEL_TERMINAL
            isPackageInstalled(context, PKG_AOSP_TERMINAL) -> PKG_AOSP_TERMINAL
            else -> null
        }

        val desc = when {
            installedPkg != null ->
                "Native Pixel Linux VM Terminal app is installed and ready."
            isPixel && avfSupported ->
                "Pixel AVF supported. Enable 'Linux terminal' in Settings → System → Developer options."
            avfSupported ->
                "AVF virtualization framework supported on this device."
            else ->
                "AVF requires Android 14+ on compatible hardware (Pixel 6+ recommended)."
        }

        return PixelAvfInfo(
            isPixelDevice = isPixel,
            isAvfSupported = avfSupported,
            installedTerminalPkg = installedPkg,
            isTerminalAppInstalled = installedPkg != null,
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            description = desc
        )
    }

    fun launchPixelTerminal(context: Context): Boolean {
        val info = getPixelAvfInfo(context)
        val targetPkg = info.installedTerminalPkg ?: PKG_PIXEL_TERMINAL

        val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            return true
        }

        // Fallback: Open Developer Settings so the user can toggle the Linux terminal
        return openDeveloperSettings(context)
    }

    // ── 2. Shizuku Integration ────────────────────────────────────────────────

    data class ShizukuStatus(
        val isInstalled: Boolean,
        val isBinderAlive: Boolean,
        val isPermissionGranted: Boolean,
        val version: Int,
        val uid: Int,
        val statusMessage: String,
    )

    fun getShizukuStatus(context: Context): ShizukuStatus {
        val installed = isPackageInstalled(context, "moe.shizuku.privileged.api")

        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }

        val permissionGranted = try {
            binderAlive && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }

        val version = try {
            if (binderAlive) Shizuku.getVersion() else 0
        } catch (_: Throwable) {
            0
        }

        val uid = try {
            if (binderAlive) Shizuku.getUid() else -1
        } catch (_: Throwable) {
            -1
        }

        val msg = when {
            permissionGranted -> "Active & Authorized (UID $uid, v$version)"
            binderAlive -> "Running, but permission not yet granted"
            installed -> "Installed, but service not started"
            else -> "Not installed"
        }

        return ShizukuStatus(
            isInstalled = installed,
            isBinderAlive = binderAlive,
            isPermissionGranted = permissionGranted,
            version = version,
            uid = uid,
            statusMessage = msg
        )
    }

    fun requestShizukuPermission(requestCode: Int = 1001): Boolean {
        return try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(requestCode)
                    true
                } else {
                    true
                }
            } else {
                false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
            false
        }
    }

    fun newShizukuProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, cmd, env, dir) as Process
    }

    suspend fun executeShizukuCommand(command: String, timeoutSeconds: Long = 15): ExecutionResult =
        withContext(Dispatchers.IO) {
            try {
                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    return@withContext ExecutionResult(
                        success = false,
                        exitCode = -1,
                        output = "",
                        error = "Shizuku is not running or permission was not granted."
                    )
                }

                val process = newShizukuProcess(arrayOf("sh", "-c", command))
                val outReader = BufferedReader(InputStreamReader(process.inputStream))
                val errReader = BufferedReader(InputStreamReader(process.errorStream))

                val outText = StringBuilder()
                val errText = StringBuilder()

                val errThread = Thread {
                    var line: String?
                    while (errReader.readLine().also { line = it } != null) {
                        errText.appendLine(line)
                    }
                }.also { it.start() }

                var line: String?
                while (outReader.readLine().also { line = it } != null) {
                    outText.appendLine(line)
                }

                val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                errThread.join(1000)

                if (!completed) {
                    process.destroy()
                    return@withContext ExecutionResult(
                        success = false,
                        exitCode = -1,
                        output = outText.toString().trim(),
                        error = "Command timed out after ${timeoutSeconds}s"
                    )
                }

                ExecutionResult(
                    success = process.exitValue() == 0,
                    exitCode = process.exitValue(),
                    output = outText.toString().trim(),
                    error = errText.toString().trim().ifBlank { null }
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Shizuku process exec failed", e)
                ExecutionResult(
                    success = false,
                    exitCode = -1,
                    output = "",
                    error = e.message ?: "Shizuku execution failed"
                )
            }
        }

    // ── 3. Root Integration ───────────────────────────────────────────────────

    data class RootInfo(
        val isSuBinaryFound: Boolean,
        val rootManager: String?,
        val suPaths: List<String>,
    )

    fun getRootInfo(context: Context): RootInfo {
        val standardPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su"
        )
        val existingPaths = standardPaths.filter { File(it).exists() }

        val manager = when {
            isPackageInstalled(context, PKG_MAGISK) || File("/sbin/magisk").exists() -> "Magisk"
            isPackageInstalled(context, PKG_KERNELSU) || File("/system/bin/ksud").exists() -> "KernelSU"
            isPackageInstalled(context, PKG_APATCH) -> "APatch"
            existingPaths.isNotEmpty() -> "SuperSU / Generic su"
            else -> null
        }

        return RootInfo(
            isSuBinaryFound = existingPaths.isNotEmpty(),
            rootManager = manager,
            suPaths = existingPaths
        )
    }

    suspend fun testRootAccess(): ExecutionResult = withContext(Dispatchers.IO) {
        executeRootCommand("id")
    }

    suspend fun executeRootCommand(command: String, timeoutSeconds: Long = 15): ExecutionResult =
        withContext(Dispatchers.IO) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val outReader = BufferedReader(InputStreamReader(proc.inputStream))
                val errReader = BufferedReader(InputStreamReader(proc.errorStream))

                val outText = StringBuilder()
                val errText = StringBuilder()

                val errThread = Thread {
                    var l: String?
                    while (errReader.readLine().also { l = it } != null) {
                        errText.appendLine(l)
                    }
                }.also { it.start() }

                var line: String?
                while (outReader.readLine().also { line = it } != null) {
                    outText.appendLine(line)
                }

                val completed = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                errThread.join(1000)

                if (!completed) {
                    proc.destroy()
                    return@withContext ExecutionResult(
                        success = false,
                        exitCode = -1,
                        output = outText.toString().trim(),
                        error = "Root command timed out"
                    )
                }

                ExecutionResult(
                    success = proc.exitValue() == 0,
                    exitCode = proc.exitValue(),
                    output = outText.toString().trim(),
                    error = errText.toString().trim().ifBlank { null }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Root exec failed", e)
                ExecutionResult(
                    success = false,
                    exitCode = -1,
                    output = "",
                    error = e.message ?: "Failed to execute su"
                )
            }
        }

    // ── 4. Wireless Debugging / ADB Integration ───────────────────────────────

    data class AdbInfo(
        val isUsbDebuggingEnabled: Boolean,
        val isWirelessAdbEnabled: Boolean,
        val statusDescription: String,
    )

    fun getAdbInfo(context: Context): AdbInfo {
        val usbAdb = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Exception) {
            false
        }

        val wifiAdb = try {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        } catch (_: Exception) {
            false
        }

        val desc = when {
            wifiAdb -> "Wireless Debugging is active on device."
            usbAdb -> "USB Debugging is enabled. Wireless Debugging can be enabled in Developer options."
            else -> "Developer options and USB/Wireless Debugging are currently disabled."
        }

        return AdbInfo(
            isUsbDebuggingEnabled = usbAdb,
            isWirelessAdbEnabled = wifiAdb,
            statusDescription = desc
        )
    }

    fun openDeveloperSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    // ── Universal Diagnostic Command Runner ───────────────────────────────────

    enum class ExecutionBackend(val displayName: String) {
        AUTO("Auto (Best Available)"),
        PIXEL_AVF("Pixel AVF Linux VM"),
        TERMUX("Termux (RUN_COMMAND)"),
        SHIZUKU("Shizuku (Privileged ADB)"),
        ROOT("Root (Superuser su)"),
        ADB_SHELL("Local Shell (Limited)");
    }

    suspend fun getTermuxStatus(context: Context): TermuxBridge.TermuxStatus =
        TermuxBridge.getTermuxStatus(context)

    suspend fun getAvfGuestState(context: Context): PixelAvfDebianGuest.AvfGuestState =
        PixelAvfDebianGuest.probeGuestState(context)

    suspend fun executeDiagnostic(
        context: Context,
        backend: ExecutionBackend,
        command: String
    ): ExecutionResult = withContext(Dispatchers.IO) {
        when (backend) {
            ExecutionBackend.SHIZUKU -> {
                executeShizukuCommand(command)
            }
            ExecutionBackend.ROOT -> {
                executeRootCommand(command)
            }
            ExecutionBackend.TERMUX -> {
                val res = TermuxBridge.executeCommand(context, command)
                ExecutionResult(
                    success = res.success,
                    exitCode = res.exitCode,
                    output = res.stdout,
                    error = res.stderr
                )
            }
            ExecutionBackend.PIXEL_AVF -> {
                val res = PixelAvfDebianGuest.executeGuestCommand(context, command)
                ExecutionResult(
                    success = res.success,
                    exitCode = res.exitCode,
                    output = res.stdout,
                    error = res.stderr
                )
            }
            ExecutionBackend.ADB_SHELL -> {
                execLocalShell(command)
            }
            ExecutionBackend.AUTO -> {
                // Priority: AVF (if reachable) -> Shizuku -> Root -> Termux -> Local Shell
                val avfState = PixelAvfDebianGuest.probeGuestState(context)
                if (avfState.isReachable && avfState.isExecutionVerified) {
                    val res = PixelAvfDebianGuest.executeGuestCommand(context, command)
                    if (res.success) {
                        return@withContext ExecutionResult(res.success, res.exitCode, res.stdout, res.stderr)
                    }
                }
                val shizuku = getShizukuStatus(context)
                if (shizuku.isPermissionGranted) {
                    executeShizukuCommand(command)
                } else if (getRootInfo(context).isSuBinaryFound) {
                    val rootRes = executeRootCommand(command)
                    if (rootRes.success) rootRes else executeShizukuCommand(command)
                } else {
                    val termuxStatus = TermuxBridge.getTermuxStatus(context)
                    if (termuxStatus.isExecutionVerified || termuxStatus.isPermissionGranted) {
                        val termuxRes = TermuxBridge.executeCommand(context, command)
                        if (termuxRes.success) {
                            return@withContext ExecutionResult(termuxRes.success, termuxRes.exitCode, termuxRes.stdout, termuxRes.stderr)
                        }
                    }
                    execLocalShell(command)
                }
            }
        }
    }

    private fun execLocalShell(command: String): ExecutionResult {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            val exited = proc.waitFor(10, TimeUnit.SECONDS)
            if (!exited) {
                proc.destroy()
                return ExecutionResult(false, -1, out.trim(), "Process timed out")
            }
            ExecutionResult(
                success = proc.exitValue() == 0,
                exitCode = proc.exitValue(),
                output = out.trim(),
                error = err.trim().ifBlank { null }
            )
        } catch (e: Exception) {
            ExecutionResult(false, -1, "", e.message)
        }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    data class ExecutionResult(
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val error: String?,
    )
}
