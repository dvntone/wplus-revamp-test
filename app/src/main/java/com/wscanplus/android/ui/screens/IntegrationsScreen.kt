package com.wscanplus.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wscanplus.android.WscanPlusApplication
import com.wscanplus.android.core.AgentMcpServer
import com.wscanplus.android.core.AppPreferences
import com.wscanplus.android.core.IntegrationsManager
import com.wscanplus.android.core.LinuxEnvironmentService
import com.wscanplus.android.core.PixelAvfDebianGuest
import com.wscanplus.android.core.TermuxBridge
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    val app = context.applicationContext as WscanPlusApplication
    val mcpServer = app.mcpServer

    // Top Tabs
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("AVF Terminal", "AI Agent & MCP", "System Status")

    // State for integration statuses
    var pixelAvf by remember { mutableStateOf(IntegrationsManager.getPixelAvfInfo(context)) }
    var shizukuStatus by remember { mutableStateOf(IntegrationsManager.getShizukuStatus(context)) }
    var rootInfo by remember { mutableStateOf(IntegrationsManager.getRootInfo(context)) }
    var adbInfo by remember { mutableStateOf(IntegrationsManager.getAdbInfo(context)) }
    var termuxStatus by remember { mutableStateOf(TermuxBridge.TermuxStatus()) }
    var avfGuestState by remember { mutableStateOf(PixelAvfDebianGuest.AvfGuestState()) }

    // AVF Debian Probed Binaries State
    val probedBinaries by LinuxEnvironmentService.installedBinaries.collectAsState()
    val isProbingBinaries by LinuxEnvironmentService.isProbingBinaries.collectAsState()

    // MCP Server State
    var isMcpRunning by remember { mutableStateOf(mcpServer.isServerRunning()) }
    var localIp by remember { mutableStateOf(AgentMcpServer.getLocalIpAddress()) }

    // Terminal console state
    var selectedBackend by remember { mutableStateOf(IntegrationsManager.ExecutionBackend.PIXEL_AVF) }
    var backendMenuExpanded by remember { mutableStateOf(false) }
    var commandInput by remember { mutableStateOf("uname -a") }
    var commandOutput by remember { mutableStateOf("") }
    var isExecuting by remember { mutableStateOf(false) }

    fun refreshAllStatuses() {
        pixelAvf = IntegrationsManager.getPixelAvfInfo(context)
        shizukuStatus = IntegrationsManager.getShizukuStatus(context)
        rootInfo = IntegrationsManager.getRootInfo(context)
        adbInfo = IntegrationsManager.getAdbInfo(context)
        isMcpRunning = mcpServer.isServerRunning()
        localIp = AgentMcpServer.getLocalIpAddress()
        scope.launch {
            termuxStatus = TermuxBridge.getTermuxStatus(context)
        }
        scope.launch {
            avfGuestState = PixelAvfDebianGuest.probeGuestState(context)
        }
        scope.launch {
            LinuxEnvironmentService.probeInstalledBinaries(context)
        }
    }

    LaunchedEffect(Unit) {
        refreshAllStatuses()
    }

    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, _ ->
            refreshAllStatuses()
        }
        try {
            Shizuku.addRequestPermissionResultListener(listener)
        } catch (_: Throwable) {}

        onDispose {
            try {
                Shizuku.removeRequestPermissionResultListener(listener)
            } catch (_: Throwable) {}
        }
    }

    fun runCommand(cmd: String) {
        if (cmd.isBlank() || isExecuting) return
        isExecuting = true
        scope.launch {
            val res = IntegrationsManager.executeDiagnostic(context, selectedBackend, cmd)
            val resultText = buildString {
                appendLine("$ [${selectedBackend.displayName}] $cmd")
                appendLine("Exit code: ${res.exitCode}")
                if (res.output.isNotBlank()) {
                    appendLine("--- STDOUT ---")
                    appendLine(res.output)
                }
                if (!res.error.isNullOrBlank()) {
                    appendLine("--- STDERR / ERROR ---")
                    appendLine(res.error)
                }
                appendLine()
            }
            commandOutput = resultText + commandOutput
            isExecuting = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminal & AI Integrations", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = {
                            refreshAllStatuses()
                            scope.launch { snackbarHostState.showSnackbar("Statuses refreshed") }
                        },
                        modifier = Modifier.testTag("refresh_integrations_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
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
        ) {
            // Navigation Tabs
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        // ── Tab 0: Direct AVF Terminal Session & Environments ────
                        // Pixel AVF Guest VM Card with 5-State Reporting
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(Icons.Filled.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Column {
                                            Text("Pixel AVF Debian Guest VM", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            Text(
                                                avfGuestState.osPrettyName ?: (if (pixelAvf.isTerminalAppInstalled) "Pixel Linux VM Terminal Installed" else pixelAvf.description),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                            )
                                        }
                                    }

                                    FilledTonalButton(
                                        onClick = { IntegrationsManager.launchPixelTerminal(context) },
                                        modifier = Modifier.testTag("launch_pixel_terminal_btn")
                                    ) {
                                        Text("Open Terminal")
                                    }
                                }

                                // 5 Independent AVF Connection States
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    StateChip("Installed", avfGuestState.isInstalled)
                                    StateChip("Authorized", avfGuestState.isAuthorized)
                                    StateChip("Running", avfGuestState.isRunning)
                                    StateChip("Reachable", avfGuestState.isReachable)
                                    StateChip("Debian Verified", avfGuestState.isExecutionVerified)
                                }

                                if (avfGuestState.installedTools.isNotEmpty()) {
                                    Text(
                                        text = "Discovered Guest Tools: ${avfGuestState.installedTools.joinToString(", ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        // Probed AVF Guest Security Binaries Card (Kismet, tcpdump, TShark)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("probed_binaries_card"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Security,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Column {
                                            Text(
                                                "Debian Guest Security Binaries",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            val installedCount = probedBinaries.count { it.isInstalled }
                                            val totalCount = if (probedBinaries.isNotEmpty()) probedBinaries.size else 3
                                            Text(
                                                if (probedBinaries.isEmpty()) "Probe kismet, tcpdump, tshark & tools"
                                                else "$installedCount of $totalCount installed in guest",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            scope.launch {
                                                LinuxEnvironmentService.probeInstalledBinaries(context)
                                                snackbarHostState.showSnackbar("Probed AVF guest binaries")
                                            }
                                        },
                                        enabled = !isProbingBinaries,
                                        modifier = Modifier.testTag("probe_avf_binaries_button")
                                    ) {
                                        if (isProbingBinaries) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text("Probing...")
                                        } else {
                                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Probe Binaries")
                                        }
                                    }
                                }

                                if (isProbingBinaries) {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("probing_progress_indicator")
                                    )
                                }

                                // List of core probed tools
                                val displayList = if (probedBinaries.isNotEmpty()) {
                                    probedBinaries
                                } else {
                                    // Default initial representation before probe
                                    listOf(
                                        LinuxEnvironmentService.InstalledBinaryInfo(
                                            binary = "kismet",
                                            displayName = "Kismet Wireless IDS",
                                            isInstalled = false,
                                            version = null,
                                            category = LinuxEnvironmentService.ToolCategory.WIFI_AUDIT
                                        ),
                                        LinuxEnvironmentService.InstalledBinaryInfo(
                                            binary = "tcpdump",
                                            displayName = "tcpdump Packet Capture",
                                            isInstalled = false,
                                            version = null,
                                            category = LinuxEnvironmentService.ToolCategory.PACKET_ANALYSIS
                                        ),
                                        LinuxEnvironmentService.InstalledBinaryInfo(
                                            binary = "tshark",
                                            displayName = "TShark Protocol Dissector",
                                            isInstalled = false,
                                            version = null,
                                            category = LinuxEnvironmentService.ToolCategory.PACKET_ANALYSIS
                                        )
                                    )
                                }

                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    displayList.forEach { info ->
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("binary_item_${info.binary}"),
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            tonalElevation = 2.dp
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(
                                                    imageVector = when (info.category) {
                                                        LinuxEnvironmentService.ToolCategory.WIFI_AUDIT -> Icons.Filled.Wifi
                                                        LinuxEnvironmentService.ToolCategory.PACKET_ANALYSIS -> Icons.Filled.NetworkCheck
                                                        LinuxEnvironmentService.ToolCategory.SDR_RF -> Icons.Filled.Sensors
                                                        LinuxEnvironmentService.ToolCategory.NETWORK_DISCOVERY -> Icons.Filled.Radar
                                                        LinuxEnvironmentService.ToolCategory.IDS_IPS -> Icons.Filled.Shield
                                                    },
                                                    contentDescription = null,
                                                    tint = if (info.isInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                    modifier = Modifier.size(24.dp)
                                                )

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Text(
                                                            text = info.binary,
                                                            style = MaterialTheme.typography.titleSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                        Text(
                                                            text = "(${info.displayName})",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }

                                                    if (info.isInstalled) {
                                                        if (!info.version.isNullOrBlank()) {
                                                            Text(
                                                                text = "Version: ${info.version}",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                fontWeight = FontWeight.SemiBold,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = Color(0xFF2E7D32)
                                                            )
                                                        }
                                                        if (!info.binaryPath.isNullOrBlank()) {
                                                            Text(
                                                                text = "Path: ${info.binaryPath}",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    } else {
                                                        Text(
                                                            text = "Not found in AVF guest ($ sudo apt install ${info.binary})",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                                        )
                                                    }
                                                }

                                                // Installed / Missing Status Chip
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = if (info.isInstalled) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = if (info.isInstalled) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                                                            contentDescription = null,
                                                            tint = if (info.isInstalled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Text(
                                                            text = if (info.isInstalled) "Installed" else "Missing",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (info.isInstalled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Termux Linux Userland Card with 5-State Reporting
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(Icons.Filled.Code, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                        Column {
                                            Text("Termux Userland & TUI", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            Text(
                                                termuxStatus.statusMessage,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                            )
                                        }
                                    }

                                    if (termuxStatus.isInstalled) {
                                        FilledTonalButton(
                                            onClick = { TermuxBridge.launchTermux(context) },
                                            modifier = Modifier.testTag("launch_termux_btn")
                                        ) {
                                            Text("Open Termux")
                                        }
                                    }
                                }

                                // 5 Independent Termux Connection States
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    StateChip("Installed", termuxStatus.isInstalled)
                                    StateChip("Permission", termuxStatus.isPermissionGranted)
                                    StateChip("Running", termuxStatus.isRunning)
                                    StateChip("Reachable", termuxStatus.isReachable)
                                    StateChip("Verified", termuxStatus.isExecutionVerified)
                                }

                                if (termuxStatus.installedTools.isNotEmpty()) {
                                    Text(
                                        text = "Discovered Userland Tools: ${termuxStatus.installedTools.joinToString(", ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }

                        // Terminal Console Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("terminal_console_card"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Interactive Diagnostic Shell",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(commandOutput))
                                            scope.launch { snackbarHostState.showSnackbar("Copied output") }
                                        },
                                        enabled = commandOutput.isNotBlank()
                                    ) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                                    }
                                }

                                // Backend Selector
                                ExposedDropdownMenuBox(
                                    expanded = backendMenuExpanded,
                                    onExpandedChange = { backendMenuExpanded = !backendMenuExpanded }
                                ) {
                                    OutlinedTextField(
                                        value = selectedBackend.displayName,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Active Terminal Backend") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(backendMenuExpanded) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = backendMenuExpanded,
                                        onDismissRequest = { backendMenuExpanded = false }
                                    ) {
                                        IntegrationsManager.ExecutionBackend.entries.forEach { backend ->
                                            DropdownMenuItem(
                                                text = { Text(backend.displayName) },
                                                onClick = {
                                                    selectedBackend = backend
                                                    backendMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                // Quick Command Presets
                                Text(
                                    text = "Quick Diagnostic Presets:",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(
                                        "uname -a",
                                        "iw dev",
                                        "cmd wifi status",
                                        "cmd wifi scan-results",
                                        "airmon-ng",
                                        "id",
                                        "cat /proc/version",
                                        "getprop ro.product.model"
                                    ).forEach { cmd ->
                                        AssistChip(
                                            onClick = {
                                                commandInput = cmd
                                                runCommand(cmd)
                                            },
                                            label = { Text(cmd, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                                        )
                                    }
                                }

                                // Command Input Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = commandInput,
                                        onValueChange = { commandInput = it },
                                        label = { Text("Command") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("command_input_field"),
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                                    )
                                    Button(
                                        onClick = { runCommand(commandInput) },
                                        enabled = !isExecuting && commandInput.isNotBlank(),
                                        modifier = Modifier.testTag("run_command_button")
                                    ) {
                                        if (isExecuting) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        } else {
                                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }

                                // Terminal Output Window
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 180.dp, max = 340.dp)
                                        .background(Color(0xFF0D1117), shape = RoundedCornerShape(8.dp))
                                        .padding(12.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = if (commandOutput.isBlank())
                                            "> Pixel AVF Terminal & Subsystem ready.\n> Type a command or pick a preset above to begin."
                                        else
                                            commandOutput,
                                        color = Color(0xFF58A6FF),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                }

                                if (commandOutput.isNotBlank()) {
                                    TextButton(
                                        onClick = { commandOutput = "" },
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text("Clear Terminal")
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // ── Tab 1: AI Agent & MCP Bridge ─────────────────────────
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isMcpRunning)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isMcpRunning) Color(0xFF2EA043) else MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(12.dp)
                                        ) {}
                                        Column {
                                            Text(
                                                text = "On-Device MCP Bridge Server",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = if (isMcpRunning) "Active on port ${mcpServer.port}" else "Server Inactive",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            if (isMcpRunning) {
                                                mcpServer.stop()
                                                isMcpRunning = false
                                                scope.launch { snackbarHostState.showSnackbar("MCP Server stopped") }
                                            } else {
                                                val started = mcpServer.start()
                                                isMcpRunning = started
                                                if (started) {
                                                    scope.launch { snackbarHostState.showSnackbar("MCP Server listening on port ${mcpServer.port}") }
                                                } else {
                                                    scope.launch { snackbarHostState.showSnackbar("Failed to bind port ${mcpServer.port}") }
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isMcpRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                        ),
                                        modifier = Modifier.testTag("toggle_mcp_server_button")
                                    ) {
                                        Text(if (isMcpRunning) "Stop" else "Start Server")
                                    }
                                }

                                HorizontalDivider()

                                Text(
                                    text = "Allows CLI AI agents (Claude Code, Gemini CLI, Cursor, GlassHaven / SSH Haven terminal) to execute AVF commands, inspect real-time threat captures, and trigger security audits over local network or localhost.",
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("Connection Endpoints (Zero-Trust Loopback):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        Text("• Host Bridge: adb forward tcp:${mcpServer.port} tcp:${mcpServer.port}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                        Text("• MCP Protocol: http://127.0.0.1:${mcpServer.port}/mcp", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                        Text("• Terminal REST: http://127.0.0.1:${mcpServer.port}/api/terminal", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                        Text("• Threat Logs: http://127.0.0.1:${mcpServer.port}/api/threats", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                        Text("• System Status: http://127.0.0.1:${mcpServer.port}/status", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val token = AppPreferences.getOrCreateMcpAuthToken(context)
                                            val configJson = """
{
  "mcpServers": {
    "wscanplus-avf": {
      "url": "http://127.0.0.1:${mcpServer.port}/mcp",
      "headers": {
        "Authorization": "Bearer $token"
      }
    }
  }
}
                                            """.trimIndent()
                                            clipboardManager.setText(AnnotatedString(configJson))
                                            scope.launch { snackbarHostState.showSnackbar("Copied MCP config with auth token") }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Copy MCP Config")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val token = AppPreferences.getOrCreateMcpAuthToken(context)
                                            val curlCmd = "curl -X POST http://127.0.0.1:${mcpServer.port}/api/terminal -H \"Authorization: Bearer $token\" -H \"Content-Type: application/json\" -d '{\"command\": \"uname -a\"}'"
                                            clipboardManager.setText(AnnotatedString(curlCmd))
                                            scope.launch { snackbarHostState.showSnackbar("Copied authenticated CLI command") }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Copy CLI Command")
                                    }
                                }

                                if (mcpServer.totalQueriesHandled > 0) {
                                    Text(
                                        text = "Activity: ${mcpServer.totalQueriesHandled} requests handled (Last: ${mcpServer.lastQuerySummary})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // MCP Tools Showcase
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("Exposed MCP Tools for AI Agents", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                                McpToolItem(
                                    name = "avf_run_command",
                                    desc = "Direct execution of shell commands in the Pixel AVF Linux container or selected backend."
                                )
                                McpToolItem(
                                    name = "wscan_get_threats",
                                    desc = "Inspect live Wi-Fi threats (Evil Twin, Deauth Floods, PMKID Sniffing, KRACK)."
                                )
                                McpToolItem(
                                    name = "wscan_analyze_threats",
                                    desc = "Runs threat assessment heuristics and generates defense strategies."
                                )
                                McpToolItem(
                                    name = "wscan_system_status",
                                    desc = "Returns status of Pixel AVF, Shizuku privileges, Root access, and Wireless Debugging."
                                )
                            }
                        }
                    }

                    2 -> {
                        // ── Tab 2: System Status & Hardware Cards ────────────────
                        StatusOverviewRow(
                            pixelAvf = pixelAvf,
                            shizuku = shizukuStatus,
                            root = rootInfo,
                            adb = adbInfo
                        )

                        // Pixel AVF Card
                        IntegrationCard(
                            title = "Native Pixel AVF Linux Terminal",
                            subtitle = "Google Pixel Linux Virtual Machine (Debian/Ubuntu)",
                            icon = Icons.Filled.Computer,
                            isActive = pixelAvf.isTerminalAppInstalled,
                            statusText = if (pixelAvf.isTerminalAppInstalled) "Installed & Ready"
                            else if (pixelAvf.isPixelDevice && pixelAvf.isAvfSupported) "Available in Developer Options"
                            else if (pixelAvf.isAvfSupported) "AVF Supported"
                            else "Unavailable",
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(text = pixelAvf.description, style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${pixelAvf.androidVersion})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val launched = IntegrationsManager.launchPixelTerminal(context)
                                        if (!launched) {
                                            scope.launch { snackbarHostState.showSnackbar("Enable in Developer options.") }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (pixelAvf.isTerminalAppInstalled) "Launch App" else "Enable in Settings")
                                }
                                OutlinedButton(onClick = { IntegrationsManager.openDeveloperSettings(context) }) {
                                    Text("Dev Options")
                                }
                            }
                        }

                        // Shizuku Card
                        IntegrationCard(
                            title = "Shizuku Privileged ADB Service",
                            subtitle = "User-space ADB daemon without root",
                            icon = Icons.Filled.Security,
                            isActive = shizukuStatus.isPermissionGranted,
                            statusText = shizukuStatus.statusMessage,
                            color = MaterialTheme.colorScheme.secondary
                        ) {
                            Text(
                                text = "Bypasses Android Wi-Fi scan throttling (4 scans / 2 min) and grants elevated access to Wi-Fi management APIs.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (!shizukuStatus.isBinderAlive) {
                                            scope.launch { snackbarHostState.showSnackbar("Shizuku is not running.") }
                                        } else {
                                            IntegrationsManager.requestShizukuPermission()
                                        }
                                    },
                                    enabled = !shizukuStatus.isPermissionGranted,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (shizukuStatus.isPermissionGranted) "Granted" else "Request Permission")
                                }
                            }
                        }

                        // Root Card
                        IntegrationCard(
                            title = "Root Access (Superuser)",
                            subtitle = rootInfo.rootManager ?: "Direct su binary",
                            icon = Icons.Filled.Bolt,
                            isActive = rootInfo.isSuBinaryFound,
                            statusText = if (rootInfo.isSuBinaryFound) "Detected (${rootInfo.rootManager ?: "su"})" else "No root found",
                            color = MaterialTheme.colorScheme.tertiary
                        ) {
                            Text(
                                text = "Required for raw 802.11 monitor mode (airmon-ng), packet injection, and hardware Wi-Fi dongles.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val res = IntegrationsManager.testRootAccess()
                                            if (res.success) {
                                                snackbarHostState.showSnackbar("Root Verified: ${res.output}")
                                            } else {
                                                snackbarHostState.showSnackbar("Root Failed: ${res.error}")
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Verify Root Access")
                                }
                            }
                        }

                        // Wireless ADB Card
                        IntegrationCard(
                            title = "Wireless & ADB Debugging",
                            subtitle = "Local wireless ADB shell & remote connectivity",
                            icon = Icons.Filled.Wifi,
                            isActive = adbInfo.isWirelessAdbEnabled,
                            statusText = if (adbInfo.isWirelessAdbEnabled) "Wireless Debugging Active"
                            else if (adbInfo.isUsbDebuggingEnabled) "USB Debugging Enabled"
                            else "Debugging Inactive",
                            color = MaterialTheme.colorScheme.secondary
                        ) {
                            Text(text = adbInfo.statusDescription, style = MaterialTheme.typography.bodySmall)
                            Button(
                                onClick = { IntegrationsManager.openDeveloperSettings(context) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.SettingsEthernet, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Wireless Debugging Settings")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpToolItem(name: String, desc: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusOverviewRow(
    pixelAvf: IntegrationsManager.PixelAvfInfo,
    shizuku: IntegrationsManager.ShizukuStatus,
    root: IntegrationsManager.RootInfo,
    adb: IntegrationsManager.AdbInfo
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusBadge("Pixel AVF", pixelAvf.isTerminalAppInstalled, if (pixelAvf.isTerminalAppInstalled) "VM Ready" else "Available")
        StatusBadge("Shizuku", shizuku.isPermissionGranted, if (shizuku.isPermissionGranted) "Active" else "Pending")
        StatusBadge("Root", root.isSuBinaryFound, if (root.isSuBinaryFound) (root.rootManager ?: "su") else "None")
        StatusBadge("Wireless ADB", adb.isWirelessAdbEnabled, if (adb.isWirelessAdbEnabled) "Active" else "Off")
    }
}

@Composable
private fun StatusBadge(label: String, isActive: Boolean, valueText: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (isActive) Color(0xFF2EA043) else Color(0xFF8B949E),
                        shape = RoundedCornerShape(4.dp)
                    )
            )
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text(valueText, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun IntegrationCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    statusText: String,
    color: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = color.copy(alpha = 0.15f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        }
                    }
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isActive) Color(0xFF2EA043).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = statusText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isActive) Color(0xFF2EA043) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            content()
        }
    }
}

@Composable
private fun StateChip(label: String, isOk: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isOk) Color(0xFF2EA043).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        color = if (isOk) Color(0xFF2EA043) else Color(0xFF8B949E),
                        shape = RoundedCornerShape(3.dp)
                    )
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isOk) Color(0xFF1B6B2F) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

