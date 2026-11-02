package com.wscanplus.android.service

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wscanplus.android.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * WatchdogService – lightweight foreground service that monitors [WifiScanningService]
 * and app-level resources, restarting failed components and alerting the user when
 * automatic recovery is not possible.
 *
 * ## Health checks (every [HEARTBEAT_CHECK_INTERVAL_MS])
 *  1. **Heartbeat** – WifiScanningService writes a timestamp to SharedPreferences each
 *     scan cycle. If the timestamp is older than [HEARTBEAT_STALE_MS], the service is
 *     considered stalled and is restarted.
 *  2. **Memory pressure** – Uses [ActivityManager.MemoryInfo.lowMemory]. When true, a
 *     notification prompts the user to close other apps.
 *  3. **USB Wi-Fi adapter** – [UsbWifiAdapterReceiver] sends intents here when an
 *     adapter is connected or disconnected via OTG; the watchdog restarts
 *     [WifiScanningService] so it picks up the new interface.
 *
 * ## Restart strategy (exponential back-off, max [MAX_RESTART_ATTEMPTS] tries)
 *  Attempt 1 → 1 s delay, Attempt 2 → 2 s, Attempt 3 → 4 s.
 *  After [MAX_RESTART_ATTEMPTS] failures a tap-to-open notification is posted and
 *  automatic recovery stops until the user manually restarts the scan.
 */
class WatchdogService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var prefs: SharedPreferences
    private var restartAttempts = 0
    private var lowMemoryNotified = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannels()
        startForeground(NOTIFICATION_ID_FG, buildForegroundNotification())
        scope.launch { runWatchdogLoop() }
        Log.i(TAG, "WatchdogService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_USB_ADAPTER_CONNECTED -> {
                val iface = intent.getStringExtra(EXTRA_USB_INTERFACE) ?: DEFAULT_USB_IFACE
                onUsbAdapterConnected(iface)
            }
            ACTION_USB_ADAPTER_DISCONNECTED -> onUsbAdapterDisconnected()
            ACTION_SCANNER_STALLED -> onScannerStalled(
                intent.getStringExtra(EXTRA_SCANNER_NAME) ?: "unknown"
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Watchdog loop ─────────────────────────────────────────────────────────

    private suspend fun runWatchdogLoop() {
        while (scope.isActive) {
            delay(HEARTBEAT_CHECK_INTERVAL_MS)
            checkScanningServiceHealth()
            checkMemoryPressure()
        }
    }

    private fun checkScanningServiceHealth() {
        val isActive = prefs.getBoolean(KEY_SCANNING_ACTIVE, false)
        if (!isActive) return // service not expected to be running

        val lastBeat = prefs.getLong(KEY_SCANNING_HEARTBEAT, 0L)
        val ageMs = System.currentTimeMillis() - lastBeat
        if (ageMs > HEARTBEAT_STALE_MS) {
            Log.w(TAG, "WifiScanningService heartbeat stale ($ageMs ms); initiating restart")
            attemptRestartScanningService()
        } else {
            restartAttempts = 0 // heartbeat is healthy; reset back-off
        }
    }

    private fun checkMemoryPressure() {
        val am = getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.lowMemory && !lowMemoryNotified) {
            lowMemoryNotified = true
            Log.w(TAG, "Low memory detected (available: ${info.availMem / 1_048_576} MB)")
            postAlertNotification(
                id          = NOTIFICATION_ID_LOW_MEM,
                title       = "Low memory",
                text        = "wscan+ may be slow or unstable. Close other apps to free memory.",
                actionLabel = "Open App",
                actionIntent = tapToOpenIntent(),
            )
        } else if (!info.lowMemory) {
            lowMemoryNotified = false
        }
    }

    // ── Service restart ───────────────────────────────────────────────────────

    private fun attemptRestartScanningService() {
        if (restartAttempts >= MAX_RESTART_ATTEMPTS) {
            Log.e(TAG, "WifiScanningService failed after $MAX_RESTART_ATTEMPTS attempts")
            postAlertNotification(
                id          = NOTIFICATION_ID_RESTART_FAILED,
                title       = "Scan service stopped",
                text        = "The IDS scanner could not be restarted automatically. " +
                              "Tap to open the app and start it manually.",
                actionLabel = "Open wscan+",
                actionIntent = tapToOpenIntent(),
            )
            return
        }

        val backoffMs = min(1L shl (restartAttempts.coerceAtMost(30)), 30L) * 1_000L
        restartAttempts++
        Log.i(TAG, "Restarting WifiScanningService (attempt $restartAttempts, " +
            "backoff ${backoffMs}ms)")

        scope.launch {
            delay(backoffMs)
            try {
                stopService(Intent(this@WatchdogService, WifiScanningService::class.java))
                delay(500)
                startForegroundService(
                    Intent(this@WatchdogService, WifiScanningService::class.java)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Restart failed", e)
            }
        }
    }

    // ── USB Wi-Fi adapter events ──────────────────────────────────────────────

    private fun onUsbAdapterConnected(iface: String) {
        Log.i(TAG, "USB Wi-Fi adapter connected on interface $iface")
        prefs.edit().putString(KEY_USB_WIFI_INTERFACE, iface).apply()

        postAlertNotification(
            id          = NOTIFICATION_ID_USB,
            title       = "USB Wi-Fi adapter connected",
            text        = "Interface $iface ready. The IDS scanner will use it for " +
                          "enhanced Wi-Fi capture.",
            actionLabel = "Open wscan+",
            actionIntent = tapToOpenIntent(),
        )

        // Restart WifiScanningService so selectScanner() picks up the USB interface
        if (prefs.getBoolean(KEY_SCANNING_ACTIVE, false)) {
            scope.launch {
                stopService(Intent(this@WatchdogService, WifiScanningService::class.java))
                delay(600)
                startForegroundService(
                    Intent(this@WatchdogService, WifiScanningService::class.java)
                )
            }
        }
    }

    private fun onUsbAdapterDisconnected() {
        Log.i(TAG, "USB Wi-Fi adapter disconnected")
        prefs.edit().remove(KEY_USB_WIFI_INTERFACE).apply()

        postAlertNotification(
            id          = NOTIFICATION_ID_USB,
            title       = "USB Wi-Fi adapter disconnected",
            text        = "Falling back to built-in adapter for IDS scanning.",
            actionLabel = null,
            actionIntent = null,
        )

        // Restart to fall back to next available scanner
        if (prefs.getBoolean(KEY_SCANNING_ACTIVE, false)) {
            scope.launch {
                stopService(Intent(this@WatchdogService, WifiScanningService::class.java))
                delay(600)
                startForegroundService(
                    Intent(this@WatchdogService, WifiScanningService::class.java)
                )
            }
        }
    }

    private fun onScannerStalled(scannerName: String) {
        Log.w(TAG, "Scanner stalled: $scannerName")
        postAlertNotification(
            id          = NOTIFICATION_ID_SCANNER_WARN,
            title       = "Scanner backend switched",
            text        = "$scannerName produced no results; wscan+ switched to the next " +
                          "available scanner automatically.",
            actionLabel = null,
            actionIntent = null,
        )
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_FG,
                "Watchdog Monitor",
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = "wscan+ background health monitor (silent)" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_ALERTS,
                "Watchdog Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "wscan+ watchdog alerts requiring user attention" }
        )
    }

    private fun buildForegroundNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID_FG)
            .setContentTitle("wscan+ monitor active")
            .setContentText("Watching scan service health")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun postAlertNotification(
        id: Int,
        title: String,
        text: String,
        actionLabel: String?,
        actionIntent: PendingIntent?,
    ) {
        val nm = getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
        if (actionIntent != null) builder.setContentIntent(actionIntent)
        if (actionLabel != null && actionIntent != null) {
            builder.addAction(android.R.drawable.ic_menu_manage, actionLabel, actionIntent)
        }
        nm.notify(id, builder.build())
    }

    private fun tapToOpenIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

    // ── Public contract ───────────────────────────────────────────────────────

    companion object {
        private const val TAG = "WatchdogService"

        /** SharedPreferences file shared with [WifiScanningService]. */
        const val PREFS_NAME                    = "wscanplus_watchdog"
        const val KEY_SCANNING_HEARTBEAT        = "scanning_heartbeat_ms"
        const val KEY_SCANNING_ACTIVE           = "scanning_active"
        const val KEY_USB_WIFI_INTERFACE        = "usb_wifi_interface"

        /** Intent actions sent to this service. */
        const val ACTION_USB_ADAPTER_CONNECTED    = "com.wscanplus.android.USB_WIFI_CONNECTED"
        const val ACTION_USB_ADAPTER_DISCONNECTED = "com.wscanplus.android.USB_WIFI_DISCONNECTED"
        const val ACTION_SCANNER_STALLED          = "com.wscanplus.android.SCANNER_STALLED"
        const val EXTRA_USB_INTERFACE             = "usb_wifi_iface"
        const val EXTRA_SCANNER_NAME              = "scanner_name"

        private const val DEFAULT_USB_IFACE             = "wlan1"
        private const val HEARTBEAT_CHECK_INTERVAL_MS   = 30_000L
        private const val HEARTBEAT_STALE_MS            = 90_000L
        private const val MAX_RESTART_ATTEMPTS          = 3

        private const val NOTIFICATION_ID_FG            = 2001
        private const val NOTIFICATION_ID_LOW_MEM       = 2002
        private const val NOTIFICATION_ID_RESTART_FAILED = 2003
        private const val NOTIFICATION_ID_USB           = 2004
        private const val NOTIFICATION_ID_SCANNER_WARN  = 2005
        private const val CHANNEL_ID_FG                 = "wscanplus_watchdog"
        private const val CHANNEL_ID_ALERTS             = "wscanplus_watchdog_alerts"
    }
}
