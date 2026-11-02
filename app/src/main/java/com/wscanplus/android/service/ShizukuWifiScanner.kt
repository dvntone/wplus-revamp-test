package com.wscanplus.android.service

import android.content.Context
import android.net.wifi.ScanResult
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * ShizukuWifiScanner – executes `cmd wifi scan-results` via Shizuku to
 * obtain fresh Wi-Fi scan results, bypassing Android's OS-level throttling.
 *
 * Shizuku must be installed and running on the device.  The user must have
 * granted this app the Shizuku permission before calling [scan].
 *
 * Reference: https://github.com/RikkaApps/Shizuku
 */
class ShizukuWifiScanner(private val context: Context) : WifiScanner {

    override suspend fun scan(): List<ScanResult> = withContext(Dispatchers.IO) {
        if (!isPermissionGranted()) {
            Log.w(TAG, "Shizuku permission not granted; falling back")
            return@withContext emptyList()
        }
        return@withContext try {
            try {
                val proc = com.wscanplus.android.core.IntegrationsManager.newShizukuProcess(
                    arrayOf("cmd", "wifi", "start-scan")
                )
                proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.d(TAG, "cmd wifi start-scan attempt: ${e.message}")
            }
            val wm = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wm.scanResults ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku scan failed", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "ShizukuWifiScanner"

        /** Returns true when Shizuku is running and permission has been granted. */
        fun isAvailable(): Boolean = try {
            Shizuku.pingBinder() && isPermissionGranted()
        } catch (e: Exception) {
            false
        }

        private fun isPermissionGranted(): Boolean = try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
}
