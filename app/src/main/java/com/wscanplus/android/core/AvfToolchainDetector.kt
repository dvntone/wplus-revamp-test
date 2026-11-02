package com.wscanplus.android.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * AvfToolchainDetector – probes the active environment (AVF Linux VM, Shizuku shell, Root su,
 * or Termux/MCP) for installed red/blue team security binaries and active network daemons.
 */
object AvfToolchainDetector {

    private const val TAG = "AvfToolchainDetector"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    enum class SecurityTeam(val label: String, val badgeColorHex: Long) {
        BLUE_TEAM("Blue Team (Defense & Audit)", 0xFF00E5FF),
        RED_TEAM("Red Team (Offensive & Recon)", 0xFFFF3D00),
        SDR_RF("SDR & Spectrum Analysis", 0xFF76FF03),
        DUAL_USE("Dual-Use Tooling", 0xFFFFD600)
    }

    enum class ToolCategory(val label: String) {
        WIFI("802.11 Wi-Fi"),
        BLUETOOTH("Bluetooth / BLE"),
        PACKETS("Packet Capture & Inspection"),
        SDR("Software Defined Radio"),
        NETWORK("Network Discovery")
    }

    data class SecurityTool(
        val id: String,
        val name: String,
        val command: String,
        val team: SecurityTeam,
        val category: ToolCategory,
        val defaultPort: Int?,
        val description: String,
        val recommendedArgs: String,
        val isDetected: Boolean = false,
        val isRunning: Boolean = false,
        val binaryPath: String? = null,
        val versionInfo: String? = null
    )

    private val defaultToolCatalog = listOf(
        // ── Blue Team ──────────────────────────────────────────────────────────
        SecurityTool(
            id = "kismet",
            name = "Kismet Wardrive & IDS",
            command = "kismet",
            team = SecurityTeam.BLUE_TEAM,
            category = ToolCategory.WIFI,
            defaultPort = 2501,
            description = "Wireless network detector, sniffer, wardrive logger, and WIDS framework with live REST API.",
            recommendedArgs = "--no-ncurses --daemonize"
        ),
        SecurityTool(
            id = "tshark",
            name = "Wireshark / TShark",
            command = "tshark",
            team = SecurityTeam.BLUE_TEAM,
            category = ToolCategory.PACKETS,
            defaultPort = null,
            description = "Terminal-based Wireshark packet analyzer for deep frame dissection, deauth tracking, and PCAP analysis.",
            recommendedArgs = "-i any -f \"wlan deauth\""
        ),
        SecurityTool(
            id = "suricata",
            name = "Suricata IDS / IPS",
            command = "suricata",
            team = SecurityTeam.BLUE_TEAM,
            category = ToolCategory.PACKETS,
            defaultPort = null,
            description = "High-performance network threat detection and event correlation engine.",
            recommendedArgs = "--af-packet -c /etc/suricata/suricata.yaml"
        ),
        SecurityTool(
            id = "tcpdump",
            name = "TCPDump",
            command = "tcpdump",
            team = SecurityTeam.BLUE_TEAM,
            category = ToolCategory.PACKETS,
            defaultPort = null,
            description = "Command-line packet analyzer for raw frame logging and PCAP capture.",
            recommendedArgs = "-i any -nn -s0"
        ),

        // ── Red Team ───────────────────────────────────────────────────────────
        SecurityTool(
            id = "aircrack-ng",
            name = "Aircrack-ng Suite",
            command = "aircrack-ng",
            team = SecurityTeam.RED_TEAM,
            category = ToolCategory.WIFI,
            defaultPort = null,
            description = "Complete suite of 802.11 tools for auditing, monitor mode management, and WPA key verification.",
            recommendedArgs = "--help"
        ),
        SecurityTool(
            id = "airodump-ng",
            name = "Airodump-ng",
            command = "airodump-ng",
            team = SecurityTeam.RED_TEAM,
            category = ToolCategory.WIFI,
            defaultPort = null,
            description = "Raw packet capture of 802.11 frames to collect WPA 4-way handshakes and GPS coordinates.",
            recommendedArgs = "--output-format pcap,csv"
        ),
        SecurityTool(
            id = "bettercap",
            name = "Bettercap",
            command = "bettercap",
            team = SecurityTeam.RED_TEAM,
            category = ToolCategory.NETWORK,
            defaultPort = 8081,
            description = "The Swiss Army knife for 802.11, BLE, Ethernet, and MITM auditing with integrated web UI.",
            recommendedArgs = "-eval \"wifi.recon on; net.recon on\""
        ),
        SecurityTool(
            id = "hcxdumptool",
            name = "hcxdumptool",
            command = "hcxdumptool",
            team = SecurityTeam.RED_TEAM,
            category = ToolCategory.WIFI,
            defaultPort = null,
            description = "Penetration testing tool to capture raw WPA/WPA2/WPA3 PMKID packets without full authentication.",
            recommendedArgs = "-m 1 -o capture.pcapng"
        ),
        SecurityTool(
            id = "nmap",
            name = "Nmap Security Scanner",
            command = "nmap",
            team = SecurityTeam.DUAL_USE,
            category = ToolCategory.NETWORK,
            defaultPort = null,
            description = "Network exploration, host discovery, port scanning, and vulnerability detection engine.",
            recommendedArgs = "-sS -sV -O -F"
        ),

        // ── SDR / RF ───────────────────────────────────────────────────────────
        SecurityTool(
            id = "rtl_tcp",
            name = "RTL-TCP Daemon",
            command = "rtl_tcp",
            team = SecurityTeam.SDR_RF,
            category = ToolCategory.SDR,
            defaultPort = 1234,
            description = "I/Q sample streaming server for Realtek RTL2832U SDR dongles over local network into AVF.",
            recommendedArgs = "-a 127.0.0.1 -p 1234 -f 100M -s 2.048M"
        ),
        SecurityTool(
            id = "rtl_power",
            name = "RTL-Power Spectrum",
            command = "rtl_power",
            team = SecurityTeam.SDR_RF,
            category = ToolCategory.SDR,
            defaultPort = null,
            description = "Wideband RF spectrum monitor over 24MHz - 1.7GHz for signal detection.",
            recommendedArgs = "-f 24M:1.7G:1M -i 10s spectrum.csv"
        ),
        SecurityTool(
            id = "dump1090",
            name = "dump1090 ADS-B",
            command = "dump1090",
            team = SecurityTeam.SDR_RF,
            category = ToolCategory.SDR,
            defaultPort = 8080,
            description = "Mode S / ADS-B transponder flight radar receiver and decoder using RTL-SDR.",
            recommendedArgs = "--interactive --net --net-http-port 8080"
        ),
        SecurityTool(
            id = "hackrf_info",
            name = "HackRF One Tools",
            command = "hackrf_info",
            team = SecurityTeam.SDR_RF,
            category = ToolCategory.SDR,
            defaultPort = null,
            description = "Hardware probe and control tool for HackRF One software defined radio transceivers.",
            recommendedArgs = ""
        )
    )

    private val _tools = MutableStateFlow(defaultToolCatalog)
    val tools: StateFlow<List<SecurityTool>> = _tools.asStateFlow()

    private val _activeTeam = MutableStateFlow<SecurityTeam?>(null)
    val activeTeam: StateFlow<SecurityTeam?> = _activeTeam.asStateFlow()

    /**
     * Probes the active execution environment for installed security tools and active daemons.
     */
    fun refreshTools(context: Context, backend: IntegrationsManager.ExecutionBackend) {
        scope.launch {
            val updated = withContext(Dispatchers.IO) {
                defaultToolCatalog.map { tool ->
                    probeTool(context, backend, tool)
                }
            }
            _tools.value = updated

            // Evaluate dominant team detected
            val detected = updated.filter { it.isDetected || it.isRunning }
            val hasRed = detected.any { it.team == SecurityTeam.RED_TEAM }
            val hasBlue = detected.any { it.team == SecurityTeam.BLUE_TEAM }
            _activeTeam.value = when {
                hasRed && hasBlue -> SecurityTeam.DUAL_USE
                hasRed -> SecurityTeam.RED_TEAM
                hasBlue -> SecurityTeam.BLUE_TEAM
                else -> null
            }
        }
    }

    private suspend fun probeTool(
        context: Context,
        backend: IntegrationsManager.ExecutionBackend,
        tool: SecurityTool
    ): SecurityTool {
        var isListening = false
        if (tool.defaultPort != null) {
            isListening = isPortListening("127.0.0.1", tool.defaultPort)
        }

        // Test command existence via which
        val probeCmd = "which ${tool.command} || command -v ${tool.command}"
        val res = IntegrationsManager.executeDiagnostic(context, backend, probeCmd)

        val isInstalled = res.success && res.output.isNotBlank() && !res.output.contains("not found")
        val binPath = if (isInstalled) res.output.lines().firstOrNull()?.trim() else null

        // Check version if detected
        var version: String? = null
        if (isInstalled) {
            val verRes = IntegrationsManager.executeDiagnostic(context, backend, "${tool.command} --version 2>&1 | head -n 1")
            if (verRes.success && verRes.output.isNotBlank()) {
                version = verRes.output.trim().take(60)
            }
        }

        return tool.copy(
            isDetected = isInstalled || isListening,
            isRunning = isListening,
            binaryPath = binPath,
            versionInfo = version
        )
    }

    /**
     * Real TCP socket check to see if a tool daemon (Kismet 2501, rtl_tcp 1234, etc.) is currently running
     */
    fun isPortListening(host: String, port: Int, timeoutMs: Int = 180): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Launches a security tool binary inside the active execution backend
     */
    suspend fun launchTool(
        context: Context,
        backend: IntegrationsManager.ExecutionBackend,
        tool: SecurityTool,
        customArgs: String? = null
    ): IntegrationsManager.ExecutionResult {
        val args = customArgs ?: tool.recommendedArgs
        val fullCmd = "${tool.command} $args"
        Log.i(TAG, "Launching security tool: $fullCmd via ${backend.displayName}")
        return IntegrationsManager.executeDiagnostic(context, backend, fullCmd)
    }
}
