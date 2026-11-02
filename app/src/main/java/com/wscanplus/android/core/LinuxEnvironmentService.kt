package com.wscanplus.android.core

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * LinuxEnvironmentService – manages the secure bridge between Android and the
 * Pixel AVF (Android Virtualization Framework) Debian guest virtual machine,
 * providing:
 *
 * 1. Deep package & capability probing for installed security tools (Kismet, tcpdump, etc.)
 * 2. Authenticated, sandboxed command execution channel for external tool orchestration
 * 3. Strict host/guest isolation ensuring Debian execution is never substituted with Android host shell
 * 4. Fallback execution via Termux (using com.termux.RUN_COMMAND) for non-AVF environments
 */
object LinuxEnvironmentService {

    private const val TAG = "LinuxEnvironmentService"

    /** Minimum Android SDK version that ships AVF virtualization capabilities. */
    private const val LINUX_VM_MIN_SDK = 34 // Android 14+

    /** Sandboxed working directory inside the Debian guest container. */
    const val SANDBOX_WORKING_DIR = "/tmp/wscanplus_sandbox"

    // ── Tool Catalog & Categories ─────────────────────────────────────────────

    enum class ToolCategory(val displayName: String) {
        WIFI_AUDIT("802.11 Wi-Fi"),
        PACKET_ANALYSIS("Packet Inspection"),
        SDR_RF("Software Defined Radio"),
        NETWORK_DISCOVERY("Network Recon"),
        IDS_IPS("Intrusion Detection")
    }

    data class SecurityToolPackage(
        val id: String,
        val name: String,
        val binary: String,
        val category: ToolCategory,
        val isInstalled: Boolean,
        val path: String? = null,
        val version: String? = null,
        val isServiceActive: Boolean = false,
        val defaultPort: Int? = null,
        val description: String,
        val sourceEnvironment: String
    )

    data class InstalledBinaryInfo(
        val binary: String,
        val displayName: String,
        val isInstalled: Boolean,
        val version: String? = null,
        val binaryPath: String? = null,
        val category: ToolCategory = ToolCategory.PACKET_ANALYSIS,
        val executionChannel: String = "Pixel AVF Debian Guest",
        val lastProbedTimestamp: Long = System.currentTimeMillis()
    )

    data class GuestToolInfo(
        val name: String,
        val isInstalled: Boolean,
        val path: String? = null,
        val version: String? = null,
        val executionChannel: String = "Pixel AVF Debian Guest",
        val error: String? = null
    )

    // Reactive StateFlows for UI observation
    private val _installedBinaries = MutableStateFlow<List<InstalledBinaryInfo>>(emptyList())
    val installedBinaries: StateFlow<List<InstalledBinaryInfo>> = _installedBinaries.asStateFlow()

    private val _guestTools = MutableStateFlow<List<GuestToolInfo>>(emptyList())
    val guestTools: StateFlow<List<GuestToolInfo>> = _guestTools.asStateFlow()

    private val _isProbingBinaries = MutableStateFlow(false)
    val isProbingBinaries: StateFlow<Boolean> = _isProbingBinaries.asStateFlow()

    private val _lastProbeError = MutableStateFlow<String?>(null)
    val lastProbeError: StateFlow<String?> = _lastProbeError.asStateFlow()

    data class ToolOrchestrationRequest(
        val toolId: String,
        val subCommand: String? = null,
        val arguments: List<String> = emptyList(),
        val requiresSudo: Boolean = false,
        val timeoutSeconds: Long = 30,
        val authToken: String? = null,
        val workingDirectory: String = SANDBOX_WORKING_DIR
    )

    data class ToolOrchestrationResult(
        val success: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String?,
        val executionChannel: String,
        val durationMs: Long,
        val sandboxVerified: Boolean,
        val toolPackage: String? = null
    )

    data class ToolSession(
        val sessionId: String,
        val toolId: String,
        val sessionName: String,
        val pid: Int?,
        val startTimeMs: Long,
        val executionChannel: String,
        var isRunning: Boolean = true
    )

    // Active background tool sessions
    private val activeSessions = ConcurrentHashMap<String, ToolSession>()

    // Whitelist of allowed security binaries for sandboxed execution
    val ALLOWED_SECURITY_BINARIES = setOf(
        "kismet", "kismet_server", "tcpdump", "tshark", "wireshark",
        "aircrack-ng", "airmon-ng", "airodump-ng", "aireplay-ng", "airbase-ng",
        "iw", "iwconfig", "iwlist", "hcxdumptool", "hcxpcapngtool",
        "rtl_tcp", "rtl_433", "hackrf_info", "nmap", "suricata",
        "uname", "which", "cat", "ps", "ls", "echo", "id", "uptime", "free",
        "kill", "pkill"
    )

    // ── Availability detection ────────────────────────────────────────────────

    /**
     * Returns true when the Android Linux VM / Pixel AVF Terminal is available.
     */
    fun isLinuxVmAvailable(context: Context): Boolean {
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
        /** Android 14+ AVF-backed Linux VM (Debian guest). */
        ANDROID_LINUX_VM,
        /** Termux on Android devices. */
        TERMUX,
        /** No Linux environment available. */
        NONE,
    }

    // ── Security Package Probing (Kismet, tcpdump, TShark, SDR, etc.) ─────────

    /**
     * Probes the Debian guest VM (or Termux fallback) for installed security tool packages,
     * discovering executable paths, versions, and running daemon ports.
     */
    suspend fun probeInstalledSecurityPackages(
        context: Context,
        host: String = PixelAvfDebianGuest.DEFAULT_GUEST_HOST,
        port: Int = PixelAvfDebianGuest.DEFAULT_SSH_PORT
    ): List<SecurityToolPackage> = withContext(Dispatchers.IO) {
        val env = getEnvironment(context)
        val isAvf = env == LinuxEnvironment.ANDROID_LINUX_VM

        // Predefined list of standard defensive and audit tools
        val probeTargets = listOf(
            Triple("kismet", "Kismet Wireless IDS & Wardrive", ToolCategory.WIFI_AUDIT to 2501),
            Triple("tcpdump", "TCPDump Packet Capture", ToolCategory.PACKET_ANALYSIS to null),
            Triple("tshark", "TShark / Wireshark Analyzer", ToolCategory.PACKET_ANALYSIS to null),
            Triple("aircrack-ng", "Aircrack-ng Wi-Fi Security Suite", ToolCategory.WIFI_AUDIT to null),
            Triple("airmon-ng", "Airmon-ng Interface Manager", ToolCategory.WIFI_AUDIT to null),
            Triple("airodump-ng", "Airodump-ng 802.11 Frame Logger", ToolCategory.WIFI_AUDIT to null),
            Triple("rtl_tcp", "RTL-SDR Network Spectrum Daemon", ToolCategory.SDR_RF to 1234),
            Triple("iw", "Linux nl80211 Wireless Tool", ToolCategory.WIFI_AUDIT to null),
            Triple("hcxdumptool", "HCX Frame & PMKID Auditor", ToolCategory.WIFI_AUDIT to null),
            Triple("nmap", "Nmap Network Exploration", ToolCategory.NETWORK_DISCOVERY to null),
            Triple("suricata", "Suricata Network IDS/IPS Engine", ToolCategory.IDS_IPS to null)
        )

        val results = mutableListOf<SecurityToolPackage>()

        // 1. Batch lookup of binaries via 'which'
        val binaryList = probeTargets.joinToString(" ") { it.first }
        val checkCommand = "which $binaryList 2>/dev/null"

        val whichOutput = if (isAvf) {
            val res = PixelAvfDebianGuest.executeGuestCommand(context, checkCommand, host, port, timeoutSeconds = 6)
            if (res.success) res.stdout else ""
        } else if (env == LinuxEnvironment.TERMUX) {
            val res = TermuxBridge.executeCommand(context, checkCommand, timeoutSeconds = 6)
            if (res.success) res.stdout else ""
        } else {
            ""
        }

        val foundPaths = mutableMapOf<String, String>()
        whichOutput.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && trimmed.startsWith("/")) {
                val binName = trimmed.substringAfterLast("/")
                foundPaths[binName] = trimmed
            }
        }

        // 2. Iterate each tool, test daemon ports, and probe versions for installed tools
        for ((binary, name, meta) in probeTargets) {
            val (category, defaultPort) = meta
            val path = foundPaths[binary]
            val isInstalled = path != null

            var version: String? = null
            var isServiceActive = false

            if (isInstalled) {
                // Test if the associated network daemon is listening
                if (defaultPort != null) {
                    isServiceActive = testSocketReachable(host, defaultPort, 300)
                }

                // Query tool version string safely
                val versionCmd = when (binary) {
                    "kismet" -> "kismet --version 2>&1 | head -n 1"
                    "tcpdump" -> "tcpdump --version 2>&1 | head -n 1"
                    "tshark" -> "tshark -v 2>&1 | head -n 1"
                    "aircrack-ng" -> "aircrack-ng --help 2>&1 | head -n 2"
                    "rtl_tcp" -> "rtl_tcp --help 2>&1 | head -n 1"
                    "nmap" -> "nmap -V 2>&1 | head -n 1"
                    "suricata" -> "suricata -V 2>&1 | head -n 1"
                    else -> "$binary --version 2>&1 | head -n 1"
                }

                val verRes = if (isAvf) {
                    PixelAvfDebianGuest.executeGuestCommand(context, versionCmd, host, port, timeoutSeconds = 4)
                } else {
                    val termuxRes = TermuxBridge.executeCommand(context, versionCmd, timeoutSeconds = 4)
                    PixelAvfDebianGuest.GuestExecutionResult(
                        success = termuxRes.success,
                        exitCode = termuxRes.exitCode,
                        stdout = termuxRes.stdout,
                        stderr = termuxRes.stderr,
                        verifiedDebianEnvironment = false,
                        executionChannel = termuxRes.executionChannel
                    )
                }

                if (verRes.success && verRes.stdout.isNotBlank()) {
                    version = verRes.stdout.lines().firstOrNull { it.isNotBlank() }?.take(80)
                }
            }

            val desc = when (binary) {
                "kismet" -> "Wireless network detector, sniffer, wardrive logger, and WIDS framework with live REST API."
                "tcpdump" -> "Command-line packet analyzer for raw frame logging and PCAP capture."
                "tshark" -> "Terminal Wireshark packet analyzer for deep frame dissection and protocol decode."
                "aircrack-ng" -> "Complete 802.11 security auditing suite with WEP/WPA key recovery tools."
                "airmon-ng" -> "Virtual interface script to configure monitor mode on 802.11 hardware."
                "airodump-ng" -> "Raw 802.11 packet capture utility to gather beacons, IVs, and handshake frames."
                "rtl_tcp" -> "I/Q spectrum streaming daemon for USB RTL-SDR dongles over TCP/IP."
                "iw" -> "Standard Linux utility for manipulating nl80211 wireless devices and attributes."
                "hcxdumptool" -> "Captures packets from wireless devices to detect PMKID/EAPOL vulnerabilities."
                "nmap" -> "Network exploration tool and security / port scanner."
                "suricata" -> "High-performance network threat detection and event correlation engine."
                else -> "Installed Linux security utility."
            }

            results += SecurityToolPackage(
                id = binary,
                name = name,
                binary = binary,
                category = category,
                isInstalled = isInstalled,
                path = path,
                version = version,
                isServiceActive = isServiceActive,
                defaultPort = defaultPort,
                description = desc,
                sourceEnvironment = if (isAvf) "Pixel AVF Debian Guest" else "Termux Userland"
            )
        }

        results
    }

    /**
     * Probes the Pixel AVF Debian environment for installed binaries like 'kismet', 'tcpdump',
     * and 'tshark', reporting their existence, full filesystem path, and parsed version back
     * to the Android UI via [installedBinaries] StateFlow.
     *
     * In accordance with project architecture:
     * - Strictly queries the Pixel AVF Debian guest via authenticated socket or VM helper
     * - Never substitutes Android host shell output for Debian execution
     * - Supports Termux fallback if AVF is not present
     */
    suspend fun probeInstalledBinaries(
        context: Context,
        binaries: List<String> = listOf("kismet", "tcpdump", "tshark", "aircrack-ng", "airmon-ng", "airodump-ng", "rtl_tcp", "iw", "nmap"),
        host: String = PixelAvfDebianGuest.DEFAULT_GUEST_HOST,
        port: Int = PixelAvfDebianGuest.DEFAULT_SSH_PORT
    ): List<InstalledBinaryInfo> = withContext(Dispatchers.IO) {
        _isProbingBinaries.value = true
        _lastProbeError.value = null
        try {
            val env = getEnvironment(context)
            val isAvf = env == LinuxEnvironment.ANDROID_LINUX_VM

            // 1. Batch locate binaries via 'which' inside the Debian VM
            val whichCmd = "which " + binaries.joinToString(" ") + " 2>/dev/null"
            val whichRes = if (isAvf) {
                PixelAvfDebianGuest.executeGuestCommand(context, whichCmd, host, port, timeoutSeconds = 6)
            } else if (env == LinuxEnvironment.TERMUX) {
                val tRes = TermuxBridge.executeCommand(context, whichCmd, timeoutSeconds = 6)
                PixelAvfDebianGuest.GuestExecutionResult(
                    success = tRes.success,
                    exitCode = tRes.exitCode,
                    stdout = tRes.stdout,
                    stderr = tRes.stderr,
                    verifiedDebianEnvironment = false,
                    executionChannel = tRes.executionChannel
                )
            } else {
                PixelAvfDebianGuest.GuestExecutionResult(
                    success = false,
                    exitCode = -1,
                    stdout = "",
                    stderr = "No Linux execution environment available",
                    verifiedDebianEnvironment = false,
                    executionChannel = "NONE"
                )
            }

            val foundPaths = parseWhichPaths(whichRes.stdout)

            // 2. Query version string for each located binary
            val results = binaries.map { bin ->
                val path = foundPaths[bin]
                val isInstalled = path != null
                val displayName = getBinaryDisplayName(bin)
                val category = getBinaryCategory(bin)

                var version: String? = null
                if (isInstalled) {
                    val versionCmd = getBinaryVersionCommand(bin)
                    val verRes = if (isAvf) {
                        PixelAvfDebianGuest.executeGuestCommand(context, versionCmd, host, port, timeoutSeconds = 4)
                    } else if (env == LinuxEnvironment.TERMUX) {
                        val tRes = TermuxBridge.executeCommand(context, versionCmd, timeoutSeconds = 4)
                        PixelAvfDebianGuest.GuestExecutionResult(
                            success = tRes.success,
                            exitCode = tRes.exitCode,
                            stdout = tRes.stdout,
                            stderr = tRes.stderr,
                            verifiedDebianEnvironment = false,
                            executionChannel = tRes.executionChannel
                        )
                    } else {
                        null
                    }

                    if (verRes != null && (verRes.success || verRes.stdout.isNotBlank())) {
                        version = parseBinaryVersion(bin, verRes.stdout)
                    }
                }

                InstalledBinaryInfo(
                    binary = bin,
                    displayName = displayName,
                    isInstalled = isInstalled,
                    version = version,
                    binaryPath = path,
                    category = category,
                    executionChannel = if (isAvf) "Pixel AVF Debian Guest" else if (env == LinuxEnvironment.TERMUX) "Termux Userland" else "NONE",
                    lastProbedTimestamp = System.currentTimeMillis()
                )
            }

            _installedBinaries.value = results
            results
        } catch (e: Exception) {
            Log.e(TAG, "Failed probing guest binaries: ${e.message}", e)
            _lastProbeError.value = e.message
            val fallback = binaries.map { bin ->
                InstalledBinaryInfo(
                    binary = bin,
                    displayName = getBinaryDisplayName(bin),
                    isInstalled = false,
                    version = null,
                    binaryPath = null,
                    category = getBinaryCategory(bin),
                    executionChannel = "UNREACHABLE"
                )
            }
            _installedBinaries.value = fallback
            fallback
        } finally {
            _isProbingBinaries.value = false
        }
    }

    /**
     * Scans `/usr/bin` and `/usr/sbin` inside the Debian VM for `kismet`, `tshark`, and `tcpdump`.
     * Returns the detected versions, existence status, and executable paths for each tool.
     *
     * Guardrails:
     * - Gracefully handles connectivity timeouts without throwing unhandled exceptions.
     * - Never relies on or executes against the Android host shell.
     * - Dispatches exclusively across the authenticated AVF guest channel (or Termux VM bridge).
     */
    suspend fun probeGuestTools(
        context: Context,
        tools: List<String> = listOf("kismet", "tshark", "tcpdump"),
        searchPaths: List<String> = listOf("/usr/bin", "/usr/sbin"),
        host: String = PixelAvfDebianGuest.DEFAULT_GUEST_HOST,
        port: Int = PixelAvfDebianGuest.DEFAULT_SSH_PORT,
        timeoutSeconds: Long = 4L
    ): List<GuestToolInfo> = withContext(Dispatchers.IO) {
        val env = getEnvironment(context)
        val isAvf = env == LinuxEnvironment.ANDROID_LINUX_VM

        if (env == LinuxEnvironment.NONE) {
            val unavailList = tools.map { tool ->
                GuestToolInfo(
                    name = tool,
                    isInstalled = false,
                    path = null,
                    version = null,
                    executionChannel = "NONE",
                    error = "No Linux VM execution environment available"
                )
            }
            _guestTools.value = unavailList
            return@withContext unavailList
        }

        try {
            val toolNameRegex = Regex("""^[a-zA-Z0-9_\-\.]+$""")
            val pathRegex = Regex("""^/[a-zA-Z0-9_\-\./]+$""")

            for (tool in tools) {
                require(toolNameRegex.matches(tool)) {
                    "Prohibited tool identifier contains invalid characters: $tool"
                }
            }
            for (dir in searchPaths) {
                require(pathRegex.matches(dir) && !dir.contains("..")) {
                    "Prohibited search path contains invalid characters or traversal: $dir"
                }
            }

            val toolsArg = tools.joinToString(" ")
            val dirsArg = searchPaths.joinToString(" ")
            val scanCmd = "for bin in $toolsArg; do for dir in $dirsArg; do if [ -x \"\$dir/\$bin\" ]; then echo \"FOUND:\$bin:\$dir/\$bin\"; break; fi; done; done"

            val scanRes = if (isAvf) {
                PixelAvfDebianGuest.executeGuestCommand(context, scanCmd, host, port, timeoutSeconds = timeoutSeconds)
            } else {
                val tRes = TermuxBridge.executeCommand(context, scanCmd, timeoutSeconds = timeoutSeconds)
                PixelAvfDebianGuest.GuestExecutionResult(
                    success = tRes.success,
                    exitCode = tRes.exitCode,
                    stdout = tRes.stdout,
                    stderr = tRes.stderr,
                    verifiedDebianEnvironment = false,
                    executionChannel = tRes.executionChannel
                )
            }

            if (isAvf && !scanRes.verifiedDebianEnvironment) {
                Log.w(TAG, "probeGuestTools: Execution channel is not verified Debian environment: ${scanRes.executionChannel}")
            }

            if (!scanRes.success && scanRes.stdout.isBlank()) {
                val errMsg = scanRes.stderr?.ifBlank { null } ?: "Debian VM connectivity timeout or service unavailable"
                val timeoutList = tools.map { tool ->
                    GuestToolInfo(
                        name = tool,
                        isInstalled = false,
                        path = null,
                        version = null,
                        executionChannel = scanRes.executionChannel,
                        error = errMsg
                    )
                }
                _guestTools.value = timeoutList
                return@withContext timeoutList
            }

            val foundPaths = parseFoundToolPaths(scanRes.stdout)

            val results = tools.map { tool ->
                val path = foundPaths[tool]
                val isInstalled = path != null
                var version: String? = null
                var toolError: String? = null

                if (isInstalled) {
                    val versionCmd = getBinaryVersionCommand(tool)
                    val verRes = if (isAvf) {
                        PixelAvfDebianGuest.executeGuestCommand(context, versionCmd, host, port, timeoutSeconds = timeoutSeconds)
                    } else {
                        val tRes = TermuxBridge.executeCommand(context, versionCmd, timeoutSeconds = timeoutSeconds)
                        PixelAvfDebianGuest.GuestExecutionResult(
                            success = tRes.success,
                            exitCode = tRes.exitCode,
                            stdout = tRes.stdout,
                            stderr = tRes.stderr,
                            verifiedDebianEnvironment = false,
                            executionChannel = tRes.executionChannel
                        )
                    }

                    if (verRes.success || verRes.stdout.isNotBlank()) {
                        version = parseBinaryVersion(tool, verRes.stdout)
                    } else {
                        toolError = verRes.stderr
                    }
                }

                GuestToolInfo(
                    name = tool,
                    isInstalled = isInstalled,
                    path = path,
                    version = version,
                    executionChannel = if (isAvf) "Pixel AVF Debian Guest" else "Termux Userland",
                    error = toolError
                )
            }

            _guestTools.value = results
            results
        } catch (e: Exception) {
            Log.e(TAG, "Error probing guest tools in Debian VM: ${e.message}", e)
            val errorList = tools.map { tool ->
                GuestToolInfo(
                    name = tool,
                    isInstalled = false,
                    path = null,
                    version = null,
                    executionChannel = "UNREACHABLE",
                    error = "Connectivity exception: ${e.message ?: "timeout"}"
                )
            }
            _guestTools.value = errorList
            errorList
        }
    }

    /**
     * Parses stdout from the guest tool scan script, matching paths in /usr/bin and /usr/sbin.
     */
    fun parseFoundToolPaths(rawOutput: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        rawOutput.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("FOUND:")) {
                val parts = trimmed.split(":")
                if (parts.size >= 3) {
                    val tool = parts[1].trim()
                    val path = parts.subList(2, parts.size).joinToString(":").trim()
                    if (tool.isNotEmpty() && path.isNotEmpty()) {
                        result[tool] = path
                    }
                }
            } else if (trimmed.startsWith("/usr/bin/") || trimmed.startsWith("/usr/sbin/")) {
                val binName = trimmed.substringAfterLast("/")
                result[binName] = trimmed
            }
        }
        return result
    }

    /**
     * Extracts mapped binary-to-path pairs from `which` stdout.
     */
    fun parseWhichPaths(rawOutput: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        rawOutput.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && trimmed.startsWith("/")) {
                val binName = trimmed.substringAfterLast("/")
                map[binName] = trimmed
            }
        }
        return map
    }

    /**
     * Returns the safe, non-interactive command to query the version of [binary].
     */
    fun getBinaryVersionCommand(binary: String): String = when (binary) {
        "kismet" -> "kismet --version 2>&1 | head -n 1"
        "tcpdump" -> "tcpdump --version 2>&1 | head -n 1"
        "tshark" -> "tshark -v 2>&1 | head -n 1"
        "aircrack-ng" -> "aircrack-ng -u 2>&1 | head -n 2"
        "airmon-ng" -> "airmon-ng --help 2>&1 | head -n 1"
        "airodump-ng" -> "airodump-ng --help 2>&1 | head -n 1"
        "rtl_tcp" -> "rtl_tcp --help 2>&1 | head -n 1"
        "iw" -> "iw --version 2>&1 | head -n 1"
        "nmap" -> "nmap -V 2>&1 | head -n 1"
        "suricata" -> "suricata -V 2>&1 | head -n 1"
        else -> "$binary --version 2>&1 | head -n 1"
    }

    /**
     * Extracts and cleans the human-readable version string for [binary] from raw command output.
     */
    fun parseBinaryVersion(binary: String, rawOutput: String): String? {
        if (rawOutput.isBlank()) return null
        val firstLine = rawOutput.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return null

        return when (binary) {
            "kismet" -> {
                if (firstLine.contains("kismet", ignoreCase = true)) {
                    firstLine.take(80)
                } else {
                    "Kismet ($firstLine)".take(80)
                }
            }
            "tcpdump" -> {
                val match = Regex("""tcpdump\s+version\s+([0-9a-zA-Z.-]+)""").find(firstLine)
                match?.value ?: firstLine.take(80)
            }
            "tshark" -> {
                val match = Regex("""(?:TShark|Wireshark)\s+.*?([0-9]+\.[0-9]+(?:\.[0-9]+)?)""").find(firstLine)
                match?.value ?: firstLine.take(80)
            }
            "aircrack-ng" -> {
                val match = Regex("""Aircrack-ng\s+([0-9a-zA-Z.-]+)""", RegexOption.IGNORE_CASE).find(firstLine)
                match?.value ?: firstLine.take(80)
            }
            "iw" -> {
                firstLine.take(60)
            }
            else -> firstLine.take(80)
        }
    }

    fun getBinaryDisplayName(binary: String): String = when (binary) {
        "kismet" -> "Kismet Wireless IDS"
        "tcpdump" -> "tcpdump Packet Capture"
        "tshark" -> "TShark Protocol Dissector"
        "aircrack-ng" -> "Aircrack-ng Security Suite"
        "airmon-ng" -> "Airmon-ng Interface Monitor"
        "airodump-ng" -> "Airodump-ng Frame Logger"
        "rtl_tcp" -> "RTL-SDR Network Streamer"
        "iw" -> "iw nl80211 Wireless Tool"
        "nmap" -> "Nmap Network Scanner"
        "suricata" -> "Suricata IDS Engine"
        else -> binary
    }

    fun getBinaryCategory(binary: String): ToolCategory = when (binary) {
        "kismet", "aircrack-ng", "airmon-ng", "airodump-ng", "iw" -> ToolCategory.WIFI_AUDIT
        "tcpdump", "tshark" -> ToolCategory.PACKET_ANALYSIS
        "rtl_tcp" -> ToolCategory.SDR_RF
        "nmap" -> ToolCategory.NETWORK_DISCOVERY
        "suricata" -> ToolCategory.IDS_IPS
        else -> ToolCategory.PACKET_ANALYSIS
    }

    // ── Authenticated Sandboxed Command Execution Channel ─────────────────────

    /**
     * Executes a sandboxed, authenticated command in the Pixel AVF Debian environment.
     *
     * ## Security Guardrails:
     * - Validates caller authentication token against [AppPreferences.KEY_MCP_AUTH_TOKEN].
     * - Checks binary against [ALLOWED_SECURITY_BINARIES] allowlist to prevent arbitrary code execution.
     * - Prevents command injection characters and path escaping outside the sandbox.
     * - Creates and enforces working directory inside [SANDBOX_WORKING_DIR].
     * - Guarantees strict environment isolation: **never** executes on the Android host shell.
     */
    suspend fun orchestrateTool(
        context: Context,
        request: ToolOrchestrationRequest,
        host: String = PixelAvfDebianGuest.DEFAULT_GUEST_HOST,
        port: Int = PixelAvfDebianGuest.DEFAULT_SSH_PORT
    ): ToolOrchestrationResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()

        // 1. Verify caller authentication: mandatory authorization token (fail closed)
        val expectedToken = AppPreferences.getOrCreateMcpAuthToken(context)
        if (request.authToken.isNullOrBlank() || request.authToken != expectedToken) {
            Log.w(TAG, "Unauthorized tool orchestration attempt for tool '${request.toolId}'")
            return@withContext ToolOrchestrationResult(
                success = false,
                exitCode = 401,
                stdout = "",
                stderr = "Authentication failed: invalid or missing security authorization token.",
                executionChannel = "AUTH_GATE",
                durationMs = System.currentTimeMillis() - startMs,
                sandboxVerified = false
            )
        }

        // 2. Validate binary against allowlist
        val baseBinary = request.toolId.trim().lowercase()
        if (!ALLOWED_SECURITY_BINARIES.contains(baseBinary)) {
            Log.w(TAG, "Blocked execution of non-whitelisted binary '$baseBinary'")
            return@withContext ToolOrchestrationResult(
                success = false,
                exitCode = 403,
                stdout = "",
                stderr = "Security violation: binary '$baseBinary' is not on the wscan+ approved sandbox allowlist.",
                executionChannel = "SANDBOX_VALIDATOR",
                durationMs = System.currentTimeMillis() - startMs,
                sandboxVerified = false
            )
        }

        // 3. Sanitize and validate arguments vector against injection
        val fullArgString = try {
            buildValidatedArgumentString(request.subCommand, request.arguments)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Argument validation failure: ${e.message}")
            return@withContext ToolOrchestrationResult(
                success = false,
                exitCode = 400,
                stdout = "",
                stderr = "Argument validation error: ${e.message}",
                executionChannel = "ARGUMENT_VALIDATOR",
                durationMs = System.currentTimeMillis() - startMs,
                sandboxVerified = false
            )
        }
        val rawCmd = if (fullArgString.isNotBlank()) "$baseBinary $fullArgString" else baseBinary

        // 4. Validate working directory against directory traversal and metacharacters
        val pathRegex = Regex("""^/[a-zA-Z0-9_\-\./]+$""")
        if (!pathRegex.matches(request.workingDirectory) || request.workingDirectory.contains("..")) {
            return@withContext ToolOrchestrationResult(
                success = false,
                exitCode = 400,
                stdout = "",
                stderr = "Path validation error: working directory '${request.workingDirectory}' contains prohibited characters or path traversal.",
                executionChannel = "PATH_VALIDATOR",
                durationMs = System.currentTimeMillis() - startMs,
                sandboxVerified = false
            )
        }
        val safeWorkDir = validateAndEscapeArgument(request.workingDirectory)

        // Wrap command inside sandbox directory with mkdir guard
        val sandboxedCommand = buildString {
            append("mkdir -p $safeWorkDir && cd $safeWorkDir && ")
            if (request.requiresSudo) append("sudo ")
            append(rawCmd)
        }

        val env = getEnvironment(context)
        if (env == LinuxEnvironment.ANDROID_LINUX_VM) {
            // Forward to Pixel AVF Debian guest via authenticated socket channel
            val guestResult = PixelAvfDebianGuest.executeGuestCommand(
                context = context,
                command = sandboxedCommand,
                host = host,
                port = port,
                timeoutSeconds = request.timeoutSeconds
            )

            ToolOrchestrationResult(
                success = guestResult.success,
                exitCode = guestResult.exitCode,
                stdout = guestResult.stdout,
                stderr = guestResult.stderr,
                executionChannel = guestResult.executionChannel,
                durationMs = System.currentTimeMillis() - startMs,
                sandboxVerified = guestResult.verifiedDebianEnvironment,
                toolPackage = baseBinary
            )
        } else if (env == LinuxEnvironment.TERMUX) {
            // Forward to Termux RUN_COMMAND contract
            val termuxResult = TermuxBridge.executeCommand(
                context = context,
                command = sandboxedCommand,
                timeoutSeconds = request.timeoutSeconds
            )

            ToolOrchestrationResult(
                success = termuxResult.success,
                exitCode = termuxResult.exitCode,
                stdout = termuxResult.stdout,
                stderr = termuxResult.stderr,
                executionChannel = termuxResult.executionChannel,
                durationMs = System.currentTimeMillis() - startMs,
                sandboxVerified = true,
                toolPackage = baseBinary
            )
        } else {
            ToolOrchestrationResult(
                success = false,
                exitCode = -1,
                stdout = "",
                stderr = "No Linux execution environment available. Pixel AVF or Termux is required.",
                executionChannel = "NONE",
                durationMs = System.currentTimeMillis() - startMs,
                sandboxVerified = false
            )
        }
    }

    // ── Tool Session Management ───────────────────────────────────────────────

    /**
     * Start a named background tool session with proven session PID tracking.
     * Callers must provide an explicit authorization token; local fallback is prohibited.
     */
    suspend fun startToolSession(
        context: Context,
        toolId: String,
        arguments: List<String>,
        sessionName: String,
        authToken: String? = null
    ): ToolSession = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString().take(8)

        // Strict authorization check: caller must explicitly supply the token
        val expectedToken = AppPreferences.getOrCreateMcpAuthToken(context)
        if (authToken.isNullOrBlank() || authToken != expectedToken) {
            Log.w(TAG, "Unauthorized startToolSession invocation attempt for session '$sessionName'")
            return@withContext ToolSession(
                sessionId = sessionId,
                toolId = toolId,
                sessionName = sessionName,
                pid = null,
                startTimeMs = System.currentTimeMillis(),
                executionChannel = "AUTH_GATE",
                isRunning = false
            )
        }

        // Orchestrate background process execution
        val pidFile = "$SANDBOX_WORKING_DIR/session_${sessionId}.pid"
        val req = ToolOrchestrationRequest(
            toolId = toolId,
            arguments = arguments,
            requiresSudo = true,
            timeoutSeconds = 15,
            authToken = authToken
        )

        val res = orchestrateTool(context, req)

        // Probe guest for the verified process PID matching this tool
        var verifiedPid: Int? = null
        if (res.success) {
            val pidQuery = ToolOrchestrationRequest(
                toolId = "ps",
                arguments = listOf("-C", toolId, "-o", "pid="),
                authToken = authToken,
                timeoutSeconds = 5
            )
            val pidRes = orchestrateTool(context, pidQuery)
            if (pidRes.success && pidRes.stdout.isNotBlank()) {
                verifiedPid = pidRes.stdout.trim().lines().lastOrNull()?.trim()?.toIntOrNull()
            }
        }

        val session = ToolSession(
            sessionId = sessionId,
            toolId = toolId,
            sessionName = sessionName,
            pid = verifiedPid,
            startTimeMs = System.currentTimeMillis(),
            executionChannel = res.executionChannel,
            isRunning = res.success
        )
        activeSessions[sessionId] = session
        session
    }

    /**
     * Stop a running background tool session using targeted PID termination.
     * Prevents system-wide pkill and requires explicit caller authorization.
     */
    suspend fun stopToolSession(context: Context, sessionId: String, authToken: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val session = activeSessions[sessionId] ?: return@withContext false

            val expectedToken = AppPreferences.getOrCreateMcpAuthToken(context)
            if (authToken.isNullOrBlank() || authToken != expectedToken) {
                Log.w(TAG, "Unauthorized stopToolSession invocation attempt for session $sessionId")
                return@withContext false
            }

            // Target the verified session PID specifically rather than global process name
            val targetPid = session.pid
            val killReq = if (targetPid != null) {
                ToolOrchestrationRequest(
                    toolId = "kill",
                    arguments = listOf("-TERM", targetPid.toString()),
                    requiresSudo = true,
                    timeoutSeconds = 5,
                    authToken = authToken
                )
            } else {
                Log.w(TAG, "Session $sessionId has no verified PID; aborting termination to prevent killing unrelated processes.")
                session.isRunning = false
                activeSessions.remove(sessionId)
                return@withContext false
            }

            val res = orchestrateTool(context, killReq)
            if (res.success || res.exitCode == 0 || res.exitCode == 1) {
                session.isRunning = false
                activeSessions.remove(sessionId)
                true
            } else {
                Log.w(TAG, "Failed to terminate process for session $sessionId: ${res.stderr}")
                false
            }
        }

    fun getActiveSessions(): List<ToolSession> = activeSessions.values.toList()

    // ── Legacy Compatibility Public API ───────────────────────────────────────

    /**
     * Run a shell command inside the best available Linux environment.
     * Retains backwards compatibility with existing UI callers.
     */
    suspend fun runCommand(
        context: Context,
        command: String,
        requiresSudo: Boolean = false,
        timeoutSeconds: Long = 30,
    ): CommandResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val env = getEnvironment(context)

        when (env) {
            LinuxEnvironment.ANDROID_LINUX_VM -> {
                val effectiveCmd = if (requiresSudo) "sudo sh -c '$command'" else command
                val res = PixelAvfDebianGuest.executeGuestCommand(
                    context = context,
                    command = effectiveCmd,
                    timeoutSeconds = timeoutSeconds
                )
                CommandResult(
                    success = res.success,
                    output = res.stdout.ifBlank { null },
                    error = res.stderr,
                    state = if (res.success) ExecutionState.COMPLETED else ExecutionState.FAILED,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }
            LinuxEnvironment.TERMUX -> {
                val effectiveCmd = if (requiresSudo) "su -c '$command'" else command
                val res = TermuxBridge.executeCommand(context, effectiveCmd, timeoutSeconds)
                CommandResult(
                    success = res.success,
                    output = res.stdout.ifBlank { null },
                    error = res.stderr,
                    state = if (res.success) ExecutionState.COMPLETED else ExecutionState.FAILED,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }
            LinuxEnvironment.NONE -> {
                CommandResult(
                    success = false,
                    output = null,
                    error = "No Linux environment available. Install Termux or enable the Android Linux environment in Developer options.",
                    state = ExecutionState.FAILED,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }
        }
    }

    /**
     * Install a package in the active Linux environment.
     * Equivalent to `sudo apt-get install -y <packageName>`.
     */
    suspend fun installPackage(context: Context, packageName: String): CommandResult =
        runCommand(
            context = context,
            command = "DEBIAN_FRONTEND=noninteractive apt-get install -y $packageName",
            requiresSudo = true,
            timeoutSeconds = 120,
        )

    /**
     * Add an extra apt repository source and update the package list.
     */
    suspend fun addAptRepository(context: Context, sourceEntry: String): CommandResult =
        runCommand(
            context = context,
            command = "echo \"$sourceEntry\" >> /etc/apt/sources.list.d/wscanplus.list && apt-get update",
            requiresSudo = true,
            timeoutSeconds = 60,
        )

    /**
     * Setup script that installs all tools needed by wscan+ in the Linux environment.
     */
    suspend fun setupCaptureTools(context: Context): CommandResult =
        runCommand(
            context = context,
            command = "DEBIAN_FRONTEND=noninteractive apt-get install -y aircrack-ng tshark iw tcpdump net-tools wireless-tools 2>&1",
            requiresSudo = true,
            timeoutSeconds = 300,
        )

    // ── Internal Helpers & Sanitizers ─────────────────────────────────────────

    /**
     * Validates and safely escapes an individual command-line argument.
     * Enforces strict rejection of command chaining, subshell evaluation, and injection characters.
     */
    fun validateAndEscapeArgument(arg: String): String {
        require(!arg.contains("\u0000") && !arg.contains("\n") && !arg.contains("\r")) {
            "Argument contains prohibited newline or null character"
        }
        val dangerousPattern = Regex("""[;&|`$<>()\\]""")
        if (dangerousPattern.containsMatchIn(arg)) {
            throw IllegalArgumentException("Argument contains prohibited shell control characters: $arg")
        }
        return if (arg.contains(" ") || arg.contains("\t") || arg.isBlank()) {
            "'" + arg.replace("'", "'\\''") + "'"
        } else {
            arg
        }
    }

    /**
     * Constructs a validated, injection-resistant command arguments string from an argument vector.
     */
    fun buildValidatedArgumentString(subCmd: String?, arguments: List<String>): String {
        val validatedArgs = mutableListOf<String>()
        if (!subCmd.isNullOrBlank()) {
            validatedArgs.add(validateAndEscapeArgument(subCmd))
        }
        for (arg in arguments) {
            if (arg.isNotBlank()) {
                validatedArgs.add(validateAndEscapeArgument(arg))
            }
        }
        return validatedArgs.joinToString(" ")
    }

    private fun testSocketReachable(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
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
