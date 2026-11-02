package com.wscanplus.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * StandardWifiScanner – fallback scanner using the Android [WifiManager] API.
 *
 * Implements asynchronous [WifiManager.SCAN_RESULTS_AVAILABLE_ACTION] broadcast receiver
 * listening for fresh scan completion, avoiding stale immediate queries.
 * Differentiates fresh results vs stale cache entries using elapsed timestamps.
 */
class StandardWifiScanner(
    private val wifiManager: WifiManager,
    private val context: Context? = null
) : WifiScanner {

    override suspend fun scan(): List<ScanResult> = withContext(Dispatchers.IO) {
        if (!wifiManager.isWifiEnabled) {
            Log.w(TAG, "Wi-Fi is disabled on device; cannot initiate active radio sweep")
        }

        val started = try {
            @Suppress("DEPRECATION")
            wifiManager.startScan()
        } catch (e: Exception) {
            Log.w(TAG, "wifiManager.startScan() exception: ${e.message}")
            false
        }

        if (!started) {
            Log.w(TAG, "startScan() returned false (throttled by OS or Wi-Fi disabled); falling back to cached scanResults")
        }

        // If we have a context and startScan initiated, await the fresh results broadcast asynchronously
        if (context != null && started) {
            val freshScanCompleted = withTimeoutOrNull(3_500L) {
                awaitScanBroadcast(context)
            } ?: false
            if (!freshScanCompleted) {
                Log.d(TAG, "Timed out awaiting SCAN_RESULTS_AVAILABLE_ACTION broadcast; reading current cached scan results")
            }
        }

        val rawResults = try {
            @Suppress("DEPRECATION")
            wifiManager.scanResults ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing scanResults: ${e.message}")
            emptyList()
        }

        // Filter and prioritize fresh results (< 60s old)
        val nowMicros = SystemClock.elapsedRealtime() * 1000L
        val maxAgeMicros = 60_000_000L // 60 seconds

        val sortedResults = rawResults.sortedByDescending { it.level }
        val freshResults = sortedResults.filter { ap ->
            ap.timestamp == 0L || (nowMicros - ap.timestamp) < maxAgeMicros
        }

        if (freshResults.isNotEmpty()) freshResults else sortedResults
    }

    private suspend fun awaitScanBroadcast(ctx: Context): Boolean =
        suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                        val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                        try {
                            ctx.unregisterReceiver(this)
                        } catch (_: Exception) {}
                        if (continuation.isActive) {
                            continuation.resume(updated)
                        }
                    }
                }
            }

            try {
                ContextCompat.registerReceiver(
                    ctx,
                    receiver,
                    IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                    ContextCompat.RECEIVER_EXPORTED
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not register scan receiver: ${e.message}")
                if (continuation.isActive) continuation.resume(false)
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                try {
                    ctx.unregisterReceiver(receiver)
                } catch (_: Exception) {}
            }
        }

    companion object {
        private const val TAG = "StandardWifiScanner"
    }
}
