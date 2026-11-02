package com.wscanplus.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wscanplus.android.core.DataExporter
import com.wscanplus.android.data.db.AppDatabase
import com.wscanplus.android.data.db.ThreatLog
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var logs by remember { mutableStateOf<List<ThreatLog>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf("ALL") }

    LaunchedEffect(Unit) {
        db.threatLogDao().observeAll().collect { logs = it }
    }

    val filteredLogs = remember(logs, selectedFilter) {
        when (selectedFilter) {
            "HIGH" -> logs.filter { it.severity.equals("HIGH", ignoreCase = true) }
            "MEDIUM" -> logs.filter { it.severity.equals("MEDIUM", ignoreCase = true) }
            else -> logs
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Threat Alerts", fontWeight = FontWeight.Bold) },
                actions = {
                    if (logs.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val file = DataExporter.exportToCsv(context, logs)
                                scope.launch {
                                    if (file != null) {
                                        snackbarHostState.showSnackbar("Exported to ${file.name}")
                                    } else {
                                        snackbarHostState.showSnackbar("Export failed")
                                    }
                                }
                            },
                            modifier = Modifier.testTag("export_alerts_button")
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = "Export CSV")
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    db.threatLogDao().deleteAll()
                                    snackbarHostState.showSnackbar("Cleared all alerts")
                                }
                            },
                            modifier = Modifier.testTag("clear_alerts_button")
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear all alerts")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            if (logs.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedFilter == "ALL",
                        onClick = { selectedFilter = "ALL" },
                        label = { Text("All (${logs.size})") }
                    )
                    FilterChip(
                        selected = selectedFilter == "HIGH",
                        onClick = { selectedFilter = "HIGH" },
                        label = { Text("High (${logs.count { it.severity.equals("HIGH", true) }})") }
                    )
                    FilterChip(
                        selected = selectedFilter == "MEDIUM",
                        onClick = { selectedFilter = "MEDIUM" },
                        label = { Text("Medium (${logs.count { it.severity.equals("MEDIUM", true) }})") }
                    )
                }
            }

            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (logs.isEmpty()) "No alerts recorded yet." else "No alerts match the selected filter.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { log ->
                        AlertLogItem(log = log)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertLogItem(log: ThreatLog) {
    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault())
    }
    val severityColor = when (log.severity.uppercase()) {
        "HIGH" -> MaterialTheme.colorScheme.errorContainer
        "MEDIUM" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = when (log.severity.uppercase()) {
        "HIGH" -> MaterialTheme.colorScheme.onErrorContainer
        "MEDIUM" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = severityColor)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = log.attackType,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
                Text(
                    text = dateFormatter.format(Instant.ofEpochMilli(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.8f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = log.description,
                style = MaterialTheme.typography.bodySmall,
                color = textColor
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "BSSID: ${log.bssid}",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f)
                )
                if (log.ssid.isNotBlank()) {
                    Text(
                        text = "SSID: ${log.ssid}",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
