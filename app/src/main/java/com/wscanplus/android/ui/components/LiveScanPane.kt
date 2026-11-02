package com.wscanplus.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wscanplus.android.core.EnrichedScanResult
import com.wscanplus.android.service.LiveSpectrumStats
import com.wscanplus.android.service.ScanState

enum class ApFilter(val label: String) {
    ALL("All"),
    THREATS("Threats"),
    OPEN("Open / Unencrypted"),
    BAND_5GHZ("5 GHz"),
    BAND_24GHZ("2.4 GHz")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScanPane(
    scanState: ScanState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(ApFilter.ALL) }
    var expandedBssid by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current

    // Filter access points based on search query and active category filter
    val filteredAps = remember(scanState.accessPoints, searchQuery, selectedFilter) {
        scanState.accessPoints.filter { ap ->
            val matchesSearch = if (searchQuery.isBlank()) true else {
                val q = searchQuery.trim().lowercase()
                ap.ssid.lowercase().contains(q) ||
                        ap.bssid.lowercase().contains(q) ||
                        ap.vendorIdentities.any { it.lowercase().contains(q) } ||
                        ap.securitySummary.lowercase().contains(q)
            }
            val matchesFilter = when (selectedFilter) {
                ApFilter.ALL -> true
                ApFilter.THREATS -> ap.riskReasons.isNotEmpty() || ap.riskScore >= 70
                ApFilter.OPEN -> ap.isOpen
                ApFilter.BAND_5GHZ -> ap.band.contains("5 GHz")
                ApFilter.BAND_24GHZ -> ap.band.contains("2.4 GHz")
            }
            matchesSearch && matchesFilter
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Live Spectrum Metrics Banner
        LiveStatsDashboard(
            stats = scanState.liveStats,
            activeCount = scanState.accessPoints.size,
            isScanning = scanState.isInstantScanActive || scanState.isContinuousActive
        )

        // 2. Search Box
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("wifi_search_input"),
            placeholder = { Text("Search SSID, BSSID, or Vendor…", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search", modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // 3. Filter Chips Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedFilter == ApFilter.ALL,
                onClick = { selectedFilter = ApFilter.ALL },
                label = { Text("All (${scanState.accessPoints.size})", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("filter_all")
            )
            FilterChip(
                selected = selectedFilter == ApFilter.THREATS,
                onClick = { selectedFilter = ApFilter.THREATS },
                label = {
                    Text(
                        "Threats (${scanState.liveStats.threatsCount})",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (scanState.liveStats.threatsCount > 0) MaterialTheme.colorScheme.error else Color.Unspecified
                    )
                },
                modifier = Modifier.testTag("filter_threats")
            )
            FilterChip(
                selected = selectedFilter == ApFilter.OPEN,
                onClick = { selectedFilter = ApFilter.OPEN },
                label = { Text("Open (${scanState.liveStats.openCount})", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("filter_open")
            )
            FilterChip(
                selected = selectedFilter == ApFilter.BAND_5GHZ,
                onClick = { selectedFilter = ApFilter.BAND_5GHZ },
                label = { Text("5G (${scanState.liveStats.band5Count})", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("filter_5g")
            )
            FilterChip(
                selected = selectedFilter == ApFilter.BAND_24GHZ,
                onClick = { selectedFilter = ApFilter.BAND_24GHZ },
                label = { Text("2.4G (${scanState.liveStats.band24Count})", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("filter_24g")
            )
        }

        // 4. Access Point Results or Empty State
        if (filteredAps.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = if (scanState.accessPoints.isEmpty()) "No Wi-Fi Spectrum Captured" else "No matching access points found",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (scanState.accessPoints.isEmpty())
                            "Ensure device Wi-Fi and Location permissions are enabled, then trigger a scan."
                        else
                            "Try adjusting your search query or filter tags.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (scanState.accessPoints.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = onRefresh,
                            modifier = Modifier.testTag("empty_state_rescan_button")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Scan Spectrum Now")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(filteredAps, key = { it.bssid }) { ap ->
                    val isExpanded = expandedBssid == ap.bssid
                    AccessPointCard(
                        ap = ap,
                        isExpanded = isExpanded,
                        onToggleExpand = {
                            expandedBssid = if (isExpanded) null else ap.bssid
                        },
                        onCopyBssid = {
                            clipboardManager.setText(AnnotatedString(ap.bssid))
                        }
                    )
                }
            }
        }
    }
}

/**
 * LiveStatsDashboard – 4-cell quick-telemetry metric strip.
 */
@Composable
fun LiveStatsDashboard(
    stats: LiveSpectrumStats,
    activeCount: Int,
    isScanning: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "LIVE SPECTRUM TELEMETRY",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (isScanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "SWEEPING",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricItem(
                    label = "Networks",
                    value = activeCount.toString(),
                    subtext = "2.4G:${stats.band24Count} 5G:${stats.band5Count}",
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    label = "Threats",
                    value = stats.threatsCount.toString(),
                    subtext = if (stats.threatsCount > 0) "ANOMALY" else "CLEAN",
                    valueColor = if (stats.threatsCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    label = "Security",
                    value = if (stats.openCount > 0) "${stats.openCount} Open" else "Secure",
                    subtext = "WPA3:${stats.wpa3Count}",
                    valueColor = if (stats.openCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    label = "Peak RSSI",
                    value = if (stats.maxSignalDbm > -100) "${stats.maxSignalDbm} dBm" else "N/A",
                    subtext = if (stats.avgSignalDbm > -100) "avg ${stats.avgSignalDbm}" else "",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MetricItem(
    label: String,
    value: String,
    subtext: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                fontSize = 13.sp
            )
            if (subtext.isNotEmpty()) {
                Text(
                    text = subtext,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp
                )
            }
        }
    }
}

/**
 * AccessPointCard – renders detailed Wi-Fi beacon telemetry and security posture.
 */
@Composable
fun AccessPointCard(
    ap: EnrichedScanResult,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopyBssid: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isThreat = ap.riskReasons.isNotEmpty() || ap.riskScore >= 70

    // Color code RSSI signal strength
    val signalColor = when {
        ap.levelDbm >= -65 -> Color(0xFF4CAF50) // Green
        ap.levelDbm >= -75 -> Color(0xFFFFB300) // Amber
        ap.levelDbm >= -85 -> Color(0xFFFF9800) // Orange
        else -> Color(0xFFF44336)              // Red
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() }
            .testTag("ap_card_${ap.bssid}"),
        shape = RoundedCornerShape(12.dp),
        border = if (isThreat) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isThreat)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: Signal meter, SSID, Security Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Wifi,
                        contentDescription = "Signal",
                        tint = signalColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            text = if (ap.ssid.isNotBlank()) ap.ssid else "<Hidden Network>",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (ap.ssid.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = ap.bssid,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                            if (ap.vendorIdentities.isNotEmpty()) {
                                Text(
                                    text = " • ${ap.vendorIdentities.first()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                // Security & Signal Badges
                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when {
                            ap.isOpen -> MaterialTheme.colorScheme.errorContainer
                            ap.isWep -> MaterialTheme.colorScheme.tertiaryContainer
                            ap.isWpa3 -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        }
                    ) {
                        Text(
                            text = ap.securitySummary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp,
                            color = when {
                                ap.isOpen -> MaterialTheme.colorScheme.onErrorContainer
                                ap.isWep -> MaterialTheme.colorScheme.onTertiaryContainer
                                ap.isWpa3 -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.onSecondaryContainer
                            }
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${ap.levelDbm} dBm",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = signalColor,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Secondary Details: Band, Channel, Frequency
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ch ${ap.channel} • ${ap.frequencyMhz} MHz (${ap.band}) • ${ap.channelWidth}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                Text(
                    text = ap.wifiStandard,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }

            // Threat Highlight Banner if flagged
            if (isThreat && ap.riskReasons.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = ap.riskReasons.first(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Expandable Inspection Details
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Risk Assessment Score: ${ap.riskScore}/100",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (ap.riskScore >= 50) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "RTT: ${if (ap.isRttResponder) "Supported" else "No"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (ap.riskReasons.isNotEmpty()) {
                        ap.riskReasons.forEach { reason ->
                            Text(
                                text = "• $reason",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Text(
                        text = "Raw Capabilities: ${ap.capabilitiesRaw}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = onCopyBssid,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy BSSID", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
