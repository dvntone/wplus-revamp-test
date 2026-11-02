package com.wscanplus.android.service

import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * NexmonScanner – enables Wi-Fi monitor mode via [Nexmon](https://github.com/seemoo-lab/nexmon)
 * firmware patches on supported devices.
 *
 * ## What is Nexmon?
 * Nexmon is a C-based firmware patching framework for Broadcom/Cypress Wi-Fi chipsets.
 * It replaces the proprietary Wi-Fi firmware with a patched version that exposes
 * monitor mode and raw frame injection capabilities not available in the stock firmware.
 * It is the modern successor to bcmon (which only supported BCM4329/4330 chips on
 * ARM32 devices). Nexmon supports ARM64 on specific devices.
 *
 * ## Why not bcmon?
 * - bcmon targets legacy Broadcom BCM4329/BCM4330 chips (ARM32 only, ~2011-2013 devices).
 * - It has not been maintained since 2013 and does not support any modern Android device.
 * - Nexmon covers a broader set of Broadcom chips (BCM4339, BCM4358, BCM4375B1) including
 *   ARM64 devices like the Nexus 5, Nexus 6P, and certain Samsung Galaxy S7/S8/S10 models.
 *
 * ## Supported devices (2024)
 * | Device          | Chipset    | ARM64 | Notes                              |
 * |-----------------|------------|-------|------------------------------------|
 * | Nexus 5         | BCM4339    | No    | ARM32                              |
 * | Nexus 6P        | BCM4358    | Yes   | ARM64 supported                    |
 * | Galaxy S7       | BCM4359    | Yes   | Region-dependent (Exynos only)     |
 * | Galaxy S8       | BCM4361    | Yes   | Exynos variant only                |
 * | Galaxy S10      | BCM4375B1  | Yes   | Magisk module available            |
 * | Raspberry Pi 3  | BCM43438   | —     | Not Android                        |
 *
 * Modern Google Pixel phones (Pixel 3+) use Qualcomm or Samsung Wi-Fi chips and
 * are NOT supported by Nexmon. No monitor-mode solution exists for these devices
 * without external USB hardware.
 *
 * ## Prerequisites
 * 1. Root access (`su` binary present).
 * 2. Nexmon firmware flashed for your device's chipset.
 * 3. `nexutil` binary available (typically at `/system/bin/nexutil`
 *    or `/data/local/tmp/nexutil`).
 * 4. libnexmon.so installed (required by nexutil for packet injection).
 *
 * ## Installation reference
 * - https://github.com/seemoo-lab/nexmon
 * - https://book.hacktricks.wiki/en/generic-methodologies-and-resources/pentesting-wifi/enable-nexmon-monitor-and-injection-on-android.html
 *
 * ## Usage in wscan+
 * [NexmonScanner] is selected by [WifiScanningService] before [RootWifiScanner]
 * when [isAvailable] returns true. It enables monitor mode, uses the standard
 * WifiManager to collect scan results (which are then richer due to the patched
 * firmware), and disables monitor mode on stop.
 */
class NexmonScanner(
    private val wifiManager: WifiManager,
) : WifiScanner {

    /**
     * Enable Nexmon monitor mode, trigger a scan, return results, then restore
     * the adapter to managed mode.
     *
     * The monitor mode enable command varies by device; the nexutil flag `-s0x613`
     * enables monitor mode with radiotap headers on most supported chips.
     */
    override suspend fun scan(): List<ScanResult> = withContext(Dispatchers.IO) {
        if (!enableMonitorMode()) {
            Log.w(TAG, "Failed to enable Nexmon monitor mode")
            return@withContext emptyList()
        }
        return@withContext try {
            // Give the firmware time to transition to monitor mode (coroutine-safe)
            delay(300)
            @Suppress("DEPRECATION")
            wifiManager.startScan()
            @Suppress("DEPRECATION")
            wifiManager.scanResults ?: emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Nexmon scan error", e)
            emptyList()
        } finally {
            disableMonitorMode()
        }
    }

    // ── Monitor mode control ──────────────────────────────────────────────────

    /**
     * Enable monitor mode using nexutil.
     * `-s0x613 -i -v2` = enable monitor mode with radiotap header injection.
     */
    private fun enableMonitorMode(): Boolean {
        return try {
            val nexutilPath = findNexutil() ?: return false
            val proc = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "$nexutilPath -s0x613 -i -v2")
            )
            val exited = proc.waitFor(10, TimeUnit.SECONDS)
            if (!exited) {
                Log.w(TAG, "nexutil enableMonitorMode timed out")
                proc.destroy()
                return false
            }
            proc.exitValue() == 0
        } catch (e: Exception) {
            Log.e(TAG, "enableMonitorMode failed", e)
            false
        }
    }

    /**
     * Disable monitor mode and restore normal Wi-Fi operation.
     * `-m0` = disable monitor mode; re-enable Wi-Fi via `svc wifi enable`.
     */
    private fun disableMonitorMode() {
        val nexutilPath = findNexutil() ?: return
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "$nexutilPath -m0")).let { proc ->
                if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroy()
            }
            Runtime.getRuntime().exec(arrayOf("su", "-c", "svc wifi enable")).let { proc ->
                if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "disableMonitorMode failed", e)
        }
    }

    companion object {
        private const val TAG = "NexmonScanner"

        private val NEXUTIL_PATHS = listOf(
            "/system/bin/nexutil",
            "/system/xbin/nexutil",
            "/data/local/tmp/nexutil",
        )

        /** Find nexutil binary on the device. */
        fun findNexutil(): String? = NEXUTIL_PATHS.firstOrNull { File(it).exists() }

        /**
         * Returns true when:
         *   1. The device is rooted.
         *   2. nexutil is present (indicates Nexmon firmware is installed).
         *   3. libnexmon.so is present.
         *
         * This is a heuristic – nexutil presence is the strongest signal.
         * The actual chip/firmware compatibility is determined at runtime.
         */
        fun isAvailable(): Boolean {
            if (!isRooted()) return false
            if (findNexutil() == null) return false
            val libPresent = listOf(
                "/system/lib/libnexmon.so",
                "/system/lib64/libnexmon.so",
                "/data/local/tmp/libnexmon.so",
            ).any { File(it).exists() }
            return libPresent
        }

        private fun isRooted(): Boolean = listOf(
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su",
        ).any { File(it).exists() }
    }
}
