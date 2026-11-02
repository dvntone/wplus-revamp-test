package com.wscanplus.android.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * TerminalDetector – discovers terminal emulator / shell applications
 * available on the Android device and provides a ranked recommendation
 * and user-preference storage.
 *
 * ## Detected environments (in preference order)
 * 1. Pixel AVF Linux Terminal (Pixel 6+ native Linux VM)
 * 2. Shizuku Privileged ADB Shell (bypasses Android throttling without root)
 * 3. Root Shell (su / Magisk / KernelSU)
 * 4. Termux (full apt userland)
 * 5. Kali NetHunter (pentesting distro)
 * 6. tmux (multiplexer)
 * 7. Wireless / USB ADB Shell
 */
object TerminalDetector {

    private const val TAG = "TerminalDetector"

    // ── Terminal descriptors ──────────────────────────────────────────────────

    data class TerminalInfo(
        val id: String,
        val displayName: String,
        val packageName: String?,       // null for binary-only detection
        val binaryHint: String?,        // binary name in Termux $PREFIX/bin or system PATH
        val description: String,
        val isAvailable: Boolean,
    )

    // ── Detection ─────────────────────────────────────────────────────────────

    /** Detect all terminals and return them in descending preference order. */
    fun detectAll(context: Context): List<TerminalInfo> {
        val pixelAvf = checkPixelAvf(context)
        val shizuku = checkShizuku(context)
        val root = checkRoot()

        return listOf(
            pixelAvf,
            shizuku,
            root,
            checkPackage(
                context = context,
                id          = "termux",
                displayName = "Termux",
                packageName = "com.termux",
                binaryHint  = null,
                description = "Full Linux environment with apt, tmux, aircrack-ng, and Python.",
            ),
            checkBinary(
                context     = context,
                id          = "nethunter",
                displayName = "Kali NetHunter",
                packageName = "com.offsec.nethunter",
                binaryHint  = "kali-nethunter",
                description = "Kali Linux on Android. Ideal for airmon-ng, Kismet, and aircrack-ng.",
            ),
            checkTmux(context),
            TerminalInfo(
                id          = "adb_shell",
                displayName = "Wireless / USB ADB Shell",
                packageName = null,
                binaryHint  = null,
                description = "Privileged shell via local Wireless Debugging or USB ADB.",
                isAvailable = IntegrationsManager.getAdbInfo(context).isUsbDebuggingEnabled ||
                        IntegrationsManager.getAdbInfo(context).isWirelessAdbEnabled,
            ),
        )
    }

    /** Return only available terminals. */
    fun detectAvailable(context: Context): List<TerminalInfo> =
        detectAll(context).filter { it.isAvailable }

    /** Return the best recommended terminal. */
    fun getBestTerminal(context: Context): TerminalInfo? =
        detectAvailable(context).firstOrNull()

    /** Return the user's preferred terminal, falling back to best available. */
    fun getPreferred(context: Context): TerminalInfo? {
        val preferredId = AppPreferences.preferredTerminal(context)
        val available   = detectAvailable(context)
        return available.firstOrNull { it.id == preferredId } ?: available.firstOrNull()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun checkPixelAvf(context: Context): TerminalInfo {
        val avfInfo = IntegrationsManager.getPixelAvfInfo(context)
        return TerminalInfo(
            id = "pixel_avf",
            displayName = "Pixel AVF Linux Terminal",
            packageName = avfInfo.installedTerminalPkg ?: IntegrationsManager.PKG_PIXEL_TERMINAL,
            binaryHint = "andlinux-run",
            description = "Google Pixel native AVF Linux container running Debian/Ubuntu.",
            isAvailable = avfInfo.isTerminalAppInstalled || avfInfo.isAvfSupported
        )
    }

    private fun checkShizuku(context: Context): TerminalInfo {
        val status = IntegrationsManager.getShizukuStatus(context)
        return TerminalInfo(
            id = "shizuku",
            displayName = "Shizuku Privileged Shell",
            packageName = "moe.shizuku.privileged.api",
            binaryHint = null,
            description = "Runs commands via ADB user-space daemon without requiring root.",
            isAvailable = status.isBinderAlive && status.isPermissionGranted
        )
    }

    private fun checkRoot(): TerminalInfo {
        val rootFound = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su"
        ).any { java.io.File(it).exists() }

        return TerminalInfo(
            id = "root_su",
            displayName = "Root Superuser (su)",
            packageName = null,
            binaryHint = "su",
            description = "Full root access for monitor mode, airmon-ng, and raw packet capture.",
            isAvailable = rootFound
        )
    }

    private fun checkPackage(
        context: Context,
        id: String,
        displayName: String,
        packageName: String,
        binaryHint: String?,
        description: String,
    ): TerminalInfo {
        val installed = try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }

        return TerminalInfo(id, displayName, packageName, binaryHint, description, installed)
    }

    private fun checkBinary(
        context: Context,
        id: String,
        displayName: String,
        packageName: String?,
        binaryHint: String?,
        description: String,
    ): TerminalInfo {
        val byPackage = packageName?.let {
            try { context.packageManager.getPackageInfo(it, 0); true }
            catch (_: PackageManager.NameNotFoundException) { false }
        } ?: false

        val byBinary = binaryHint?.let {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("which", it))
                proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                proc.exitValue() == 0
            } catch (_: Exception) { false }
        } ?: false

        return TerminalInfo(id, displayName, packageName, binaryHint, description, byPackage || byBinary)
    }

    private fun checkTmux(context: Context): TerminalInfo {
        val available = try {
            val proc = Runtime.getRuntime().exec(arrayOf("tmux", "-V"))
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            proc.exitValue() == 0
        } catch (_: Exception) { false }

        return TerminalInfo(
            id          = "tmux",
            displayName = "tmux (via Termux)",
            packageName = "com.termux",
            binaryHint  = "tmux",
            description = "Terminal multiplexer. Allows multiple panes for simultaneous scanning and monitoring.",
            isAvailable = available,
        )
    }
}
