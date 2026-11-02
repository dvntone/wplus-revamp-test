package com.wscanplus.android.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wscanplus.android.R
import com.wscanplus.android.data.db.AppDatabase
import com.wscanplus.android.data.db.ThreatLog
import com.wscanplus.android.service.ScanCoordinator
import com.wscanplus.android.service.ScanExecutionState
import com.wscanplus.android.service.ScanState
import com.wscanplus.android.service.WifiScanningService
import com.wscanplus.android.ui.components.LiveScanPane
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val coordinator = remember { ScanCoordinator.getInstance() }
    val scanState by coordinator.scanState.collectAsState()

    val recentThreats by db.threatLogDao().observeRecent(25).collectAsState(initial = emptyList())
    var selectedTab by remember { mutableStateOf(0) }

    fun toggleScan() {
        if (scanState.isContinuousActive) {
            context.stopService(Intent(context, WifiScanningService::class.java))
            coordinator.setContinuousMonitoringActive(false)
        } else {
            val intent = Intent(context, WifiScanningService::class.java)
            context.startForegroundService(intent)
            coordinator.setContinuousMonitoringActive(true)
        }
    }

    fun triggerInstantScan() {
        scope.launch {
            coordinator.requestInstantScan(context)
        }
    }

    // Auto-populate scan upon launching if no scan has been executed yet
    LaunchedEffect(Unit) {
        if (scanState.lastScanTimestamp == 0L && !scanState.isContinuousActive && !scanState.isInstantScanActive) {
            coordinator.requestInstantScan(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("wscan+", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { triggerInstantScan() },
                        enabled = !scanState.isInstantScanActive,
                        modifier = Modifier.testTag("appbar_refresh_scan")
                    ) {
                        if (scanState.isInstantScanActive) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh Scan")
                        }
                    }
                    IconButton(
                        onClick = onToggleTheme,
                        modifier = Modifier.testTag("theme_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (darkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = if (darkTheme) "Switch to light mode" else "Switch to dark mode"
                        )
                    }
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Open Settings"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Status Card
            ScanStatusCard(
                scanState = scanState,
                threatCount = recentThreats.size
            )

            // Primary Scanner Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { toggleScan() },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("scan_toggle_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (scanState.isContinuousActive)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (scanState.isContinuousActive) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(if (scanState.isContinuousActive) "Stop IDS" else "Start Continuous IDS")
                }

                OutlinedButton(
                    onClick = { triggerInstantScan() },
                    enabled = !scanState.isInstantScanActive,
                    modifier = Modifier.testTag("instant_scan_button")
                ) {
                    if (scanState.isInstantScanActive || scanState.executionState == ScanExecutionState.QUEUED) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (scanState.executionState == ScanExecutionState.QUEUED) "Queued…" else "Scanning…")
                    } else {
                        Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Instant Scan")
                    }
                }
            }

            // View Tabs: Live Spectrum | Threat Alerts | RF Telemetry
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_view_tabs")
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Live Spectrum (${scanState.accessPoints.size})", fontSize = 12.sp)
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (recentThreats.isNotEmpty()) MaterialTheme.colorScheme.error else LocalContentColor.current
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Threats (${recentThreats.size})", fontSize = 12.sp)
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Hub, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("RF Stats", fontSize = 12.sp)
                        }
                    }
                )
            }

            // Content Switcher
            when (selectedTab) {
                0 -> {
                    // LIVE WI-FI SPECTRUM PANE
                    LiveScanPane(
                        scanState = scanState,
                        onRefresh = { triggerInstantScan() },
                        modifier = Modifier.weight(1f)
                    )
                }
                1 -> {
                    // THREAT DETECTIONS PANE
                    ThreatsPane(
                        threats = recentThreats,
                        modifier = Modifier.weight(1f)
                    )
                }
                2 -> {
                    // RF TELEMETRY & CHANNEL CONGESTION PANE
                    RfStatsPane(
                        scanState = scanState,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanStatusCard(scanState: ScanState, threatCount: Int) {
    val isActive = scanState.isContinuousActive || scanState.isInstantScanActive ||
            scanState.executionState == ScanExecutionState.RUNNING ||
            scanState.executionState == ScanExecutionState.QUEUED

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (scanState.executionState == ScanExecutionState.RUNNING ||
                    scanState.executionState == ScanExecutionState.QUEUED
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else {
                    Icon(
                        Icons.Filled.Security,
                        contentDescription = null,
                        tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column {
                    val title = when {
                        scanState.executionState == ScanExecutionState.QUEUED -> "Scan Queued…"
                        scanState.executionState == ScanExecutionState.THROTTLED -> "OS Throttled (Using Cache)"
                        scanState.isContinuousActive -> "Active Continuous IDS"
                        scanState.isInstantScanActive -> "Instant Spectrum Scan"
                        else -> "Scanner Ready"
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = scanState.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = when {
                    threatCount > 0 -> MaterialTheme.colorScheme.errorContainer
                    scanState.executionState == ScanExecutionState.THROTTLED -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.secondaryContainer
                }
            ) {
                Text(
                    text = if (threatCount > 0) "$threatCount alerts" else "${scanState.lastScanResultCount} APs",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        threatCount > 0 -> MaterialTheme.colorScheme.onErrorContainer
                        scanState.executionState == ScanExecutionState.THROTTLED -> MaterialTheme.colorScheme.onTertiaryContainer
                        else -> MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
            }
        }
    }
}

@Composable
private fun ThreatsPane(
    threats: List<ThreatLog>,
    modifier: Modifier = Modifier
) {
    if (threats.isEmpty()) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = "No Threats Detected",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "No active Evil Twin, Karma/MANA multi-SSID clusters, or deauth floods identified in current spectrum sweeps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(threats, key = { it.id }) { threat ->
                ThreatCard(threat = threat)
            }
        }
    }
}

@Composable
private fun ThreatCard(threat: ThreatLog) {
    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
    val containerColor = when (threat.severity.uppercase()) {
        "HIGH" -> MaterialTheme.colorScheme.errorContainer
        "MEDIUM" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val onColor = when (threat.severity.uppercase()) {
        "HIGH" -> MaterialTheme.colorScheme.onErrorContainer
        "MEDIUM" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = threat.attackType,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = onColor
                )
                Text(
                    text = dateFormatter.format(Instant.ofEpochMilli(threat.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = onColor.copy(alpha = 0.8f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = threat.description,
                style = MaterialTheme.typography.bodySmall,
                color = onColor
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "BSSID: ${threat.bssid}",
                    style = MaterialTheme.typography.labelSmall,
                    color = onColor.copy(alpha = 0.7f)
                )
                if (threat.ssid.isNotBlank()) {
                    Text(
                        text = "SSID: ${threat.ssid}",
                        style = MaterialTheme.typography.labelSmall,
                        color = onColor.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RfStatsPane(
    scanState: ScanState,
    modifier: Modifier = Modifier
) {
    val stats = scanState.liveStats
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Frequency Band Breakdown",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("2.4 GHz Band: ${stats.band24Count} APs", style = MaterialTheme.typography.bodyMedium)
                        Text("5 GHz Band: ${stats.band5Count} APs", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (stats.band6Count > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("6 GHz (Wi-Fi 6E/7): ${stats.band6Count} APs", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Security Protocol Distribution",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("• WPA3-SAE: ${stats.wpa3Count} networks", style = MaterialTheme.typography.bodyMedium)
                    Text("• WPA2-PSK: ${stats.wpa2Count} networks", style = MaterialTheme.typography.bodyMedium)
                    Text("• Open (Unencrypted): ${stats.openCount} networks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (stats.openCount > 0) MaterialTheme.colorScheme.error else Color.Unspecified
                    )
                    if (stats.wepCount > 0) {
                        Text("• WEP (Legacy Insecure): ${stats.wepCount} networks",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Most Congested 802.11 Channels",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    if (stats.channelsInUse.isEmpty()) {
                        Text("No channel telemetry available yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        stats.channelsInUse.forEach { (channel, count) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Channel $channel", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text("$count Access Points", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Radio Backend & Operational State",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("• Active Scanner: ${scanState.activeBackendName.ifBlank { "StandardWifiScanner (WifiManager)" }}", style = MaterialTheme.typography.bodyMedium)
                    Text("• Continuous Monitoring: ${if (scanState.isContinuousActive) "Active (Interval: 10s)" else "Inactive"}", style = MaterialTheme.typography.bodyMedium)
                    Text("• Hardware Mutex: Synchronized single-flight", style = MaterialTheme.typography.bodyMedium)
                    Text("• Throttle Resiliency: Active with fallback to cached results", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
