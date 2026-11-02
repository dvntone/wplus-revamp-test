package com.wscanplus.android.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
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
import com.wscanplus.android.WscanPlusApplication
import com.wscanplus.android.core.AiProviderManager
import com.wscanplus.android.core.AppPreferences
import com.wscanplus.android.core.IntegrationsManager
import com.wscanplus.android.core.McpAgentActivity
import com.wscanplus.android.data.db.AppDatabase
import com.wscanplus.android.data.db.ThreatLog
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAnalysisScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val prefs = remember { AppPreferences.main(context) }
    val aiManager = remember { AiProviderManager(context) }
    val app = context.applicationContext as WscanPlusApplication
    val mcpServer = app.mcpServer

    val threats by db.threatLogDao().observeRecent(50).collectAsState(initial = emptyList())
    val agentActivities by mcpServer.agentActivities.collectAsState()

    // Presentation Mode state: MASCOT (default), FEED, TERMINAL
    var selectedUiMode by remember {
        mutableStateOf(
            prefs.getString(AppPreferences.KEY_AI_UI_MODE, AppPreferences.AI_UI_MODE_MASCOT)
                ?: AppPreferences.AI_UI_MODE_MASCOT
        )
    }

    // AI Provider state
    var selectedProvider by remember {
        mutableStateOf(
            AiProviderManager.ProviderType.fromId(
                prefs.getString(AppPreferences.KEY_AI_PROVIDER, AppPreferences.AI_PROVIDER_OFFLINE_RULES)
                    ?: AppPreferences.AI_PROVIDER_OFFLINE_RULES
            )
        )
    }

    var showConfigDialog by remember { mutableStateOf(false) }
    var currentAssessment by remember { mutableStateOf<AiProviderManager.AiAssessment?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }

    fun setUiMode(mode: String) {
        selectedUiMode = mode
        prefs.edit().putString(AppPreferences.KEY_AI_UI_MODE, mode).apply()
    }

    fun setProvider(provider: AiProviderManager.ProviderType) {
        selectedProvider = provider
        prefs.edit().putString(AppPreferences.KEY_AI_PROVIDER, provider.id).apply()
    }

    fun runAssessment() {
        scope.launch {
            isAnalyzing = true
            currentAssessment = aiManager.analyzeThreats(threats, selectedProvider)
            isAnalyzing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.nyx_avatar),
                            contentDescription = "Nyx Avatar",
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("wscan+ .nyx", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "AI Threat Intelligence",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showConfigDialog = true },
                        modifier = Modifier.testTag("ai_config_button")
                    ) {
                        Icon(Icons.Filled.Tune, contentDescription = "Configure AI Providers")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Presentation Selector Bar (Mascot / Live Feed / Terminal Feedback)
            TabRow(
                selectedTabIndex = when (selectedUiMode) {
                    AppPreferences.AI_UI_MODE_FEED -> 1
                    AppPreferences.AI_UI_MODE_TERMINAL -> 2
                    else -> 0
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedUiMode == AppPreferences.AI_UI_MODE_MASCOT,
                    onClick = { setUiMode(AppPreferences.AI_UI_MODE_MASCOT) },
                    text = { Text(".nyx Mascot") },
                    icon = { Icon(Icons.Filled.SmartToy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("tab_mascot")
                )
                Tab(
                    selected = selectedUiMode == AppPreferences.AI_UI_MODE_FEED,
                    onClick = { setUiMode(AppPreferences.AI_UI_MODE_FEED) },
                    text = { Text("Live Feed") },
                    icon = { Icon(Icons.Filled.DynamicFeed, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("tab_feed")
                )
                Tab(
                    selected = selectedUiMode == AppPreferences.AI_UI_MODE_TERMINAL,
                    onClick = { setUiMode(AppPreferences.AI_UI_MODE_TERMINAL) },
                    text = { Text("Terminal") },
                    icon = { Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("tab_terminal")
                )
            }

            // Active Provider Indicator Strip
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (selectedProvider.isLocal) Icons.Filled.Memory else Icons.Filled.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = selectedProvider.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(
                        onClick = { showConfigDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Switch", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Main Content Area based on User's Presentation Choice
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (selectedUiMode) {
                    AppPreferences.AI_UI_MODE_FEED -> {
                        LiveFeedView(
                            threats = threats,
                            assessment = currentAssessment,
                            isAnalyzing = isAnalyzing,
                            onRunAnalysis = { runAssessment() }
                        )
                    }
                    AppPreferences.AI_UI_MODE_TERMINAL -> {
                        TerminalFeedbackView(
                            threats = threats,
                            activities = agentActivities,
                            assessment = currentAssessment,
                            onRunCommand = { cmd ->
                                when {
                                    cmd.startsWith("avf ") -> {
                                        val realCmd = cmd.removePrefix("avf ").trim()
                                        IntegrationsManager.executeDiagnostic(context, IntegrationsManager.ExecutionBackend.PIXEL_AVF, realCmd).output
                                    }
                                    cmd.equals("analyze", ignoreCase = true) -> {
                                        runAssessment()
                                        "Dispatched threat analysis to ${selectedProvider.displayName}..."
                                    }
                                    cmd.equals("threats", ignoreCase = true) -> {
                                        if (threats.isEmpty()) "No threats recorded in IDS database."
                                        else threats.take(10).joinToString("\n") { "[${it.severity}] ${it.attackType} - ${it.bssid} (${it.ssid})" }
                                    }
                                    cmd.equals("status", ignoreCase = true) -> {
                                        "Backend: ${selectedProvider.displayName} | MCP Server: port 8080 (${if (mcpServer.isServerRunning()) "ONLINE" else "OFFLINE"})"
                                    }
                                    else -> aiManager.queryAgent(cmd, threats)
                                }
                            }
                        )
                    }
                    else -> {
                        // Default: AI Mascot Mode (.nyx)
                        NyxMascotView(
                            threats = threats,
                            assessment = currentAssessment,
                            isAnalyzing = isAnalyzing,
                            onRunAssessment = { runAssessment() },
                            onQueryAgent = { msg -> aiManager.queryAgent(msg, threats) }
                        )
                    }
                }
            }
        }
    }

    if (showConfigDialog) {
        AiProviderConfigDialog(
            currentProvider = selectedProvider,
            onSelectProvider = {
                setProvider(it)
                showConfigDialog = false
            },
            onDismiss = { showConfigDialog = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mascot View (.nyx)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NyxMascotView(
    threats: List<ThreatLog>,
    assessment: AiProviderManager.AiAssessment?,
    isAnalyzing: Boolean,
    onRunAssessment: () -> Unit,
    onQueryAgent: suspend (String) -> String
) {
    val scope = rememberCoroutineScope()
    var nyxSpeech by remember {
        mutableStateOf(
            if (threats.isEmpty()) {
                "Hello operator! I'm Nyx, your wscan+ security mascot. I'm actively monitoring your 802.11 spectrum. No intrusions detected!"
            } else {
                "Warning operator! I detected ${threats.size} active threat frames! Let's analyze and neutralize the rogue vectors."
            }
        )
    }
    var promptInput by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(false) }

    val threatLevel = assessment?.threatLevel ?: when {
        threats.any { it.severity.equals("CRITICAL", true) } -> "CRITICAL"
        threats.any { it.severity.equals("HIGH", true) } -> "HIGH"
        threats.any { it.severity.equals("MEDIUM", true) } -> "MEDIUM"
        threats.isNotEmpty() -> "LOW"
        else -> "NOMINAL"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Mascot Brand Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Image(
                        painter = painterResource(id = R.drawable.nyx_avatar),
                        contentDescription = "Nyx Robot Mascot",
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Surface(
                        shape = CircleShape,
                        color = when (threatLevel) {
                            "CRITICAL", "HIGH" -> MaterialTheme.colorScheme.error
                            "MEDIUM" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = when (threatLevel) {
                                "CRITICAL", "HIGH" -> Icons.Filled.Warning
                                "MEDIUM" -> Icons.Filled.Shield
                                else -> Icons.Filled.CheckCircle
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "wscan+ .nyx",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )

                // Tactical brand tags from uploaded design
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TacticalBadge("Linux-First")
                    TacticalBadge("Privacy Focused")
                    TacticalBadge("Operator Built")
                }
            }
        }

        // Mascot Speech Bubble Dialogue
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Nyx Tactical Assessment",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    if (isThinking || isAnalyzing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                }

                Text(
                    text = assessment?.fullAnalysis ?: nyxSpeech,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp
                )

                if (assessment?.mitigations?.isNotEmpty() == true) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Countermeasures:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    assessment.mitigations.forEach { item ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("🛡️ ", fontSize = 12.sp)
                            Text(item, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRunAssessment,
                enabled = !isAnalyzing,
                modifier = Modifier
                    .weight(1f)
                    .testTag("mascot_analyze_button")
            ) {
                Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (isAnalyzing) "Auditing…" else "Audit Threats")
            }
        }

        // Quick Topic Chips
        Text(
            text = "Direct Tactics with Nyx",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Start)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = {
                    scope.launch {
                        isThinking = true
                        nyxSpeech = onQueryAgent("Explain Evil Twin defense and how wscan+ isolates rogue APs.")
                        isThinking = false
                    }
                },
                label = { Text("Evil Twin Advice") },
                leadingIcon = { Icon(Icons.Filled.WifiTethering, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
            AssistChip(
                onClick = {
                    scope.launch {
                        isThinking = true
                        nyxSpeech = onQueryAgent("How do I protect against 802.11 Deauth floods?")
                        isThinking = false
                    }
                },
                label = { Text("Deauth Defense") },
                leadingIcon = { Icon(Icons.Filled.SignalWifiOff, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }

        // Direct Agent Message Input
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                placeholder = { Text("Ask Nyx about wireless defense…") },
                modifier = Modifier
                    .weight(1f)
                    .testTag("nyx_input_field"),
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val msg = promptInput.trim()
                    if (msg.isNotBlank()) {
                        promptInput = ""
                        scope.launch {
                            isThinking = true
                            nyxSpeech = onQueryAgent(msg)
                            isThinking = false
                        }
                    }
                },
                enabled = promptInput.isNotBlank() && !isThinking,
                modifier = Modifier.testTag("nyx_send_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to Nyx", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Live Feed View
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LiveFeedView(
    threats: List<ThreatLog>,
    assessment: AiProviderManager.AiAssessment?,
    isAnalyzing: Boolean,
    onRunAnalysis: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Live Threat Assessment Stream", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (threats.isEmpty()) "Environment clear • Monitoring passive beacons"
                            else "${threats.size} events captured across radio spectrum",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = onRunAnalysis,
                        enabled = !isAnalyzing,
                        modifier = Modifier.testTag("feed_refresh_audit_button")
                    ) {
                        Text(if (isAnalyzing) "Running…" else "Analyze")
                    }
                }
            }
        }

        if (assessment != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Latest AI Synthesis", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            SeverityBadge(assessment.threatLevel)
                        }
                        Text(assessment.fullAnalysis, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Provider: ${assessment.providerName} • Latency: ${assessment.latencyMs} ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (threats.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No threats logged. Spectrum is clear.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(threats) { log ->
                ThreatFeedCard(log)
            }
        }
    }
}

@Composable
fun ThreatFeedCard(log: ThreatLog) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(log.attackType, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                SeverityBadge(log.severity)
            }
            Text(log.description, style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("BSSID: ${log.bssid}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Terminal Feedback View (GlassHaven / Haven Style)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TerminalFeedbackView(
    threats: List<ThreatLog>,
    activities: List<McpAgentActivity>,
    assessment: AiProviderManager.AiAssessment?,
    onRunCommand: suspend (String) -> String
) {
    val scope = rememberCoroutineScope()
    var cmdText by remember { mutableStateOf("") }
    val terminalLines = remember {
        mutableStateListOf(
            "wscan+ Haven CLI Terminal v1.0.0 [Pixel AVF & MCP Ready]",
            "Type 'help', 'status', 'threats', 'analyze', or 'avf <command>' to execute.",
            "────────────────────────────────────────────────────────────"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(12.dp)
    ) {
        // Agent Activity Section (Haven Style)
        Text(
            text = "AGENT ACTIVITY & MCP LATENCY",
            color = Color(0xFF58A6FF),
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (activities.isEmpty()) {
                    Text(
                        "No MCP agent calls logged yet. Listening on port 8080...",
                        color = Color(0xFF8B949E),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                } else {
                    activities.take(3).forEach { act ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${act.clientName} > ${act.toolName}",
                                color = Color(0xFFC9D1D9),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = if (act.status == "ok") Color(0xFF238636) else Color(0xFFDA3633),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        act.status,
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${act.latencyMs} ms",
                                    color = Color(0xFF58A6FF),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Raw Terminal Stream Window
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF090D13), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                terminalLines.forEach { line ->
                    Text(
                        text = line,
                        color = if (line.startsWith("$")) Color(0xFF7EE787)
                        else if (line.contains("Warning") || line.contains("CRITICAL")) Color(0xFFF85149)
                        else Color(0xFFC9D1D9),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Command Prompt Input
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$ ", color = Color(0xFF7EE787), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = cmdText,
                onValueChange = { cmdText = it },
                placeholder = { Text("cmd...", color = Color(0xFF484F58), fontSize = 12.sp) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("terminal_command_input"),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFC9D1D9),
                    unfocusedTextColor = Color(0xFFC9D1D9),
                    focusedContainerColor = Color(0xFF161B22),
                    unfocusedContainerColor = Color(0xFF161B22),
                    focusedBorderColor = Color(0xFF58A6FF),
                    unfocusedBorderColor = Color(0xFF30363D)
                )
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    val input = cmdText.trim()
                    if (input.isNotBlank()) {
                        cmdText = ""
                        terminalLines.add("$ $input")
                        scope.launch {
                            val output = onRunCommand(input)
                            terminalLines.addAll(output.lines())
                        }
                    }
                },
                enabled = cmdText.isNotBlank(),
                modifier = Modifier.testTag("terminal_send_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Run", tint = Color(0xFF58A6FF))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AI Provider Configuration Dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AiProviderConfigDialog(
    currentProvider: AiProviderManager.ProviderType,
    onSelectProvider: (AiProviderManager.ProviderType) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences.main(context) }

    var geminiKey by remember { mutableStateOf(AppPreferences.getSecureSecret(context, AppPreferences.KEY_GEMINI_KEY)) }
    var openAiBaseUrl by remember { mutableStateOf(prefs.getString(AppPreferences.KEY_OPENAI_BASE_URL, AppPreferences.DEFAULT_OPENAI_BASE_URL) ?: AppPreferences.DEFAULT_OPENAI_BASE_URL) }
    var openAiModel by remember { mutableStateOf(prefs.getString(AppPreferences.KEY_OPENAI_MODEL, AppPreferences.DEFAULT_OPENAI_MODEL) ?: AppPreferences.DEFAULT_OPENAI_MODEL) }
    var openAiKey by remember { mutableStateOf(AppPreferences.getSecureSecret(context, AppPreferences.KEY_OPENAI_API_KEY)) }
    var avfEndpoint by remember { mutableStateOf(prefs.getString(AppPreferences.KEY_AVF_ENDPOINT, AppPreferences.DEFAULT_AVF_ENDPOINT) ?: AppPreferences.DEFAULT_AVF_ENDPOINT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI Intelligence Backends") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Select active threat analysis engine:", style = MaterialTheme.typography.bodySmall)

                AiProviderManager.ProviderType.values().forEach { provider ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectProvider(provider) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (currentProvider == provider)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = if (currentProvider == provider)
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentProvider == provider,
                                onClick = { onSelectProvider(provider) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(provider.displayName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (provider.isLocal) "Zero network • High privacy on-device"
                                    else "Network API • Neural threat reasoning",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text("Provider Settings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

                // Gemini Key
                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Gemini API Key") },
                    placeholder = { Text("AIzaSy...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // OpenAI / Ollama Base URL
                OutlinedTextField(
                    value = openAiBaseUrl,
                    onValueChange = { openAiBaseUrl = it },
                    label = { Text("OpenAI / Ollama Base URL") },
                    placeholder = { Text("http://127.0.0.1:11434/v1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // OpenAI Model
                OutlinedTextField(
                    value = openAiModel,
                    onValueChange = { openAiModel = it },
                    label = { Text("Model Name") },
                    placeholder = { Text("llama3.2 or deepseek-chat") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // AVF Endpoint
                OutlinedTextField(
                    value = avfEndpoint,
                    onValueChange = { avfEndpoint = it },
                    label = { Text("Pixel AVF Endpoint") },
                    placeholder = { Text("http://127.0.0.1:8080") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    prefs.edit()
                        .putString(AppPreferences.KEY_OPENAI_BASE_URL, openAiBaseUrl.trim())
                        .putString(AppPreferences.KEY_OPENAI_MODEL, openAiModel.trim())
                        .putString(AppPreferences.KEY_AVF_ENDPOINT, avfEndpoint.trim())
                        .apply()
                    AppPreferences.setSecureSecret(context, AppPreferences.KEY_GEMINI_KEY, geminiKey.trim())
                    AppPreferences.setSecureSecret(context, AppPreferences.KEY_OPENAI_API_KEY, openAiKey.trim())
                    onDismiss()
                }
            ) {
                Text("Save Configuration")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// UI Helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TacticalBadge(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun SeverityBadge(severity: String) {
    val (color, text) = when (severity.uppercase()) {
        "CRITICAL" -> MaterialTheme.colorScheme.error to "CRITICAL"
        "HIGH" -> Color(0xFFE53935) to "HIGH"
        "MEDIUM" -> Color(0xFFFB8C00) to "MEDIUM"
        "LOW" -> Color(0xFF43A047) to "LOW"
        else -> MaterialTheme.colorScheme.primary to "CLEAN"
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, color)
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
