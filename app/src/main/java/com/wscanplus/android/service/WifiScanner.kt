package com.wscanplus.android.service

import android.net.wifi.ScanResult

/** Common interface for all Wi-Fi scanner backends. */
interface WifiScanner {
    suspend fun scan(): List<ScanResult>
}

/**
 * Compatibility builder to construct standard [ScanResult] instances from
 * raw Linux terminal/root/USB iw/airodump scans.
 */
object ScanResultCompat {
    fun create(
        bssid: String,
        ssid: String,
        level: Int,
        frequency: Int,
        capabilities: String = "[WPA2-PSK-CCMP][ESS]"
    ): ScanResult {
        return try {
            val sr = ScanResult::class.java.getDeclaredConstructor().newInstance()
            sr.BSSID = bssid
            sr.SSID = ssid
            sr.level = level
            sr.frequency = frequency
            sr.capabilities = capabilities
            sr
        } catch (_: Exception) {
            ScanResult().apply {
                BSSID = bssid
                SSID = ssid
                this.level = level
                this.frequency = frequency
                this.capabilities = capabilities
            }
        }
    }
}
