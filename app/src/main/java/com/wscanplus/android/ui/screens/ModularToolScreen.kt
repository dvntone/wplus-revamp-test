package com.wscanplus.android.ui.screens

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wscanplus.android.R
import com.wscanplus.android.core.AvfHardwareBridge
import com.wscanplus.android.core.AvfToolchainDetector
import com.wscanplus.android.core.EnrichedScanParser
import com.wscanplus.android.core.EnrichedScanResult
import com.wscanplus.android.core.IntegrationsManager
import kotlinx.coroutines.launch

enum class ModularTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    BLUE_TEAM("Blue Team (Audit)", Icons.Filled.Shield),
    RED_TEAM("Red Team (Offense)", Icons.Filled.Bolt),
    SDR_RF("RF & SDR Spectrum", Icons.Filled.Sensors),
    ENRICHED_SCAN("Deep 802.11 IEs", Icons.Filled.Radar),
    MASCOT_AI(".nyx AI Mascot", Icons.Filled.SmartToy)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModularToolScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(ModularTab.BLUE_TEAM) }
    var selectedBackend by remember { mutableStateOf(IntegrationsManager.ExecutionBackend.AUTO) }

    val tools by AvfToolchainDetector.tools.collectAsState()
    val activeTeam by AvfToolchainDetector.activeTeam.collectAsState()

    val gpsState by AvfHardwareBridge.gpsState.collectAsState()
    val sdrState by AvfHardwareBridge.sdrState.collectAsState()
    val kismetTelemetry by AvfHardwareBridge.kismetTelemetry.collectAsState()

    // Enriched scan preview
    var enrichedScans by remember { mutableStateOf<List<EnrichedScanResult>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        AvfToolchainDetector.refreshTools(context, selectedBackend)
        AvfHardwareBridge.probeUsbSdr(context)
        AvfHardwareBridge.pollKismetStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Security Toolchain & AVF Bridge",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Bi-Directional Telemetry & Modular Modules",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                AvfToolchainDetector.refreshTools(context, selectedBackend)
                                AvfHardwareBridge.probeUsbSdr(context)
                                AvfHardwareBridge.pollKismetStatus()
                            }
                        },
                        modifier = Modifier.testTag("refresh_tools_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh Tools & Hardware")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Active Team Status Banner
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                color = when (activeTeam) {
                    AvfToolchainDetector.SecurityTeam.RED_TEAM -> Color(0xFF2C1010)
                    AvfToolchainDetector.SecurityTeam.BLUE_TEAM -> Color(0xFF0D2533)
                    AvfToolchainDetector.SecurityTeam.DUAL_USE -> Color(0xFF2E2405)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                border = BorderStroke(
                    1.dp,
                    when (activeTeam) {
                        AvfToolchainDetector.SecurityTeam.RED_TEAM -> Color(0xFFFF5252)
                        AvfToolchainDetector.SecurityTeam.BLUE_TEAM -> Color(0xFF00E5FF)
                        AvfToolchainDetector.SecurityTeam.DUAL_USE -> Color(0xFFFFD600)
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (activeTeam) {
                                    AvfToolchainDetector.SecurityTeam.RED_TEAM -> Color(0xFFFF5252)
                                    AvfToolchainDetector.SecurityTeam.BLUE_TEAM -> Color(0xFF00E5FF)
                                    AvfToolchainDetector.SecurityTeam.DUAL_USE -> Color(0xFFFFD600)
                                    else -> Color.Gray
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeTeam?.label ?: "Standard Audit Mode (Probing Tools)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = when (activeTeam) {
                                AvfToolchainDetector.SecurityTeam.RED_TEAM -> Color(0xFFFF8A80)
                                AvfToolchainDetector.SecurityTeam.BLUE_TEAM -> Color(0xFF80D8FF)
                                AvfToolchainDetector.SecurityTeam.DUAL_USE -> Color(0xFFFFE57F)
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                        val detectedCount = tools.count { it.isDetected || it.isRunning }
                        Text(
                            text = "$detectedCount security binaries detected in AVF / Linux VM environment",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Dynamic Modular Tab Selector
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                edgePadding = 12.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                ModularTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.title) },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        modifier = Modifier.testTag("tab_${tab.name.lowercase()}")
                    )
                }
            }

            // Tab Content
            when (selectedTab) {
                ModularTab.BLUE_TEAM -> BlueTeamView(
                    context = context,
                    tools = tools.filter { it.team == AvfToolchainDetector.SecurityTeam.BLUE_TEAM || it.team == AvfToolchainDetector.SecurityTeam.DUAL_USE },
                    gpsState = gpsState,
                    kismetTelemetry = kismetTelemetry,
                    backend = selectedBackend,
                    onToggleGps = { enable ->
                        if (enable) AvfHardwareBridge.startGpsBridge(context)
                        else AvfHardwareBridge.stopGpsBridge()
                    },
                    onPollKismet = { AvfHardwareBridge.pollKismetStatus() }
                )

                ModularTab.RED_TEAM -> RedTeamView(
                    context = context,
                    tools = tools.filter { it.team == AvfToolchainDetector.SecurityTeam.RED_TEAM || it.team == AvfToolchainDetector.SecurityTeam.DUAL_USE },
                    backend = selectedBackend
                )

                ModularTab.SDR_RF -> SdrRfView(
                    context = context,
                    sdrState = sdrState,
                    onToggleRtlTcp = { enable -> AvfHardwareBridge.toggleRtlTcpRelay(enable) },
                    onRefreshUsb = { AvfHardwareBridge.probeUsbSdr(context) }
                )

                ModularTab.ENRICHED_SCAN -> EnrichedScanView(
                    context = context,
                    scans = enrichedScans,
                    isScanning = isScanning,
                    onPerformScan = {
                        isScanning = true
                        coroutineScope.launch {
                            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                            @Suppress("DEPRECATION")
                            val raw = wm?.scanResults ?: emptyList()
                            enrichedScans = raw.map { EnrichedScanParser.parse(it) }
                            isScanning = false
                        }
                    }
                )

                ModularTab.MASCOT_AI -> MascotAiView(
                    activeTeam = activeTeam,
                    tools = tools
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 1. Blue Team View (Defensive / Wardrive / Packet Capture)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun BlueTeamView(
    context: Context,
    tools: List<AvfToolchainDetector.SecurityTool>,
    gpsState: AvfHardwareBridge.GpsBridgeState,
    kismetTelemetry: AvfHardwareBridge.KismetTelemetry,
    backend: IntegrationsManager.ExecutionBackend,
    onToggleGps: (Boolean) -> Unit,
    onPollKismet: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var launchOutput by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Forward Telemetry: GPS NMEA TCP Streamer for Kismet
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.LocationOn,
                                contentDescription = null,
                                tint = if (gpsState.isRunning) Color(0xFF00E5FF) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Forward Telemetry: GPS -> AVF Kismet",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Switch(
                            checked = gpsState.isRunning,
                            onCheckedChange = { onToggleGps(it) },
                            modifier = Modifier.testTag("toggle_gps_bridge")
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Streams Android GNSS updates as standard NMEA 0183 (\$GPRMC, \$GPGGA) over local TCP socket for Kismet, gpsd, and airodump-ng inside AVF.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("TCP Port", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text("${gpsState.port} (gpsd format)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Column {
                            Text("AVF Clients", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text("${gpsState.connectedClients} connected", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Column {
                            Text("Sentences Sent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text("${gpsState.sentencesSent}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (gpsState.isRunning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Live Fix: Lat ${String.format("%.5f", gpsState.lastLatitude)}, Lon ${String.format("%.5f", gpsState.lastLongitude)}, Alt ${String.format("%.1f", gpsState.lastAltitudeMeters)}m",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF00E5FF)
                        )
                    }
                }
            }
        }

        // Reverse Telemetry: Kismet Live Status
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.WifiTethering,
                                contentDescription = null,
                                tint = if (kismetTelemetry.isConnected) Color(0xFF76FF03) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Reverse Telemetry: Kismet REST Pipeline",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        FilledTonalButton(
                            onClick = onPollKismet,
                            modifier = Modifier.testTag("poll_kismet_button")
                        ) {
                            Text("Poll API", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Polls live wireless devices, alert feeds, and packet counts from Kismet daemon running in the AVF Linux VM (${kismetTelemetry.endpointUrl}).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Daemon Status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text(
                                text = if (kismetTelemetry.isConnected) "Online (Port 2501)" else "Offline / Not Responding",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (kismetTelemetry.isConnected) Color(0xFF76FF03) else Color.Gray
                            )
                        }
                        Column {
                            Text("Devices Seen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text("${kismetTelemetry.deviceCount}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("Packets", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text("${kismetTelemetry.packetCount}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (kismetTelemetry.recentAlerts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Active Kismet Alerts:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        kismetTelemetry.recentAlerts.take(2).forEach { alert ->
                            Text(
                                text = "• $alert",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF8A80)
                            )
                        }
                    }
                }
            }
        }

        // Blue Team Security Tool Catalog
        item {
            Text(
                text = "Detected Defensive & Audit Tooling",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }

        items(tools) { tool ->
            ToolItemCard(
                tool = tool,
                onLaunch = {
                    coroutineScope.launch {
                        val res = AvfToolchainDetector.launchTool(context, backend, tool)
                        launchOutput = if (res.success) res.output.ifBlank { "Tool launched successfully" }
                        else "Error: ${res.error ?: res.output}"
                    }
                }
            )
        }

        if (launchOutput != null) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Command Execution Output", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { launchOutput = null }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(
                            text = launchOutput ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 2. Red Team View (Offensive / Security Audit / Handshakes)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun RedTeamView(
    context: Context,
    tools: List<AvfToolchainDetector.SecurityTool>,
    backend: IntegrationsManager.ExecutionBackend
) {
    val coroutineScope = rememberCoroutineScope()
    var terminalOutput by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1010)),
                border = BorderStroke(1.dp, Color(0xFFFF5252)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Security, contentDescription = null, tint = Color(0xFFFF5252))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Offensive Wireless Security Audit Suite",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF8A80)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Authorized security evaluation modules for verifying 802.11w PMF enforcement, detecting rogue access points, simulating deauth resilience, and auditing PMKID vulnerabilities.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Text(
                text = "Penetration Testing & Recon Binaries",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }

        items(tools) { tool ->
            ToolItemCard(
                tool = tool,
                onLaunch = {
                    coroutineScope.launch {
                        val res = AvfToolchainDetector.launchTool(context, backend, tool)
                        terminalOutput = if (res.success) res.output.ifBlank { "Tool command executed" }
                        else "Error: ${res.error ?: res.output}"
                    }
                }
            )
        }

        if (terminalOutput != null) {
            item {
                Surface(
                    color = Color.Black,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFFF5252)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Execution Console", color = Color(0xFFFF8A80), style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = { terminalOutput = null }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(
                            text = terminalOutput ?: "",
                            color = Color(0xFF76FF03),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 3. RF Spectrum & SDR View
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun SdrRfView(
    context: Context,
    sdrState: AvfHardwareBridge.SdrBridgeState,
    onToggleRtlTcp: (Boolean) -> Unit,
    onRefreshUsb: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // USB SDR Hardware Discovery
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Usb, contentDescription = null, tint = Color(0xFF76FF03))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "USB SDR Peripheral Discovery",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = onRefreshUsb, modifier = Modifier.testTag("refresh_sdr_usb")) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Scan USB")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    if (sdrState.connectedDevices.isEmpty()) {
                        Text(
                            text = "No USB devices detected via OTG. Connect an RTL2832U, HackRF One, or Airspy dongle via USB-C OTG adapter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        sdrState.connectedDevices.forEach { dev ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (dev.isRecognizedSdr) Icons.Filled.Radio else Icons.Filled.DeviceUnknown,
                                        contentDescription = null,
                                        tint = if (dev.isRecognizedSdr) Color(0xFF76FF03) else MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(dev.deviceName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "Vendor:Product ID: ${dev.combinedId} (hex)",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // AVF vm_config.json injection block
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "AVF vm_config.json 'usb_config' Schema",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Definitive schema resolved from com.google.android.virt APEX classes.dex. Injected into /data/data/com.android.virtualization.terminal/files/linux/vm_config.json:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = Color.Black,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = sdrState.generatedVmConfigJson,
                            color = Color(0xFF00E5FF),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Verified Boot Gate: ${sdrState.gateExplanation}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        // Userspace RTL-TCP Fallback Relay
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Sensors,
                                contentDescription = null,
                                tint = if (sdrState.isRtlTcpRelayActive) Color(0xFF76FF03) else MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Userspace RTL-TCP Relay Server",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Switch(
                            checked = sdrState.isRtlTcpRelayActive,
                            onCheckedChange = { onToggleRtlTcp(it) },
                            modifier = Modifier.testTag("toggle_rtltcp_relay")
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Provides complete SDR connectivity even when verified boot gates assignable_devices.xml. Streams RTL-SDR I/Q samples over 127.0.0.1:${sdrState.rtlTcpPort} directly into AVF tools (GQRX, SDR++, inspectrum, dump1090).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 4. Enriched Wi-Fi Scan Inspector (IE 221, Country IE 7, RTT, Wi-Fi 7)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun EnrichedScanView(
    context: Context,
    scans: List<EnrichedScanResult>,
    isScanning: Boolean,
    onPerformScan: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Deep 802.11 Frame & IE Inspector",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Extracts Information Elements, vendor OUIs, and cipher suites",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = onPerformScan,
                    enabled = !isScanning,
                    modifier = Modifier.testTag("perform_enriched_scan_button")
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Enrich Scan")
                    }
                }
            }
        }

        if (scans.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Tap 'Enrich Scan' to parse live 802.11 beacon frames and vendor Information Elements.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            items(scans) { scan ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = scan.ssid.ifBlank { "<Hidden SSID>" },
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Surface(
                                color = if (scan.riskScore > 30) Color(0xFFFF3D00) else Color(0xFF00E5FF),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (scan.riskScore > 30) "Risk ${scan.riskScore}%" else "Secure",
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = "BSSID: ${scan.bssid} • ${scan.band} (Ch ${scan.channel}) • ${scan.channelWidth} • ${scan.levelDbm} dBm",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Standard: ${scan.wifiStandard} • Security: ${scan.securitySummary}",
                            style = MaterialTheme.typography.bodySmall
                        )

                        if (scan.vendorIdentities.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "IE 221 Vendor: ${scan.vendorIdentities.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF76FF03)
                            )
                        }

                        if (scan.countryCode != null) {
                            Text(
                                text = "IE 7 Regulatory Domain: ${scan.countryCode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }

                        if (scan.isRttResponder) {
                            Text(
                                text = "802.11mc FTM RTT: Responder Enabled (Spatial Ranging Available)",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF00E5FF)
                            )
                        }

                        if (scan.riskReasons.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            scan.riskReasons.forEach { reason ->
                                Text(
                                    text = "⚠ $reason",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFF8A80)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 5. Mascot & AI Bridge View (.nyx integration)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun MascotAiView(
    activeTeam: AvfToolchainDetector.SecurityTeam?,
    tools: List<AvfToolchainDetector.SecurityTool>
) {
    var aiDisplayMode by remember { mutableStateOf("Mascot Overlay (.nyx)") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .border(
                        3.dp,
                        when (activeTeam) {
                            AvfToolchainDetector.SecurityTeam.RED_TEAM -> Color(0xFFFF5252)
                            AvfToolchainDetector.SecurityTeam.BLUE_TEAM -> Color(0xFF00E5FF)
                            else -> Color(0xFF76FF03)
                        },
                        CircleShape
                    )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.nyx_avatar),
                    contentDescription = ".nyx Security AI Mascot",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = ".nyx Autonomous Threat Sentinel",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Operator Persona: ${when (activeTeam) {
                        AvfToolchainDetector.SecurityTeam.RED_TEAM -> "Offensive Adversary Emulation"
                        AvfToolchainDetector.SecurityTeam.BLUE_TEAM -> "Defensive Blue Team WIDS Monitor"
                        else -> "Spectrum & Dual-Use Overseer"
                    }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (activeTeam) {
                        AvfToolchainDetector.SecurityTeam.RED_TEAM -> Color(0xFFFF8A80)
                        AvfToolchainDetector.SecurityTeam.BLUE_TEAM -> Color(0xFF80D8FF)
                        else -> Color(0xFFB9F6CA)
                    }
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "User-Determined AI Feedback Interface",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Choose how .nyx communicates findings and automated security audits:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    listOf("Mascot Overlay (.nyx)", "Tactical Security Feed", "AVF CLI Terminal Feedback").forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = aiDisplayMode == mode,
                                onClick = { aiDisplayMode = mode },
                                modifier = Modifier.testTag("radio_ai_${mode.take(6).lowercase()}")
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(mode, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Live Model Context Protocol (MCP) Integration",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Embedded on-device MCP Server running on port 8080. External CLI AI agents (Claude Code, Gemini CLI, local AVF agents) connect over localhost or Wi-Fi to query live threat telemetry, execute VM tools, and command the hardware bridge.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Reusable Tool Item Card
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun ToolItemCard(
    tool: AvfToolchainDetector.SecurityTool,
    onLaunch: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = tool.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (tool.isRunning) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = Color(0xFF76FF03),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "PORT ${tool.defaultPort} ACTIVE",
                                    color = Color.Black,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        } else if (tool.isDetected) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = Color(0xFF00E5FF),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "INSTALLED",
                                    color = Color.Black,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "Binary: ${tool.command} • Category: ${tool.category.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                FilledTonalButton(
                    onClick = onLaunch,
                    modifier = Modifier.testTag("launch_tool_${tool.id}")
                ) {
                    Text("Execute", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (tool.binaryPath != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Path: ${tool.binaryPath}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF80D8FF)
                )
            }
        }
    }
}
