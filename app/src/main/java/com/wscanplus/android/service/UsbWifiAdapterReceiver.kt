package com.wscanplus.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.File

/**
 * UsbWifiAdapterReceiver – [BroadcastReceiver] that fires whenever a USB device
 * is attached or detached from the Android OTG port.
 *
 * When a **known Wi-Fi adapter** (matched by VID:PID or vendor ID) is connected,
 * the receiver:
 *  1. Waits briefly for the kernel to create the network interface.
 *  2. Enumerates `/sys/class/net/` to find the new `wlan*` interface.
 *  3. Forwards an `ACTION_USB_ADAPTER_CONNECTED` intent to [WatchdogService],
 *     which restarts [WifiScanningService] so [UsbWifiScanner] is selected.
 *
 * ## Kernel driver requirements
 * Android does not ship USB Wi-Fi drivers for most adapters. A custom kernel
 * (e.g. NetHunter, TWRP-capable devices) is required for the adapter to be
 * recognised as a wireless interface. Supported chipsets and drivers:
 *
 * | Chipset     | Driver      | Common adapters                            |
 * |-------------|-------------|--------------------------------------------|
 * | AR9271      | ath9k_htc   | Alfa AWUS036NHA, TP-Link TL-WN722N v1      |
 * | RTL8188EU   | rtl8188eu   | TP-Link TL-WN725N v2/v3, Edimax EW-7811Un |
 * | RTL8812AU   | 88XXau/DKMS | Alfa AWUS036ACH, ASUS USB-AC56             |
 * | MT7601U     | mt7601u     | Various budget adapters                    |
 * | RT5370      | rt2800usb   | Ralink/MediaTek RT5370                     |
 *
 * Register this receiver in the manifest with `USB_DEVICE_ATTACHED` and
 * `USB_DEVICE_DETACHED` intent filters, plus a `<meta-data>` pointing to
 * `res/xml/usb_device_filter.xml` to pre-filter to known adapters.
 */
class UsbWifiAdapterReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleAttached(context, device)
            UsbManager.ACTION_USB_DEVICE_DETACHED -> handleDetached(context, device)
        }
    }

    private fun handleAttached(context: Context, device: UsbDevice) {
        if (!isKnownWifiAdapter(device)) {
            Log.d(TAG, "USB ${device.vendorId.hex()}:${device.productId.hex()} – not a known Wi-Fi adapter")
            return
        }
        Log.i(TAG, "USB Wi-Fi adapter attached: ${device.productName} " +
            "(VID=${device.vendorId.hex()} PID=${device.productId.hex()})")

        // Perform the delayed interface lookup on a daemon thread so we don't
        // block onReceive (which must complete in ~10 s on the main thread).
        Thread {
            // Give the kernel ~1.5 s to load the driver and create the netif.
            Thread.sleep(1_500)
            val iface = findNewWifiInterface()
            if (iface != null) {
                Log.i(TAG, "Kernel created Wi-Fi interface: $iface")
            } else {
                Log.w(TAG, "Adapter attached but no new wlan* interface found. " +
                    "The kernel driver for ${device.productName} may not be loaded. " +
                    "Consider a NetHunter or custom kernel with USB Wi-Fi support.")
            }
            val serviceIntent = Intent(context, WatchdogService::class.java).apply {
                action = WatchdogService.ACTION_USB_ADAPTER_CONNECTED
                putExtra(WatchdogService.EXTRA_USB_INTERFACE, iface ?: DEFAULT_USB_IFACE)
            }
            context.startForegroundService(serviceIntent)
        }.also { it.isDaemon = true }.start()
    }

    private fun handleDetached(context: Context, device: UsbDevice) {
        if (!isKnownWifiAdapter(device)) return
        Log.i(TAG, "USB Wi-Fi adapter detached: ${device.productName}")
        context.startForegroundService(
            Intent(context, WatchdogService::class.java).apply {
                action = WatchdogService.ACTION_USB_ADAPTER_DISCONNECTED
            }
        )
    }

    companion object {
        private const val TAG             = "UsbWifiAdapterReceiver"
        private const val DEFAULT_USB_IFACE = "wlan1"

        /**
         * Vendor IDs of manufacturers whose USB products are predominantly Wi-Fi adapters.
         * False positives (non-Wi-Fi devices from these vendors) are filtered out by
         * the VID:PID list below.
         */
        private val KNOWN_WIFI_VENDOR_IDS = setOf(
            0x0CF3, // Qualcomm Atheros (AR9271, AR7010 …)
            0x0BDA, // Realtek (RTL8187, RTL8188EU, RTL8812AU …)
            0x0E8D, // MediaTek / Ralink (MT7601U, RT2870, RT3070 …)
            0x148F, // Ralink Technology (RT2870, RT3070, RT5370 …)
            0x2357, // TP-Link (newer Realtek/MediaTek chipsets)
            0x7392, // Edimax Technology
        )

        /** High-confidence VID:PID pairs for specific known-good adapters. */
        private val KNOWN_WIFI_VID_PID = setOf(
            0x0CF3 to 0x9271, // Atheros AR9271 (Alfa AWUS036NHA, TP-Link TL-WN722N v1)
            0x0CF3 to 0x7015, // Atheros AR9287
            0x0BDA to 0x8179, // Realtek RTL8188EU (TP-Link TL-WN725N v2/v3)
            0x0BDA to 0x881A, // Realtek RTL8812AU (Alfa AWUS036ACH)
            0x0BDA to 0x8814, // Realtek RTL8814AU
            0x0BDA to 0x8187, // Realtek RTL8187L
            0x0BDA to 0x8192, // Realtek RTL8192EU
            0x0E8D to 0x7601, // MediaTek MT7601U
            0x0E8D to 0x2870, // Ralink RT2870
            0x148F to 0x3070, // Ralink RT3070
            0x148F to 0x5370, // Ralink RT5370 (Raspberry Pi dongle)
            0x2357 to 0x0109, // TP-Link TL-WN823N v3
            0x7392 to 0x7811, // Edimax EW-7811Un (RTL8188CUS)
        )

        /** Returns true if [device] matches a known USB Wi-Fi adapter. */
        fun isKnownWifiAdapter(device: UsbDevice): Boolean =
            (device.vendorId to device.productId) in KNOWN_WIFI_VID_PID ||
                device.vendorId in KNOWN_WIFI_VENDOR_IDS

        /**
         * Scans `/sys/class/net/` for wireless interfaces beyond `wlan0`.
         * These are created by the kernel when a USB Wi-Fi adapter driver loads.
         *
         * @return The lexicographically first `wlan*` interface name that is not
         *         `wlan0`, or `null` if none exists.
         */
        fun findNewWifiInterface(): String? =
            runCatching {
                File("/sys/class/net").listFiles()
                    ?.map { it.name }
                    ?.filter { it.startsWith("wlan") && it != "wlan0" }
                    ?.minOrNull()
            }.getOrNull()

        private fun Int.hex(): String = "0x%04X".format(this)
    }
}
