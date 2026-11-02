package com.wscanplus.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wscanplus.android.core.AppPreferences
import com.wscanplus.android.core.LinuxEnvironmentService
import com.wscanplus.android.core.SystemStatusChecker
import com.wscanplus.android.core.TerminalDetector
import com.wscanplus.android.core.UpdateChecker
import kotlinx.coroutines.launch

// ── Legacy compat aliases (used by other screens) ────────────────────────────
private const val PREFS_NAME      = AppPreferences.PREFS_MAIN
private const val KEY_FIREBASE_URL = AppPreferences.KEY_FIREBASE_URL
private const val KEY_DEVICE_ID   = AppPreferences.KEY_DEVICE_ID
private const val KEY_GEMINI_KEY  = AppPreferences.KEY_GEMINI_KEY
private const val KEY_DESKTOP_HOST = AppPreferences.KEY_DESKTOP_HOST

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val prefs   = remember { AppPreferences.main(context) }
    val featurePrefs = remember { AppPreferences.features(context) }

    // ── Cloud / companion ──────────────────────────────────────────────────
    var firebaseUrl  by remember { mutableStateOf(prefs.getString(KEY_FIREBASE_URL, "") ?: "") }
    var deviceId     by remember { mutableStateOf(prefs.getString(KEY_DEVICE_ID, Build.MODEL) ?: Build.MODEL) }
    var geminiKey    by remember { mutableStateOf(prefs.getString(KEY_GEMINI_KEY, "") ?: "") }
    var desktopHost  by remember { mutableStateOf(prefs.getString(KEY_DESKTOP_HOST, "") ?: "") }
    var geminiKeyVisible by remember { mutableStateOf(false) }

    // ── Scan parameters ────────────────────────────────────────────────────
    var scanIntervalMs by remember {
        mutableStateOf(prefs.getLong(AppPreferences.KEY_SCAN_INTERVAL_MS,
            AppPreferences.DEFAULT_SCAN_INTERVAL_MS).toString())
    }
    var scanTimeoutMs by remember {
        mutableStateOf(prefs.getLong(AppPreferences.KEY_SCAN_TIMEOUT_MS,
            AppPreferences.DEFAULT_SCAN_TIMEOUT_MS).toString())
    }

    // ── Feature toggles ────────────────────────────────────────────────────
    @Composable
    fun featureToggle(key: String, default: Boolean = true) =
        remember { mutableStateOf(featurePrefs.getBoolean(key, default)) }

    var ftEvilTwin       by featureToggle(AppPreferences.KEY_FEATURE_EVIL_TWIN)
    var ftDeauthFlood    by featureToggle(AppPreferences.KEY_FEATURE_DEAUTH_FLOOD)
    var ftPmkid          by featureToggle(AppPreferences.KEY_FEATURE_PMKID)
    var ftKrack          by featureToggle(AppPreferences.KEY_FEATURE_KRACK)
    var ftFirebase       by featureToggle(AppPreferences.KEY_FEATURE_FIREBASE_SYNC)
    var ftGemini         by featureToggle(AppPreferences.KEY_FEATURE_GEMINI_AI)
    var ftCompanion      by featureToggle(AppPreferences.KEY_FEATURE_COMPANION_RELAY)
    var ftNexmon         by featureToggle(AppPreferences.KEY_FEATURE_NEXMON)
    var ftShizuku        by featureToggle(AppPreferences.KEY_FEATURE_SHIZUKU)
    var ftRootScanner    by featureToggle(AppPreferences.KEY_FEATURE_ROOT_SCANNER)
    var ftUsbWifi        by featureToggle(AppPreferences.KEY_FEATURE_USB_WIFI)
    var ftLinuxEnv       by featureToggle(AppPreferences.KEY_FEATURE_LINUX_ENV)
    var ftAutoUpdate     by featureToggle(AppPreferences.KEY_FEATURE_AUTO_UPDATE)

    // ── Terminal preference ────────────────────────────────────────────────
    val availableTerminals = remember { TerminalDetector.detectAll(context) }
    var selectedTerminalId by remember {
        mutableStateOf(prefs.getString(AppPreferences.KEY_PREFERRED_TERMINAL,
            AppPreferences.TERMINAL_TERMUX) ?: AppPreferences.TERMINAL_TERMUX)
    }
    var terminalMenuExpanded by remember { mutableStateOf(false) }

    // ── Linux environment ──────────────────────────────────────────────────
    val linuxEnv = remember { LinuxEnvironmentService.getEnvironment(context) }
    var linuxEnvDistro by remember {
        mutableStateOf(prefs.getString(AppPreferences.KEY_LINUX_ENV_DISTRO,
            AppPreferences.DEFAULT_LINUX_ENV_DISTRO) ?: AppPreferences.DEFAULT_LINUX_ENV_DISTRO)
    }

    // ── Update checker ─────────────────────────────────────────────────────
    var updateResult by remember { mutableStateOf<UpdateChecker.UpdateResult?>(null) }
    var updateChecking by remember { mutableStateOf(false) }

    // ── Snackbar ───────────────────────────────────────────────────────────
    val snackbarHostState = remember { SnackbarHostState() }
    var savedSnackbar by remember { mutableStateOf(false) }
    LaunchedEffect(savedSnackbar) {
        if (savedSnackbar) {
            snackbarHostState.showSnackbar("Settings saved")
            savedSnackbar = false
        }
    }

    fun save() {
        prefs.edit()
            .putString(KEY_FIREBASE_URL, firebaseUrl.trim())
            .putString(KEY_DEVICE_ID, deviceId.trim())
            .putString(KEY_GEMINI_KEY, geminiKey.trim())
            .putString(KEY_DESKTOP_HOST, desktopHost.trim())
            .putLong(AppPreferences.KEY_SCAN_INTERVAL_MS,
                scanIntervalMs.toLongOrNull() ?: AppPreferences.DEFAULT_SCAN_INTERVAL_MS)
            .putLong(AppPreferences.KEY_SCAN_TIMEOUT_MS,
                scanTimeoutMs.toLongOrNull() ?: AppPreferences.DEFAULT_SCAN_TIMEOUT_MS)
            .putString(AppPreferences.KEY_PREFERRED_TERMINAL, selectedTerminalId)
            .putString(AppPreferences.KEY_LINUX_ENV_DISTRO, linuxEnvDistro.trim())
            .apply()
        featurePrefs.edit()
            .putBoolean(AppPreferences.KEY_FEATURE_EVIL_TWIN, ftEvilTwin)
            .putBoolean(AppPreferences.KEY_FEATURE_DEAUTH_FLOOD, ftDeauthFlood)
            .putBoolean(AppPreferences.KEY_FEATURE_PMKID, ftPmkid)
            .putBoolean(AppPreferences.KEY_FEATURE_KRACK, ftKrack)
            .putBoolean(AppPreferences.KEY_FEATURE_FIREBASE_SYNC, ftFirebase)
            .putBoolean(AppPreferences.KEY_FEATURE_GEMINI_AI, ftGemini)
            .putBoolean(AppPreferences.KEY_FEATURE_COMPANION_RELAY, ftCompanion)
            .putBoolean(AppPreferences.KEY_FEATURE_NEXMON, ftNexmon)
            .putBoolean(AppPreferences.KEY_FEATURE_SHIZUKU, ftShizuku)
            .putBoolean(AppPreferences.KEY_FEATURE_ROOT_SCANNER, ftRootScanner)
            .putBoolean(AppPreferences.KEY_FEATURE_USB_WIFI, ftUsbWifi)
            .putBoolean(AppPreferences.KEY_FEATURE_LINUX_ENV, ftLinuxEnv)
            .putBoolean(AppPreferences.KEY_FEATURE_AUTO_UPDATE, ftAutoUpdate)
            .apply()
        savedSnackbar = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                actions = {
                    IconButton(onClick = {
                        // About section shortcut — scroll is handled by user
                    }) {
                        Icon(Icons.Filled.Info, contentDescription = "About")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            // ── Cloud Sync ─────────────────────────────────────────────────
            SectionHeader("Cloud Sync (Firebase)")
            FeatureToggleRow(
                label = "Firebase Sync",
                description = "Sync threat logs and alerts to Firebase Realtime Database. " +
                    "⚠ Disabling stops all cloud sync.",
                checked = ftFirebase,
                onCheckedChange = { ftFirebase = it },
            )
            if (ftFirebase) {
                OutlinedTextField(
                    value = firebaseUrl,
                    onValueChange = { firebaseUrl = it },
                    label = { Text("Firebase Realtime DB URL") },
                    placeholder = { Text("https://your-project.firebaseio.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = deviceId,
                    onValueChange = { deviceId = it },
                    label = { Text("Device ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            HorizontalDivider()

            // ── AI Analysis ────────────────────────────────────────────────
            SectionHeader("AI Analysis (Gemini)")
            FeatureToggleRow(
                label = "Gemini AI",
                description = "Send scan context to Gemini API for attack analysis.",
                checked = ftGemini,
                onCheckedChange = { ftGemini = it },
            )
            if (ftGemini) {
                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Gemini API Key") },
                    placeholder = { Text("AIza…") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (geminiKeyVisible) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { geminiKeyVisible = !geminiKeyVisible }) {
                            Icon(
                                imageVector = if (geminiKeyVisible) Icons.Filled.VisibilityOff
                                              else Icons.Filled.Visibility,
                                contentDescription = if (geminiKeyVisible) "Hide" else "Show",
                            )
                        }
                    },
                )
            }

            HorizontalDivider()

            // ── Companion Desktop ──────────────────────────────────────────
            SectionHeader("Companion Desktop App")
            FeatureToggleRow(
                label = "Companion Relay",
                description = "Push scan results to the Windows wscan+ instance over LAN or USB.",
                checked = ftCompanion,
                onCheckedChange = { ftCompanion = it },
            )
            if (ftCompanion) {
                OutlinedTextField(
                    value = desktopHost,
                    onValueChange = { desktopHost = it },
                    label = { Text("Desktop Host (IP:Port)") },
                    placeholder = { Text("192.168.1.100:9000") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }

            HorizontalDivider()

            // ── Scan Parameters ────────────────────────────────────────────
            SectionHeader("Scan Parameters")
            OutlinedTextField(
                value = scanIntervalMs,
                onValueChange = { scanIntervalMs = it },
                label = { Text("Scan interval (ms)") },
                supportingText = { Text("Time between scan cycles. Default: 5000 ms") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = scanTimeoutMs,
                onValueChange = { scanTimeoutMs = it },
                label = { Text("Scan timeout (ms)") },
                supportingText = { Text("Max duration for a single scan attempt. Default: 8000 ms") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            HorizontalDivider()

            // ── Detection Modules ──────────────────────────────────────────
            SectionHeader("Detection Modules")
            Text(
                "⚠ Disabling a detection module prevents wscan+ from detecting that " +
                    "attack class. Only disable if you are certain it is not needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            FeatureToggleRow("Evil Twin / Karma Detection", null, ftEvilTwin) { ftEvilTwin = it }
            FeatureToggleRow("Deauthentication Flood Detection", null, ftDeauthFlood) { ftDeauthFlood = it }
            FeatureToggleRow("PMKID Sniffing Detection", null, ftPmkid) { ftPmkid = it }
            FeatureToggleRow("KRACK Detection", null, ftKrack) { ftKrack = it }

            HorizontalDivider()

            // ── Scanner Backends ───────────────────────────────────────────
            SectionHeader("Scanner Backends")
            Text(
                "⚠ Disabling a scanner backend removes it from the priority list. " +
                    "The next available backend will be used automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            FeatureToggleRow("USB OTG Wi-Fi Adapter", "Priority 0 — external adapter via OTG", ftUsbWifi) { ftUsbWifi = it }
            FeatureToggleRow("Nexmon Monitor Mode", "Priority 1 — Broadcom chips (Nexus 6P, Galaxy S7/S8)", ftNexmon) { ftNexmon = it }
            FeatureToggleRow("Shizuku Privileged Scan", "Priority 2 — bypass OS throttling", ftShizuku) { ftShizuku = it }
            FeatureToggleRow("Root Scanner (airmon-ng / iw)", "Priority 3 — rooted devices", ftRootScanner) { ftRootScanner = it }

            HorizontalDivider()

            // ── Terminal Preference ────────────────────────────────────────
            SectionHeader("Terminal Preference")
            if (availableTerminals.none { it.isAvailable }) {
                Text(
                    "No terminal app detected. Install Termux for best results.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    "Multiple terminal environments detected. Select your preferred one.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ExposedDropdownMenuBox(
                    expanded = terminalMenuExpanded,
                    onExpandedChange = { terminalMenuExpanded = !terminalMenuExpanded },
                ) {
                    OutlinedTextField(
                        value = availableTerminals
                            .firstOrNull { it.id == selectedTerminalId }
                            ?.displayName ?: selectedTerminalId,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Preferred Terminal") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(terminalMenuExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = terminalMenuExpanded,
                        onDismissRequest = { terminalMenuExpanded = false },
                    ) {
                        availableTerminals.filter { it.isAvailable }.forEach { terminal ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(terminal.displayName,
                                            style = MaterialTheme.typography.bodyMedium)
                                        Text(terminal.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    selectedTerminalId = terminal.id
                                    terminalMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── Android Linux Environment ──────────────────────────────────
            SectionHeader("Android Linux Environment")
            val linuxLabel = when (linuxEnv) {
                LinuxEnvironmentService.LinuxEnvironment.ANDROID_LINUX_VM ->
                    "✅ Pixel AVF / Android Linux VM detected"
                LinuxEnvironmentService.LinuxEnvironment.TERMUX ->
                    "✅ Termux available (fallback Linux environment)"
                LinuxEnvironmentService.LinuxEnvironment.NONE ->
                    "⚠ No Linux environment found. Enable 'Linux terminal' in Settings → Developer options or install Termux."
            }
            Text(linuxLabel, style = MaterialTheme.typography.bodySmall)
            FeatureToggleRow(
                label = "Enable Linux Environment Features",
                description = "Allow wscan+ to run commands in the Linux environment " +
                    "(aircrack-ng, iw, tcpdump setup).",
                checked = ftLinuxEnv,
                onCheckedChange = { ftLinuxEnv = it },
            )
            if (ftLinuxEnv && linuxEnv != LinuxEnvironmentService.LinuxEnvironment.NONE) {
                OutlinedTextField(
                    value = linuxEnvDistro,
                    onValueChange = { linuxEnvDistro = it },
                    label = { Text("Distro hint") },
                    supportingText = { Text("e.g. debian, ubuntu (informational only)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            HorizontalDivider()

            // ── About & Update ─────────────────────────────────────────────
            SectionHeader("About & Updates")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("wscan+", style = MaterialTheme.typography.titleMedium)
                    Text("Version: ${UpdateChecker.CURRENT_VERSION}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Platform: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Device: ${Build.MANUFACTURER} ${Build.MODEL}",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Wi-Fi IDS scanner with Evil Twin, Deauth Flood, PMKID and KRACK detection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            FeatureToggleRow(
                label = "Auto-check for updates",
                description = "Check GitHub releases on app launch (requires internet).",
                checked = ftAutoUpdate,
                onCheckedChange = { ftAutoUpdate = it },
            )

            val isOnline = SystemStatusChecker.hasInternetConnectivity(context)
            if (!isOnline) {
                Text("📴 Device is offline — update check unavailable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    if (!isOnline) return@Button
                    updateChecking = true
                    scope.launch {
                        updateResult = UpdateChecker.check(context)
                        updateChecking = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = isOnline && !updateChecking,
            ) {
                if (updateChecking)
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else
                    Text("Check for Updates")
            }

            updateResult?.let { result ->
                val (msg, color) = when (result.status) {
                    UpdateChecker.UpdateStatus.OFFLINE ->
                        "Offline — cannot check for updates." to MaterialTheme.colorScheme.error
                    UpdateChecker.UpdateStatus.ERROR ->
                        "Error: ${result.releaseNotes}" to MaterialTheme.colorScheme.error
                    UpdateChecker.UpdateStatus.UP_TO_DATE ->
                        "✅ You are on the latest version (${result.currentVersion})." to
                            MaterialTheme.colorScheme.primary
                    UpdateChecker.UpdateStatus.UPDATE_AVAILABLE_UNCONFIRMED ->
                        "⚠ v${result.latestVersion} is available but not yet " +
                        "build-confirmed by CI. Check back later." to
                            MaterialTheme.colorScheme.tertiary
                    UpdateChecker.UpdateStatus.UPDATE_AVAILABLE ->
                        "🚀 v${result.latestVersion} is available!" to
                            MaterialTheme.colorScheme.primary
                }
                Text(msg, style = MaterialTheme.typography.bodySmall, color = color)

                if (result.status == UpdateChecker.UpdateStatus.UPDATE_AVAILABLE &&
                    result.releaseUrl != null) {
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.releaseUrl))
                        context.startActivity(intent)
                    }) {
                        Text("Open release page →")
                    }
                }
            }

            HorizontalDivider()

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { save() },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("save_settings_button"),
            ) {
                Text("Save Settings")
            }
        }
    }
}

// ── Reusable composables ──────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun FeatureToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ── Preference extension properties (backwards-compat for other screens) ─────

fun Context.getWscanPref(key: String, default: String = ""): String =
    getSharedPreferences(AppPreferences.PREFS_MAIN, Context.MODE_PRIVATE)
        .getString(key, default) ?: default

val Context.geminiApiKey: String   get() = getWscanPref(KEY_GEMINI_KEY)
val Context.firebaseDatabaseUrl: String get() = getWscanPref(KEY_FIREBASE_URL)
val Context.wscanDeviceId: String  get() = getWscanPref(KEY_DEVICE_ID, android.os.Build.MODEL)
val Context.desktopHost: String    get() = getWscanPref(KEY_DESKTOP_HOST)
