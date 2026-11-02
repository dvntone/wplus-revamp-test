package com.wscanplus.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wscanplus.android.MainActivity
import com.wscanplus.android.WscanPlusApplication
import com.wscanplus.android.core.AttackHeuristics
import com.wscanplus.android.core.CompanionClient
import com.wscanplus.android.core.OuiLookup
import com.wscanplus.android.core.SystemStatusChecker
import com.wscanplus.android.data.db.ThreatLog
import com.wscanplus.android.ui.screens.desktopHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * WifiScanningService – foreground service that drives the IDS scan loop.
 *
 * ## Scanner priority order
 *  0. [UsbWifiScanner]    – external USB adapter via OTG (best coverage)
 *  1. [NexmonScanner]     – Broadcom monitor mode (Nexus 6P, Galaxy S7/S8/S10 Exynos)
 *  2. [ShizukuWifiScanner]– bypass OS throttling via ADB shell (no root needed)
 *  3. [RootWifiScanner]   – airmon-ng / iw dev on rooted devices
 *  4. [StandardWifiScanner]– throttled OS scan (4/2 min on Android 9+)
 *
 * ## Watchdog integration
 * The scan loop writes a heartbeat timestamp to [WatchdogService.PREFS_NAME] every
 * cycle. [WatchdogService] checks this timestamp and restarts this service if it
 * becomes stale (> 90 s old).
 *
 * ## Scanner backend cycling
 * After [MAX_CONSECUTIVE_EMPTY] consecutive empty scan results the service
 * automatically selects the next best available backend and notifies
 * [WatchdogService] via [WatchdogService.ACTION_SCANNER_STALLED].
 */
class WifiScanningService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val heuristics = AttackHeuristics()
    private lateinit var watchdogPrefs: SharedPreferences

    private val wifiManager: WifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    private val db by lazy { (application as WscanPlusApplication).database }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        watchdogPrefs = getSharedPreferences(WatchdogService.PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        watchdogPrefs.edit().putBoolean(WatchdogService.KEY_SCANNING_ACTIVE, true).apply()
        ScanCoordinator.getInstance().setContinuousMonitoringActive(true)
        scope.launch { runScanLoop() }
        checkAndNotifyScanThrottle()
        return START_STICKY
    }

    override fun onDestroy() {
        activeInstance = null
        ScanCoordinator.getInstance().setContinuousMonitoringActive(false)
        watchdogPrefs.edit()
            .putBoolean(WatchdogService.KEY_SCANNING_ACTIVE, false)
            .remove(WatchdogService.KEY_SCANNING_HEARTBEAT)
            .apply()
        scope.cancel()
        heuristics.reset()
        super.onDestroy()
    }

    fun handleDeauthFrame(frame: AttackHeuristics.DeauthFrame) {
        val threat = heuristics.analyzeDeauthFrame(frame)
        if (threat != null) {
            scope.launch { persistThreats(listOf(threat)) }
        }
    }

    fun handleEapolFrame(frame: AttackHeuristics.EapolFrame) {
        val threats = heuristics.analyzeEapolFrame(frame)
        if (threats.isNotEmpty()) {
            scope.launch { persistThreats(threats) }
        }
    }

    // ── Scan loop ─────────────────────────────────────────────────────────────

    private suspend fun runScanLoop() {
        var scanner = selectScanner()
        var consecutiveEmpty = 0
        val coordinator = ScanCoordinator.getInstance()
        Log.i(TAG, "Starting scan loop with ${scanner.javaClass.simpleName} via ScanCoordinator")

        while (scope.isActive) {
            // Write watchdog heartbeat before each scan
            watchdogPrefs.edit()
                .putLong(WatchdogService.KEY_SCANNING_HEARTBEAT, System.currentTimeMillis())
                .apply()

            try {
                val cycle = coordinator.executeContinuousScan(applicationContext, scanner)

                if (cycle.apsFound == 0 && !cycle.success) {
                    consecutiveEmpty++
                    if (consecutiveEmpty >= MAX_CONSECUTIVE_EMPTY) {
                        Log.w(TAG, "${scanner.javaClass.simpleName} produced $consecutiveEmpty " +
                            "consecutive empty results; cycling to next scanner")
                        notifyWatchdogScannerStalled(scanner.javaClass.simpleName)
                        scanner = selectScanner(excluded = scanner.javaClass)
                        consecutiveEmpty = 0
                        Log.i(TAG, "Switched to ${scanner.javaClass.simpleName}")
                    }
                } else {
                    consecutiveEmpty = 0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Scan error", e)
            }
            coordinator.delayWithInterrupt(SCAN_INTERVAL_MS)
        }
    }

    /**
     * Select the best available scanner backend.
     * @param excluded Class to skip (used when cycling away from a stalled backend).
     */
    private fun selectScanner(excluded: Class<*>? = null): WifiScanner {
        val usbIface = watchdogPrefs.getString(WatchdogService.KEY_USB_WIFI_INTERFACE, null)

        // 0. USB Wi-Fi adapter via OTG
        if (excluded != UsbWifiScanner::class.java) {
            val hasUsbIface = usbIface != null || UsbWifiScanner.isAvailable()
            if (hasUsbIface) {
                Log.i(TAG, "USB Wi-Fi adapter available – using UsbWifiScanner")
                return UsbWifiScanner(usbIface ?: "")
            }
        }
        // 1. Nexmon monitor mode
        if (excluded != NexmonScanner::class.java && NexmonScanner.isAvailable()) {
            Log.i(TAG, "Nexmon firmware detected – using NexmonScanner")
            return NexmonScanner(wifiManager)
        }
        // 2. Shizuku privileged scan
        if (excluded != ShizukuWifiScanner::class.java && ShizukuWifiScanner.isAvailable()) {
            Log.i(TAG, "Shizuku available – using ShizukuWifiScanner")
            return ShizukuWifiScanner(applicationContext)
        }
        // 3. Root + airmon-ng / iw
        if (excluded != RootWifiScanner::class.java && RootWifiScanner.isRooted()) {
            Log.i(TAG, "Root detected – using RootWifiScanner")
            return RootWifiScanner()
        }
        // 4. Standard WifiManager (throttled)
        Log.i(TAG, "Using StandardWifiScanner (throttled)")
        return StandardWifiScanner(wifiManager, applicationContext)
    }

    private fun notifyWatchdogScannerStalled(scannerName: String) {
        try {
            startService(
                Intent(this, WatchdogService::class.java).apply {
                    action = WatchdogService.ACTION_SCANNER_STALLED
                    putExtra(WatchdogService.EXTRA_SCANNER_NAME, scannerName)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not notify WatchdogService: ${e.message}")
        }
    }

    private suspend fun persistThreats(threats: List<AttackHeuristics.ThreatEvent>) {
        for (threat in threats) {
            db.threatLogDao().insert(
                ThreatLog(
                    timestamp  = threat.timestampMs,
                    attackType = threat.attackType.displayName,
                    bssid      = threat.bssid,
                    ssid       = threat.ssid,
                    description = threat.description,
                    severity   = threat.severity.name,
                )
            )
        }
    }

    // ── Scan-throttle tap-to-fix notification ─────────────────────────────────

    /**
     * If Wi-Fi scan throttling is enabled, post a tap-to-fix notification that
     * navigates directly to Developer Options (or Device Info to enable dev mode).
     */
    private fun checkAndNotifyScanThrottle() {
        if (SystemStatusChecker.isScanThrottleDisabled(this)) return

        val devModeEnabled = SystemStatusChecker.isDeveloperModeEnabled(this)
        val actionIntent = if (devModeEnabled)
            android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        else
            android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(actionIntent),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID_THROTTLE,
            "Scan Throttle Warning",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_THROTTLE)
            .setContentTitle("Wi-Fi scan throttling is ON")
            .setContentText(
                if (devModeEnabled)
                    "Tap to open Developer Options and disable Wi-Fi scan throttling."
                else
                    "Tap to open About Phone — tap Build Number 7× to enable Developer Mode."
            )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_manage,
                if (devModeEnabled) "Developer Options" else "About Phone",
                pendingIntent,
            )
            .build()

        nm.notify(NOTIFICATION_ID_THROTTLE, notification)
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wi-Fi IDS Scanning",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "wscan+ is actively monitoring for Wi-Fi attacks" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("wscan+ IDS Active")
            .setContentText("Monitoring for Wi-Fi attacks…")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG                   = "WifiScanningService"
        private const val NOTIFICATION_ID       = 1001
        private const val NOTIFICATION_ID_THROTTLE = 1002
        private const val CHANNEL_ID            = "wscanplus_ids_channel"
        private const val CHANNEL_ID_THROTTLE   = "wscanplus_throttle_warn"
        private const val SCAN_INTERVAL_MS      = 5_000L
        private const val MAX_CONSECUTIVE_EMPTY = 3

        @Volatile
        private var activeInstance: WifiScanningService? = null

        /** Ingest an 802.11 Deauth or Disassociation frame into the active IDS engine */
        fun ingestDeauthFrame(frame: AttackHeuristics.DeauthFrame) {
            activeInstance?.handleDeauthFrame(frame)
        }

        /** Ingest an EAPOL handshake frame into the active IDS engine */
        fun ingestEapolFrame(frame: AttackHeuristics.EapolFrame) {
            activeInstance?.handleEapolFrame(frame)
        }
    }
}
