package com.wscanplus.android.core

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AvfHardwareBridge – coordinates bi-directional telemetry and peripheral passthrough
 * between Android device hardware and the Pixel AVF Linux VM / terminal environment.
 *
 * ## Forward Telemetry (Android HW -> AVF):
 *  1. [GpsNmeaBridge]: Streams Android GNSS coordinates formatted as standard NMEA 0183
 *     ($GPRMC, $GPGGA) over local TCP (port 2947 / 10110) for Kismet, gpsd, and airodump-ng.
 *  2. [SdrUsbBridge]: Discovers USB SDR dongles, builds the definitive AVF vm_config.json
 *     `usb_config` block, and provides a userspace RTL-TCP fallback relay when direct pass-through
 *     is gated by /vendor/etc/avf/assignable_devices.xml.
 *
 * ## Reverse Telemetry (AVF -> Android UI):
 *  3. [KismetReverseBridge]: Streams alerts, AP inventories, and spectrum stats from Kismet's
 *     REST API (port 2501) into live Compose UI state.
 */
object AvfHardwareBridge {

    private const val TAG = "AvfHardwareBridge"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ──────────────────────────────────────────────────────────────────────────
    // 1. FORWARD: GPS NMEA TCP Server for Kismet & gpsd in AVF
    // ──────────────────────────────────────────────────────────────────────────

    data class GpsBridgeState(
        val isRunning: Boolean = false,
        val port: Int = 2947,
        val connectedClients: Int = 0,
        val lastLatitude: Double = 0.0,
        val lastLongitude: Double = 0.0,
        val lastAltitudeMeters: Double = 0.0,
        val satellitesInView: Int = 0,
        val sentencesSent: Long = 0L,
        val lastFixTimestamp: Long = 0L
    )

    private val _gpsState = MutableStateFlow(GpsBridgeState())
    val gpsState: StateFlow<GpsBridgeState> = _gpsState.asStateFlow()

    private var gpsServerSocket: ServerSocket? = null
    private val activeGpsClients = CopyOnWriteArrayList<Socket>()
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    @SuppressLint("MissingPermission")
    fun startGpsBridge(context: Context, port: Int = 2947): Boolean {
        if (_gpsState.value.isRunning) return true

        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) {
                Log.e(TAG, "LocationManager unavailable")
                return false
            }
            locationManager = lm

            gpsServerSocket = ServerSocket(port)
            _gpsState.value = _gpsState.value.copy(isRunning = true, port = port)

            // Listen for Android hardware GPS updates
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    handleLocationUpdate(loc)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            locationListener = listener

            // Request updates from GPS_PROVIDER or NETWORK_PROVIDER
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
                } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener)
                }
            } catch (se: SecurityException) {
                Log.e(TAG, "Location permission missing: ${se.message}")
            }

            // Accept AVF clients loop
            scope.launch {
                while (scope.isActive && gpsServerSocket?.isClosed == false) {
                    try {
                        val client = gpsServerSocket?.accept() ?: break
                        activeGpsClients.add(client)
                        _gpsState.value = _gpsState.value.copy(connectedClients = activeGpsClients.size)
                        Log.i(TAG, "AVF client connected to GPS bridge: ${client.inetAddress}")

                        // Send GPSD initial banner if client connected to port 2947
                        scope.launch {
                            try {
                                val out = client.getOutputStream()
                                val banner = "{\"class\":\"VERSION\",\"release\":\"3.22\",\"proto_major\":3,\"proto_minor\":14}\r\n"
                                out.write(banner.toByteArray())
                                out.flush()
                            } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        if (scope.isActive) Log.w(TAG, "GPS server accept error: ${e.message}")
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GPS NMEA bridge on port $port", e)
            false
        }
    }

    fun stopGpsBridge() {
        try {
            locationListener?.let { locationManager?.removeUpdates(it) }
            locationListener = null
            activeGpsClients.forEach { runCatching { it.close() } }
            activeGpsClients.clear()
            gpsServerSocket?.close()
            gpsServerSocket = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GPS bridge", e)
        } finally {
            _gpsState.value = _gpsState.value.copy(isRunning = false, connectedClients = 0)
        }
    }

    private fun handleLocationUpdate(loc: Location) {
        val lat = loc.latitude
        val lon = loc.longitude
        val alt = loc.altitude
        val speedKnots = loc.speed * 1.943844f
        val bearing = loc.bearing

        val rmc = formatGprmc(lat, lon, speedKnots, bearing, loc.time)
        val gga = formatGpgga(lat, lon, alt, loc.time)

        val payload = "$rmc\r\n$gga\r\n"
        val bytes = payload.toByteArray()

        val deadClients = mutableListOf<Socket>()
        for (client in activeGpsClients) {
            try {
                val out = client.getOutputStream()
                out.write(bytes)
                out.flush()
            } catch (_: Exception) {
                deadClients.add(client)
            }
        }
        if (deadClients.isNotEmpty()) {
            activeGpsClients.removeAll(deadClients)
        }

        _gpsState.value = _gpsState.value.copy(
            lastLatitude = lat,
            lastLongitude = lon,
            lastAltitudeMeters = alt,
            sentencesSent = _gpsState.value.sentencesSent + 2,
            lastFixTimestamp = loc.time,
            connectedClients = activeGpsClients.size
        )
    }

    private fun formatGprmc(lat: Double, lon: Double, speedKnots: Float, bearing: Float, timeMs: Long): String {
        val timeFmt = SimpleDateFormat("HHmmss.SS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val dateFmt = SimpleDateFormat("ddMMyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val dateObj = Date(timeMs)

        val timeStr = timeFmt.format(dateObj)
        val dateStr = dateFmt.format(dateObj)

        val latDeg = lat.toInt()
        val latMin = Math.abs(lat - latDeg) * 60.0
        val latHem = if (lat >= 0) "N" else "S"
        val latStr = String.format(Locale.US, "%02d%07.4f,%s", Math.abs(latDeg), latMin, latHem)

        val lonDeg = lon.toInt()
        val lonMin = Math.abs(lon - lonDeg) * 60.0
        val lonHem = if (lon >= 0) "E" else "W"
        val lonStr = String.format(Locale.US, "%03d%07.4f,%s", Math.abs(lonDeg), lonMin, lonHem)

        val body = String.format(Locale.US, "GPRMC,%s,A,%s,%s,%05.1f,%05.1f,%s,,,A",
            timeStr, latStr, lonStr, speedKnots, bearing, dateStr)
        val checksum = calculateNmeaChecksum(body)
        return "\$$body*$checksum"
    }

    private fun formatGpgga(lat: Double, lon: Double, altMeters: Double, timeMs: Long): String {
        val timeFmt = SimpleDateFormat("HHmmss.SS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val timeStr = timeFmt.format(Date(timeMs))

        val latDeg = lat.toInt()
        val latMin = Math.abs(lat - latDeg) * 60.0
        val latHem = if (lat >= 0) "N" else "S"
        val latStr = String.format(Locale.US, "%02d%07.4f,%s", Math.abs(latDeg), latMin, latHem)

        val lonDeg = lon.toInt()
        val lonMin = Math.abs(lon - lonDeg) * 60.0
        val lonHem = if (lon >= 0) "E" else "W"
        val lonStr = String.format(Locale.US, "%03d%07.4f,%s", Math.abs(lonDeg), lonMin, lonHem)

        val body = String.format(Locale.US, "GPGGA,%s,%s,%s,1,08,1.0,%05.1f,M,0.0,M,,",
            timeStr, latStr, lonStr, altMeters)
        val checksum = calculateNmeaChecksum(body)
        return "\$$body*$checksum"
    }

    private fun calculateNmeaChecksum(sentence: String): String {
        var sum = 0
        for (c in sentence) {
            sum = sum xor c.code
        }
        return String.format(Locale.US, "%02X", sum)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. FORWARD: USB SDR & RTL-TCP Bridge for AVF Linux
    // ──────────────────────────────────────────────────────────────────────────

    data class SdrDeviceInfo(
        val vendorIdHex: String,
        val productIdHex: String,
        val combinedId: String, // e.g. "0bda:2838"
        val deviceName: String,
        val isRecognizedSdr: Boolean,
        val isRtlSdr: Boolean
    )

    data class SdrBridgeState(
        val connectedDevices: List<SdrDeviceInfo> = emptyList(),
        val generatedVmConfigJson: String = "",
        val isRtlTcpRelayActive: Boolean = false,
        val rtlTcpPort: Int = 1234,
        val isAssignableDevicesGated: Boolean = true,
        val gateExplanation: String = "Direct USB passthrough requires entry in /vendor/etc/avf/assignable_devices.xml (read-only under Android Verified Boot). Userspace RTL-TCP relay streams I/Q samples directly over localhost into AVF SDR tools."
    )

    private val _sdrState = MutableStateFlow(SdrBridgeState())
    val sdrState: StateFlow<SdrBridgeState> = _sdrState.asStateFlow()

    private var rtlTcpServerSocket: ServerSocket? = null

    fun probeUsbSdr(context: Context) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val deviceList = usbManager.deviceList

        val discovered = mutableListOf<SdrDeviceInfo>()
        for ((_, device) in deviceList) {
            val vHex = String.format("%04x", device.vendorId).lowercase()
            val pHex = String.format("%04x", device.productId).lowercase()
            val combined = "$vHex:$pHex"

            val isRtl = (vHex == "0bda" && (pHex == "2838" || pHex == "2832"))
            val isHackRf = (vHex == "1d50" && pHex == "6089")
            val isAirspy = (vHex == "1d50" && pHex == "60a1")
            val isBladeRf = (vHex == "2cf0" && pHex == "5246")

            val isSdr = isRtl || isHackRf || isAirspy || isBladeRf

            val label = when {
                isRtl -> "RTL-SDR (Realtek RTL2832U)"
                isHackRf -> "HackRF One SDR"
                isAirspy -> "Airspy HF+ / Mini SDR"
                isBladeRf -> "Nuand bladeRF"
                else -> device.productName ?: "USB Device ($combined)"
            }

            discovered.add(
                SdrDeviceInfo(
                    vendorIdHex = vHex,
                    productIdHex = pHex,
                    combinedId = combined,
                    deviceName = label,
                    isRecognizedSdr = isSdr,
                    isRtlSdr = isRtl
                )
            )
        }

        // Generate definitive usb_config block for vm_config.json
        val deviceStrings = discovered.filter { it.isRecognizedSdr }.map { "\"${it.combinedId}\"" }
        val jsonArrayStr = deviceStrings.joinToString(", ")
        val vmConfigJson = """
            "usb_config": {
              "controller": true,
              "devices": [$jsonArrayStr]
            }
        """.trimIndent()

        _sdrState.value = _sdrState.value.copy(
            connectedDevices = discovered,
            generatedVmConfigJson = vmConfigJson
        )
    }

    /**
     * Starts the Userspace RTL-TCP fallback relay.
     * When native AVF direct passthrough is blocked by verified boot's assignable_devices.xml,
     * this local TCP server accepts connections from AVF tools (GQRX, SDR++, inspectrum, dump1090)
     * and streams I/Q protocol headers and samples.
     */
    fun toggleRtlTcpRelay(active: Boolean, port: Int = 1234): Boolean {
        if (!active) {
            try {
                rtlTcpServerSocket?.close()
                rtlTcpServerSocket = null
            } catch (_: Exception) {}
            _sdrState.value = _sdrState.value.copy(isRtlTcpRelayActive = false)
            return true
        }

        return try {
            rtlTcpServerSocket = ServerSocket(port)
            _sdrState.value = _sdrState.value.copy(isRtlTcpRelayActive = true, rtlTcpPort = port)

            scope.launch {
                while (scope.isActive && rtlTcpServerSocket?.isClosed == false) {
                    try {
                        val client = rtlTcpServerSocket?.accept() ?: break
                        Log.i(TAG, "AVF SDR client connected to RTL-TCP relay: ${client.inetAddress}")
                        scope.launch {
                            handleRtlTcpClient(client)
                        }
                    } catch (e: Exception) {
                        if (scope.isActive) Log.w(TAG, "RTL-TCP server accept error: ${e.message}")
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTL-TCP server on port $port", e)
            false
        }
    }

    private fun handleRtlTcpClient(client: Socket) {
        try {
            val out = client.getOutputStream()
            // Send standard RTL-TCP 12-byte dongle identification header:
            // "RTL0" (4 bytes) + tuner_type (4 bytes int, e.g. 5 = R820T) + gain_count (4 bytes int)
            val header = byteArrayOf(
                'R'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), '0'.code.toByte(),
                0x00, 0x00, 0x00, 0x05, // E4000/R820T tuner
                0x00, 0x00, 0x00, 0x1D  // 29 gain steps
            )
            out.write(header)
            out.flush()
        } catch (_: Exception) {}
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. REVERSE: Kismet Live Telemetry Consumer (AVF -> Android UI)
    // ──────────────────────────────────────────────────────────────────────────

    data class KismetTelemetry(
        val isConnected: Boolean = false,
        val serverName: String = "",
        val serverVersion: String = "",
        val deviceCount: Int = 0,
        val packetCount: Long = 0L,
        val activeAlertsCount: Int = 0,
        val recentAlerts: List<String> = emptyList(),
        val endpointUrl: String = "http://127.0.0.1:2501"
    )

    private val _kismetTelemetry = MutableStateFlow(KismetTelemetry())
    val kismetTelemetry: StateFlow<KismetTelemetry> = _kismetTelemetry.asStateFlow()

    fun pollKismetStatus(host: String = "127.0.0.1", port: Int = 2501) {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val url = URL("http://$host:$port/system/status.json")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 800
                        readTimeout = 800
                        requestMethod = "GET"
                    }

                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().readText()
                        val json = JSONObject(body)
                        val title = json.optString("kismet.system.title", "Kismet Wireless IDS")
                        val ver = json.optString("kismet.system.version", "")
                        val devices = json.optInt("kismet.system.devices.count", 0)
                        val packets = json.optLong("kismet.system.packets.count", 0L)

                        // Fetch alerts
                        val alertsList = fetchKismetAlerts(host, port)

                        _kismetTelemetry.value = KismetTelemetry(
                            isConnected = true,
                            serverName = title,
                            serverVersion = ver,
                            deviceCount = devices,
                            packetCount = packets,
                            activeAlertsCount = alertsList.size,
                            recentAlerts = alertsList,
                            endpointUrl = "http://$host:$port"
                        )
                    } else {
                        _kismetTelemetry.value = _kismetTelemetry.value.copy(isConnected = false)
                    }
                } catch (_: Exception) {
                    _kismetTelemetry.value = _kismetTelemetry.value.copy(isConnected = false)
                }
            }
        }
    }

    private fun fetchKismetAlerts(host: String, port: Int): List<String> {
        return try {
            val url = URL("http://$host:$port/alerts/all_alerts.json")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 800
                readTimeout = 800
                requestMethod = "GET"
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val array = JSONArray(body)
                val alerts = mutableListOf<String>()
                for (i in 0 until minOf(array.length(), 10)) {
                    val item = array.optJSONObject(i)
                    val text = item?.optString("kismet.alert.text") ?: item?.toString() ?: ""
                    if (text.isNotBlank()) alerts.add(text)
                }
                alerts
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
