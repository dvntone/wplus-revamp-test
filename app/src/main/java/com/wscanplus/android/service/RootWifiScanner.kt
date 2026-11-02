package com.wscanplus.android.service

import android.net.wifi.ScanResult
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * RootWifiScanner – Wi-Fi scanning and monitor-mode capture for rooted devices.
 *
 * ## Capture strategy (in priority order)
 *
 * 1. **airmon-ng + airodump-ng via Termux/NetHunter**
 *    When `airmon-ng` is available (Termux with aircrack-ng, or Kali NetHunter),
 *    this is the most capable path:
 *    - `airmon-ng start wlan0` → puts adapter in monitor mode (wlan0mon)
 *    - `airodump-ng wlan0mon` → channel-hopping passive scan; captures all
 *      visible BSSIDs, SSIDs, clients, and WPA2 handshakes.
 *    - airodump-ng output is parsed for Evil Twin / Deauth detection.
 *
 * 2. **iw dev scan** – uses the kernel `iw` utility (available via root + `iw`
 *    package) to trigger a scan and parse the output.
 *
 * 3. **tcpdump capture** – raw packet capture on wlan0 or wlan0mon for
 *    PCAP export and subsequent analysis.
 *
 * ## bcmon vs. Nexmon vs. iw
 * - **bcmon** is a legacy tool (2011–2013) for Broadcom BCM4329/BCM4330 on ARM32.
 *   It is deprecated and unsupported on any device manufactured after ~2013.
 * - **Nexmon** is the modern successor; see [NexmonScanner] for device support.
 *   Use [NexmonScanner] instead of [RootWifiScanner] when Nexmon is available.
 * - **iw** + **airmon-ng** work on any device where the kernel driver supports
 *   the SIOCDEVPRIVATE ioctl for mode switching (often the case on Qualcomm chips
 *   with custom kernels).
 *
 * ## References
 * - iw: https://wireless.wiki.kernel.org/en/users/documentation/iw
 * - aircrack-ng suite: https://www.aircrack-ng.org/
 * - Termux aircrack-ng: https://wiki.termux.com/wiki/Aircrack-ng
 */
class RootWifiScanner : WifiScanner {

    override suspend fun scan(): List<ScanResult> = withContext(Dispatchers.IO) {
        // Prefer airmon-ng/airodump-ng when available in Termux
        val airmonPath = findAirmon()
        if (airmonPath != null) {
            Log.i(TAG, "airmon-ng found at $airmonPath – using airodump-ng scan")
            return@withContext scanWithAirodump(airmonPath)
        }

        // Fallback to iw dev scan
        return@withContext scanWithIw()
    }

    // ── airmon-ng + airodump-ng ───────────────────────────────────────────────

    /**
     * Enable monitor mode via airmon-ng and run a short airodump-ng passive scan.
     * Returns empty (airodump entries do not map 1:1 to ScanResult); use
     * [parseAirodumpCsv] to retrieve [AirodumpEntry] records for heuristics.
     *
     * Shell metacharacters (`&`, `&&`) are passed via `sh -c '...'` so they are
     * correctly interpreted by the shell rather than exec'd literally.
     */
    private suspend fun scanWithAirodump(airmonPath: String): List<ScanResult> {
        val csvPrefix = "/data/local/tmp/wscanplus_airodump"
        return try {
            // 1. Enable monitor mode
            exec("su", "-c", "$airmonPath start wlan0")
            delay(1_000)

            // 2. Run airodump-ng for 10 s then kill it. Wrap in `sh -c '...'` so
            //    the `&` and `&&` shell operators are interpreted correctly.
            val airodumpProc = Runtime.getRuntime().exec(
                arrayOf("su", "-c",
                    "sh -c 'airodump-ng wlan0mon --write $csvPrefix " +
                    "--output-format csv -a & sleep 10 && pkill airodump-ng'")
            )
            // Consume stderr on a daemon thread (same pattern as exec()) to prevent
            // the pipe-buffer from blocking the child process while we waitFor().
            val errThread = Thread {
                airodumpProc.errorStream.use { it.bufferedReader().readText() }
            }.also { it.isDaemon = true; it.start() }
            val exited = airodumpProc.waitFor(15, TimeUnit.SECONDS)
            errThread.join(500)
            if (!exited) {
                Log.w(TAG, "airodump scan timed out; killing process")
                airodumpProc.destroy()
            }

            // 3. Parse the airodump CSV for Evil Twin analysis
            val entries = parseAirodumpCsv("${csvPrefix}-01.csv")
            Log.i(TAG, "airodump parsed ${entries.size} AP entries")

            // 4. Restore managed mode
            exec("su", "-c", "airmon-ng stop wlan0mon")
            entries.map { entry ->
                ScanResultCompat.create(
                    bssid = entry.bssid,
                    ssid = entry.ssid,
                    level = entry.power,
                    frequency = if (entry.channel <= 14) 2407 + entry.channel * 5 else 5000 + entry.channel * 5,
                    capabilities = entry.privacy
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "airodump scan failed", e)
            emptyList()
        }
    }

    /**
     * Parse an airodump-ng CSV file and return AP records.
     *
     * CSV format (AP section):
     *   BSSID, First time seen, Last time seen, Channel, Speed, Privacy,
     *   Cipher, Authentication, Power, Beacons, IVs, LAN IP, ID-length, ESSID, Key
     */
    fun parseAirodumpCsv(path: String): List<AirodumpEntry> {
        val entries = mutableListOf<AirodumpEntry>()
        val file = File(path)
        if (!file.exists()) return entries
        try {
            var inApSection = false
            file.forEachLine { line ->
                if (line.startsWith("BSSID,")) { inApSection = true; return@forEachLine }
                if (line.isBlank()) { inApSection = false; return@forEachLine }
                if (!inApSection) return@forEachLine

                val parts = line.split(",")
                if (parts.size < 14) return@forEachLine
                entries.add(
                    AirodumpEntry(
                        bssid   = parts[0].trim(),
                        channel = parts[3].trim().toIntOrNull() ?: 0,
                        privacy = parts[5].trim(),
                        power   = parts[8].trim().toIntOrNull() ?: 0,
                        ssid    = parts[13].trim(),
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "airodump CSV parse failed", e)
        }
        return entries
    }

    // ── iw dev scan ──────────────────────────────────────────────────────────

    /**
     * Trigger a scan via `iw dev wlan0 scan` and parse key fields.
     *
     * `iw scan` output example (abbreviated):
     * ```
     * BSS aa:bb:cc:dd:ee:ff (on wlan0)
     *   SSID: MyNetwork
     *   signal: -68.00 dBm
     *   freq: 2437
     * ```
     */
    private fun scanWithIw(): List<ScanResult> {
        return try {
            val output = exec("su", "-c", "iw dev wlan0 scan")
            Log.d(TAG, "iw scan output: ${output.length} chars")
            val entries = parseIwScanEntries(output)
            Log.d(TAG, "iw scan parsed ${entries.size} APs")
            entries.map { entry ->
                ScanResultCompat.create(
                    bssid = entry.bssid,
                    ssid = entry.ssid,
                    level = entry.signal,
                    frequency = entry.freq,
                    capabilities = "[WPA2-PSK-CCMP][ESS]"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "iw scan failed", e)
            emptyList()
        }
    }

    /**
     * Parse `iw dev scan` output into [IwScanEntry] records.
     *
     * Signal parsing handles the `-68.00 dBm` format produced by `iw`; we
     * extract the integer part before the decimal point and before the unit.
     */
    fun parseIwScanEntries(output: String): List<IwScanEntry> {
        val entries = mutableListOf<IwScanEntry>()
        var bssid = ""; var ssid = ""; var signal = 0; var freq = 0
        for (line in output.lines()) {
            val t = line.trim()
            when {
                t.startsWith("BSS ") -> {
                    if (bssid.isNotBlank()) entries.add(IwScanEntry(bssid, ssid, signal, freq))
                    bssid = t.removePrefix("BSS ").substringBefore(" (").trim()
                    ssid = ""; signal = 0; freq = 0
                }
                t.startsWith("SSID:") -> ssid = t.removePrefix("SSID:").trim()
                t.startsWith("signal:") -> {
                    // Format: "-68.00 dBm" → parse integer part only
                    signal = t.removePrefix("signal:").trim()
                        .substringBefore(" ")   // drop " dBm"
                        .substringBefore(".")   // drop decimal fraction
                        .toIntOrNull() ?: 0
                }
                t.startsWith("freq:") -> freq = t.removePrefix("freq:").trim().toIntOrNull() ?: 0
            }
        }
        if (bssid.isNotBlank()) entries.add(IwScanEntry(bssid, ssid, signal, freq))
        return entries
    }

    // ── tcpdump capture ───────────────────────────────────────────────────────

    /**
     * Starts a background tcpdump packet capture.
     *
     * @param interface_ Capture interface. Use `wlan0mon` for monitor mode or
     *                   `wlan0` for managed mode (device traffic only).
     * @param outputPath Absolute path on device for the .pcap output file.
     * @return The running [Process], or null on failure.
     */
    fun startTcpdumpCapture(
        interface_: String = "wlan0mon",
        outputPath: String = "/sdcard/wscanplus_capture.pcap",
    ): Process? {
        return try {
            Runtime.getRuntime().exec(
                arrayOf("su", "-c", "tcpdump -i $interface_ -U -w $outputPath")
            )
        } catch (e: Exception) {
            Log.e(TAG, "tcpdump launch failed", e)
            null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Execute a shell command and return its stdout.
     *
     * - Both stdout and stderr are consumed to prevent the child process from
     *   blocking on a full pipe buffer.
     * - A 30-second timeout guards against hung processes.
     * - The process is always destroyed to release OS resources.
     */
    private fun exec(vararg cmd: String): String {
        val proc = Runtime.getRuntime().exec(cmd)
        return try {
            // Consume stderr concurrently on a separate thread to prevent deadlock
            // when both stdout and stderr pipes fill before we read either.
            val stderrThread = Thread { proc.errorStream.use { it.bufferedReader().readText() } }
                .also { it.isDaemon = true; it.start() }
            val out = proc.inputStream.use { it.bufferedReader().readText() }
            val exited = proc.waitFor(30, TimeUnit.SECONDS)
            stderrThread.join(500)
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
        private const val TAG = "RootWifiScanner"

        private val AIRMON_PATHS = listOf(
            "/data/data/com.termux/files/usr/bin/airmon-ng",
            "/system/bin/airmon-ng",
            "/usr/bin/airmon-ng",
        )

        /** Find airmon-ng in Termux or system paths. */
        fun findAirmon(): String? = AIRMON_PATHS.firstOrNull { File(it).exists() }

        /** Heuristic check: su binary exists in standard locations. */
        fun isRooted(): Boolean = listOf(
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su",
        ).any { File(it).exists() }
    }

    /** Parsed entry from `iw dev scan` output. */
    data class IwScanEntry(val bssid: String, val ssid: String, val signal: Int, val freq: Int)

    /** Parsed entry from an airodump-ng CSV file. */
    data class AirodumpEntry(
        val bssid: String,
        val channel: Int,
        val privacy: String,
        val power: Int,
        val ssid: String,
    )
}
