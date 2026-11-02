package com.wscanplus.android.service

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import android.net.wifi.ScanResult

/**
 * UsbWifiScanner – Wi-Fi scanner backend for external USB Wi-Fi adapters
 * connected via Android OTG.
 *
 * ## How it works
 * When a supported USB Wi-Fi adapter is connected via OTG and the Android
 * kernel has the appropriate driver loaded (ath9k_htc for AR9271, rtl8188eu,
 * mt7601u, etc.), the adapter appears as a new wireless network interface —
 * typically `wlan1`, `wlan2`, etc.
 *
 * This scanner targets that interface. With root access it brings the interface
 * up via `ip link set <iface> up` and runs `iw dev <iface> scan`. Without root
 * the interface may still work in managed mode depending on the kernel config.
 *
 * ## Interface auto-detection
 * If [iface] is left blank, [UsbWifiAdapterReceiver.findNewWifiInterface] is
 * called to locate the first non-`wlan0` wireless interface in `/sys/class/net/`.
 *
 * ## Prerequisites
 *  1. OTG-capable Android device (USB host mode supported).
 *  2. USB Wi-Fi adapter with a compatible kernel driver — see
 *     [UsbWifiAdapterReceiver] for the supported chipset list.
 *  3. Root access is recommended for bringing up the interface and for
 *     monitor mode support via [RootWifiScanner.startTcpdumpCapture].
 */
class UsbWifiScanner(
    /** Target interface name. Empty string triggers auto-detection. */
    private var iface: String = "",
) : WifiScanner {

    private val rootScanner = RootWifiScanner()

    override suspend fun scan(): List<ScanResult> = withContext(Dispatchers.IO) {
        val target = resolveInterface() ?: run {
            Log.w(TAG, "No USB Wi-Fi interface found – cannot scan. " +
                "Ensure the adapter is connected and the kernel driver is loaded.")
            return@withContext emptyList()
        }
        Log.i(TAG, "USB Wi-Fi scan on interface '$target'")

        // Ensure the interface is up before scanning
        bringInterfaceUp(target)

        val entries = scanWithIw(target)
        Log.d(TAG, "USB Wi-Fi scan on '$target' returned ${entries.size} APs")

        entries.map { entry ->
            ScanResultCompat.create(
                bssid = entry.bssid,
                ssid = entry.ssid,
                level = entry.signal,
                frequency = entry.freq,
                capabilities = "[WPA2-PSK-CCMP][ESS]"
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun resolveInterface(): String? {
        if (iface.isNotBlank() && File("/sys/class/net/$iface").exists()) return iface
        val detected = UsbWifiAdapterReceiver.findNewWifiInterface()
        if (detected != null) iface = detected
        return detected
    }

    /**
     * Bring the interface up with `ip link set <iface> up` (requires root on
     * most Android devices). Non-fatal if it fails — the interface might already
     * be up or the caller may lack root.
     */
    private suspend fun bringInterfaceUp(target: String) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "ip link set $target up"))
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroy()
        } catch (_: Exception) { /* best-effort */ }
    }

    /** Run `iw dev <iface> scan` and parse entries using RootWifiScanner's parser. */
    private suspend fun scanWithIw(target: String): List<RootWifiScanner.IwScanEntry> {
        return try {
            val output = exec("su", "-c", "iw dev $target scan")
            if (output.isBlank()) {
                Log.w(TAG, "Empty iw scan output on $target; retrying after link-up delay")
                delay(800)
                val retryOutput = exec("su", "-c", "iw dev $target scan")
                rootScanner.parseIwScanEntries(retryOutput)
            } else {
                rootScanner.parseIwScanEntries(output)
            }
        } catch (e: Exception) {
            Log.e(TAG, "iw scan failed on $target", e)
            emptyList()
        }
    }

    /**
     * Execute a shell command and return its stdout.
     * stderr is consumed on a daemon thread to prevent pipe-buffer blocking.
     * A 15-second timeout protects against hung processes.
     */
    private fun exec(vararg cmd: String): String {
        val proc = Runtime.getRuntime().exec(cmd)
        return try {
            val errThread = Thread { proc.errorStream.use { it.bufferedReader().readText() } }
                .also { it.isDaemon = true; it.start() }
            val out = proc.inputStream.use { it.bufferedReader().readText() }
            val exited = proc.waitFor(15, TimeUnit.SECONDS)
            errThread.join(300)
            if (!exited) {
                Log.w(TAG, "exec timed out: ${cmd.joinToString(" ")}")
                proc.destroy()
            }
            out
        } finally {
            proc.destroy()
        }
    }

    companion object {
        private const val TAG = "UsbWifiScanner"

        /**
         * Returns true when a USB Wi-Fi interface (any `wlan*` beyond `wlan0`) is
         * present in `/sys/class/net/`.
         */
        fun isAvailable(): Boolean =
            UsbWifiAdapterReceiver.findNewWifiInterface() != null
    }
}
