package com.wscanplus.android.service

import android.content.Context
import android.content.Intent
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import com.wscanplus.android.core.AppPreferences
import com.wscanplus.android.core.AttackHeuristics
import com.wscanplus.android.core.CompanionClient
import com.wscanplus.android.core.EnrichedScanParser
import com.wscanplus.android.core.EnrichedScanResult
import com.wscanplus.android.core.OuiLookup
import com.wscanplus.android.data.db.AppDatabase
import com.wscanplus.android.data.db.ThreatLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ScanExecutionState – represents the granular operational lifecycle of Wi-Fi scans.
 */
enum class ScanExecutionState {
    IDLE,
    QUEUED,
    RUNNING,
    COMPLETED,
    THROTTLED,
    FAILED,
    UNSUPPORTED
}

/**
 * ScanTrigger – distinguishes whether a scan was invoked by background continuous monitoring
 * or a user-initiated Instant Scan.
 */
enum class ScanTrigger {
    CONTINUOUS,
    INSTANT
}

/**
 * LiveSpectrumStats – aggregated real-time spectrum metrics for the live scan pane.
 */
data class LiveSpectrumStats(
    val totalAps: Int = 0,
    val band24Count: Int = 0,
    val band5Count: Int = 0,
    val band6Count: Int = 0,
    val openCount: Int = 0,
    val wpa2Count: Int = 0,
    val wpa3Count: Int = 0,
    val wepCount: Int = 0,
    val maxSignalDbm: Int = -100,
    val avgSignalDbm: Int = -100,
    val threatsCount: Int = 0,
    val channelsInUse: List<Pair<Int, Int>> = emptyList() // Pair<channel, apCount>
)

/**
 * ScanState – synchronized snapshot of the scanner system state across UI and services.
 */
data class ScanState(
    val executionState: ScanExecutionState = ScanExecutionState.IDLE,
    val isContinuousActive: Boolean = false,
    val isInstantScanActive: Boolean = false,
    val lastScanTimestamp: Long = 0L,
    val lastScanResultCount: Int = 0,
    val lastThreatCount: Int = 0,
    val activeBackendName: String = "",
    val statusMessage: String = "Ready",
    val consecutiveFailures: Int = 0,
    val accessPoints: List<EnrichedScanResult> = emptyList(),
    val liveStats: LiveSpectrumStats = LiveSpectrumStats(),
)

/**
 * ScanCoordinator – Single authoritative coordinator for all Wi-Fi scanning operations in wscan+.
 *
 * Ensures:
 * 1. Zero overlapping hardware scans across all scanner backends.
 * 2. Instant Scan requested during continuous monitoring immediately triggers/piggybacks
 *    on the coordinator without spawning a second independent scanning loop.
 * 3. Complete synchronization between continuous monitoring, instant scan button, progress
 *    indicators, and status badges.
 * 4. Resilient handling of Android OS scan throttling and cached results.
 */
class ScanCoordinator(
    private val heuristics: AttackHeuristics = AttackHeuristics(),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val scanMutex = Mutex()

    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    // Used to signal continuous scanning loop when an Instant Scan is requested
    private var instantScanSignal: CompletableDeferred<Unit>? = null

    // Tracks current in-flight scan job so overlapping callers can await the same cycle
    private var currentScanJob: Deferred<ScanCycleResult>? = null

    data class ScanCycleResult(
        val success: Boolean,
        val isThrottled: Boolean,
        val apsFound: Int,
        val threatsFound: Int,
        val error: String? = null
    )

    /**
     * Set continuous monitoring active status.
     */
    fun setContinuousMonitoringActive(active: Boolean) {
        _scanState.value = _scanState.value.copy(
            isContinuousActive = active,
            statusMessage = if (active) "Continuous IDS monitoring active" else "Continuous monitoring stopped"
        )
    }

    /**
     * Request an Instant Scan from UI or background agents.
     * If continuous monitoring is already running, this signals the coordinator to execute
     * an immediate cycle or await the currently running hardware scan.
     */
    suspend fun requestInstantScan(
        context: Context? = null,
        scannerOverride: WifiScanner? = null
    ): ScanCycleResult {
        // Rapid tap protection: if instant scan is already queued or running, join existing job
        val existingJob = currentScanJob
        if (_scanState.value.isInstantScanActive && existingJob != null && existingJob.isActive) {
            Log.d(TAG, "Instant scan already in progress; awaiting active hardware scan cycle")
            return existingJob.await()
        }

        _scanState.value = _scanState.value.copy(
            isInstantScanActive = true,
            executionState = if (scanMutex.isLocked) ScanExecutionState.QUEUED else ScanExecutionState.RUNNING,
            statusMessage = if (scanMutex.isLocked) "Scan request queued (hardware busy)…" else "Performing instant spectrum scan…"
        )

        // If continuous monitoring is running, awake its sleep interval
        instantScanSignal?.complete(Unit)

        val deferred = coroutineScope.async {
            executeScanInternal(context, ScanTrigger.INSTANT, scannerOverride)
        }
        currentScanJob = deferred

        return try {
            val result = deferred.await()
            result
        } finally {
            _scanState.value = _scanState.value.copy(isInstantScanActive = false)
            currentScanJob = null
        }
    }

    /**
     * Execute a continuous monitoring scan cycle. Called exclusively by [WifiScanningService].
     */
    suspend fun executeContinuousScan(
        context: Context? = null,
        scanner: WifiScanner
    ): ScanCycleResult {
        return executeScanInternal(context, ScanTrigger.CONTINUOUS, scanner)
    }

    /**
     * Internal single-flight hardware scan execution.
     */
    private suspend fun executeScanInternal(
        context: Context?,
        trigger: ScanTrigger,
        scannerOverride: WifiScanner?
    ): ScanCycleResult {
        return scanMutex.withLock {
            val backend = scannerOverride ?: if (context != null) {
                resolveScannerBackend(context)
            } else {
                error("No scanner backend available (Context is null)")
            }
            val backendName = backend.javaClass.simpleName

            _scanState.value = _scanState.value.copy(
                executionState = ScanExecutionState.RUNNING,
                activeBackendName = backendName,
                statusMessage = "Scanning spectrum via $backendName…"
            )

            val startTime = System.currentTimeMillis()
            var rawResults: List<ScanResult> = emptyList()
            var isThrottled = false
            var scanError: String? = null

            try {
                rawResults = backend.scan()

                // Check for throttle condition if results are empty and standard scanner was used
                if (backend is StandardWifiScanner && rawResults.isEmpty()) {
                    isThrottled = true
                }
            } catch (e: CancellationException) {
                _scanState.value = _scanState.value.copy(
                    executionState = ScanExecutionState.IDLE,
                    statusMessage = "Scan cancelled"
                )
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Hardware scan failed on backend $backendName", e)
                scanError = e.message ?: "Unknown hardware error"
            }

            if (scanError != null) {
                val newFailures = _scanState.value.consecutiveFailures + 1
                _scanState.value = _scanState.value.copy(
                    executionState = ScanExecutionState.FAILED,
                    consecutiveFailures = newFailures,
                    statusMessage = "Scan failed ($backendName): $scanError"
                )
                return@withLock ScanCycleResult(
                    success = false,
                    isThrottled = false,
                    apsFound = 0,
                    threatsFound = 0,
                    error = scanError
                )
            }

            // Parse Access Points
            val aps = rawResults.map {
                AttackHeuristics.AccessPoint(
                    bssid = it.BSSID ?: "",
                    ssid = it.SSID ?: "",
                    rssi = it.level,
                    capabilities = it.capabilities ?: "",
                )
            }

            // Run threat heuristics
            val ouiLookup = context?.let { ctx ->
                runCatching { OuiLookup.getInstance(ctx.applicationContext) }.getOrNull()
            }
            val threats = heuristics.analyzeEvilTwin(aps, ouiLookup)

            // Persist detected threats to Room DB if context is present
            if (threats.isNotEmpty() && context != null) {
                runCatching {
                    val db = AppDatabase.getInstance(context.applicationContext)
                    threats.forEach { t ->
                        db.threatLogDao().insert(
                            ThreatLog(
                                timestamp = t.timestampMs,
                                attackType = t.attackType.displayName,
                                bssid = t.bssid,
                                ssid = t.ssid,
                                description = t.description,
                                severity = t.severity.name
                            )
                        )
                    }
                }
            }

            // Enrich all raw access points for live UI telemetry and inspection
            val threatBssids = threats.map { it.bssid.lowercase() }.toSet()
            val enrichedAps = rawResults.map { raw: ScanResult ->
                val parsed = EnrichedScanParser.parse(raw)
                val vendor = ouiLookup?.lookupVendor(raw.BSSID ?: "")
                val vendors: List<String> = if (vendor != null && !parsed.vendorIdentities.contains(vendor)) {
                    parsed.vendorIdentities + vendor
                } else {
                    parsed.vendorIdentities
                }
                val enriched = if (vendors != parsed.vendorIdentities) parsed.copy(vendorIdentities = vendors) else parsed

                if (threatBssids.contains(enriched.bssid.lowercase())) {
                    val matchingThreat = threats.firstOrNull { it.bssid.equals(enriched.bssid, ignoreCase = true) }
                    val threatDesc = matchingThreat?.description ?: "Identified security threat"
                    val updatedReasons = if (!enriched.riskReasons.contains(threatDesc)) {
                        enriched.riskReasons + threatDesc
                    } else {
                        enriched.riskReasons
                    }
                    enriched.copy(
                        riskScore = maxOf(enriched.riskScore, 85),
                        riskReasons = updatedReasons
                    )
                } else {
                    enriched
                }
            }.sortedWith(
                compareByDescending<EnrichedScanResult> { threatBssids.contains(it.bssid.lowercase()) }
                    .thenByDescending { it.levelDbm }
            )

            // Compute spectrum statistics
            val band24 = enrichedAps.count { it.band == "2.4 GHz" }
            val band5 = enrichedAps.count { it.band == "5 GHz" }
            val band6 = enrichedAps.count { it.band == "6 GHz" }
            val openCount = enrichedAps.count { it.isOpen }
            val wpa2Count = enrichedAps.count { it.isWpa2 }
            val wpa3Count = enrichedAps.count { it.isWpa3 }
            val wepCount = enrichedAps.count { it.isWep }
            val maxSignal = enrichedAps.maxOfOrNull { it.levelDbm } ?: -100
            val avgSignal = if (enrichedAps.isNotEmpty()) enrichedAps.map { it.levelDbm }.average().toInt() else -100
            val topChannels = enrichedAps.filter { it.channel > 0 }
                .groupBy { it.channel }
                .mapValues { it.value.size }
                .toList()
                .sortedByDescending { it.second }
                .take(6)

            val stats = LiveSpectrumStats(
                totalAps = enrichedAps.size,
                band24Count = band24,
                band5Count = band5,
                band6Count = band6,
                openCount = openCount,
                wpa2Count = wpa2Count,
                wpa3Count = wpa3Count,
                wepCount = wepCount,
                maxSignalDbm = maxSignal,
                avgSignalDbm = avgSignal,
                threatsCount = threats.size,
                channelsInUse = topChannels
            )

            val finalState = if (isThrottled) ScanExecutionState.THROTTLED else ScanExecutionState.COMPLETED
            val statusMsg = when {
                isThrottled -> "Scan throttled by Android OS (using cached spectrum)"
                threats.isNotEmpty() -> "Scan completed: ${aps.size} APs, ${threats.size} THREATS DETECTED"
                else -> "AP beacon scan nominal: ${aps.size} APs visible (no beacon anomalies; over-the-air frame analysis requires monitor mode / AVF)"
            }

            // Forward scan results to desktop companion if enabled
            if (context != null && AppPreferences.features(context).getBoolean(AppPreferences.KEY_FEATURE_COMPANION_RELAY, false)) {
                val host = AppPreferences.main(context).getString(AppPreferences.KEY_DESKTOP_HOST, null)
                if (!host.isNullOrBlank()) {
                    coroutineScope.launch {
                        try {
                            val companion = CompanionClient(host)
                            companion.pushScanResults(rawResults)
                        } catch (e: Exception) {
                            Log.w(TAG, "Companion relay push skipped: ${e.message}")
                        }
                    }
                }
            }

            _scanState.value = _scanState.value.copy(
                executionState = finalState,
                lastScanTimestamp = startTime,
                lastScanResultCount = aps.size,
                lastThreatCount = threats.size,
                consecutiveFailures = 0,
                statusMessage = statusMsg,
                accessPoints = enrichedAps,
                liveStats = stats
            )

            ScanCycleResult(
                success = true,
                isThrottled = isThrottled,
                apsFound = aps.size,
                threatsFound = threats.size
            )
        }
    }

    /**
     * Awaits sleep interval or wakes immediately if an instant scan signal was raised.
     */
    suspend fun delayWithInterrupt(durationMs: Long) {
        val signal = CompletableDeferred<Unit>()
        instantScanSignal = signal
        try {
            withTimeoutOrNull(durationMs) {
                signal.await()
                Log.d(TAG, "Continuous scan interval interrupted for Instant Scan request")
            }
        } finally {
            instantScanSignal = null
        }
    }

    /**
     * Resolve default scanner backend based on available platform hardware.
     */
    private fun resolveScannerBackend(context: Context): WifiScanner {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(WatchdogService.PREFS_NAME, Context.MODE_PRIVATE)
        val usbIface = prefs.getString(WatchdogService.KEY_USB_WIFI_INTERFACE, null)

        return when {
            usbIface != null || UsbWifiScanner.isAvailable() -> UsbWifiScanner(usbIface ?: "")
            ShizukuWifiScanner.isAvailable() -> ShizukuWifiScanner(appContext)
            RootWifiScanner.isRooted() -> RootWifiScanner()
            else -> {
                val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                StandardWifiScanner(wm, appContext)
            }
        }
    }

    /**
     * Reset heuristics and state tracking.
     */
    fun reset() {
        heuristics.reset()
        _scanState.value = ScanState()
    }

    companion object {
        private const val TAG = "ScanCoordinator"

        @Volatile
        private var instance: ScanCoordinator? = null

        fun getInstance(): ScanCoordinator {
            return instance ?: synchronized(this) {
                instance ?: ScanCoordinator().also { instance = it }
            }
        }
    }
}
