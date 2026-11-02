package com.wscanplus.android.core

import android.net.wifi.ScanResult
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * EnrichedScanData – fully extracts every hardware & protocol field from Android [ScanResult]
 * based on the deep-dive specification for wireless threat detection and AVF toolchain ingestion.
 */
data class EnrichedScanResult(
    val ssid: String,
    val bssid: String,
    val levelDbm: Int,
    val frequencyMhz: Int,
    val band: String,
    val channel: Int,
    val timestampMicros: Long,
    val channelWidth: String,
    val centerFreq0: Int,
    val centerFreq1: Int,
    val operatorFriendlyName: String?,
    val venueName: String?,
    val isRttResponder: Boolean,
    val wifiStandard: String,
    val capabilitiesRaw: String,
    val securityTokens: List<String>,
    val securitySummary: String,
    val isWpsEnabled: Boolean,
    val isOpen: Boolean,
    val isWep: Boolean,
    val isWpa3: Boolean,
    val isWpa2: Boolean,
    val countryCode: String?,
    val vendorOuis: List<String>,
    val vendorIdentities: List<String>,
    val rawIeCount: Int,
    val riskScore: Int,
    val riskReasons: List<String>
) {
    /**
     * Serializes to JSON for forwarding over local socket/MCP to AVF Linux tools (Kismet, Wireshark, etc.)
     */
    fun toJson(): String {
        val ouis = vendorOuis.joinToString("\",\"", "\"", "\"")
        val tokens = securityTokens.joinToString("\",\"", "\"", "\"")
        val reasons = riskReasons.joinToString("\",\"", "\"", "\"")
        return """{
            |"ssid":"${escapeJson(ssid)}",
            |"bssid":"$bssid",
            |"level_dbm":$levelDbm,
            |"frequency_mhz":$frequencyMhz,
            |"band":"$band",
            |"channel":$channel,
            |"timestamp_micros":$timestampMicros,
            |"channel_width":"$channelWidth",
            |"center_freq0":$centerFreq0,
            |"center_freq1":$centerFreq1,
            |"is_rtt_responder":$isRttResponder,
            |"wifi_standard":"$wifiStandard",
            |"capabilities":"${escapeJson(capabilitiesRaw)}",
            |"security_summary":"$securitySummary",
            |"is_wps":$isWpsEnabled,
            |"is_open":$isOpen,
            |"is_wep":$isWep,
            |"is_wpa3":$isWpa3,
            |"is_wpa2":$isWpa2,
            |"country_code":${countryCode?.let { "\"$it\"" } ?: "null"},
            |"vendor_ouis":[${if (vendorOuis.isEmpty()) "" else ouis}],
            |"vendor_identities":[${if (vendorIdentities.isEmpty()) "" else vendorIdentities.joinToString("\",\"", "\"", "\"")}],
            |"raw_ie_count":$rawIeCount,
            |"risk_score":$riskScore,
            |"risk_reasons":[${if (riskReasons.isEmpty()) "" else reasons}]
        |}""".trimMargin()
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", "")
    }
}

object EnrichedScanParser {

    /**
     * Converts a raw Android [ScanResult] into an [EnrichedScanResult]
     */
    fun parse(scanResult: ScanResult): EnrichedScanResult {
        val ssid = scanResult.SSID ?: ""
        val bssid = scanResult.BSSID ?: "00:00:00:00:00:00"
        val level = scanResult.level
        val freq = scanResult.frequency

        val band = when {
            freq in 2400..2484 -> "2.4 GHz"
            freq in 4900..5900 -> "5 GHz"
            freq in 5925..7125 -> "6 GHz (Wi-Fi 6E/7)"
            else -> "Unknown ($freq MHz)"
        }

        val channel = frequencyToChannel(freq)
        val timestamp = scanResult.timestamp

        val channelWidth = parseChannelWidth(scanResult)
        val centerFreq0 = scanResult.centerFreq0
        val centerFreq1 = scanResult.centerFreq1

        val operatorName = scanResult.operatorFriendlyName?.toString()?.ifBlank { null }
        val venueName = scanResult.venueName?.toString()?.ifBlank { null }

        val isRtt = scanResult.is80211mcResponder

        val standard = parseWifiStandard(scanResult)
        val capabilitiesRaw = scanResult.capabilities ?: ""
        val securityTokens = parseSecurityTokens(capabilitiesRaw)

        val isOpen = isNetworkOpen(capabilitiesRaw)
        val isWep = capabilitiesRaw.contains("WEP", ignoreCase = true)
        val isWpa3 = capabilitiesRaw.contains("SAE", ignoreCase = true) || capabilitiesRaw.contains("WPA3", ignoreCase = true)
        val isWpa2 = capabilitiesRaw.contains("WPA2", ignoreCase = true) || capabilitiesRaw.contains("RSN", ignoreCase = true)
        val isWps = capabilitiesRaw.contains("WPS", ignoreCase = true)

        val securitySummary = when {
            isOpen -> "Open (Unencrypted)"
            isWep -> "WEP (Insecure)"
            isWpa3 && isWpa2 -> "WPA3 / WPA2 Mixed"
            isWpa3 -> "WPA3-SAE"
            isWpa2 -> "WPA2-PSK"
            capabilitiesRaw.contains("WPA", ignoreCase = true) -> "WPA-TKIP (Obsolete)"
            else -> capabilitiesRaw.ifBlank { "Unknown" }
        }

        // Parse Information Elements (IE 221 vendor OUIs, IE 7 country)
        val ieData = parseInformationElements(scanResult)

        // Calculate Threat Risk Score
        val riskReasons = mutableListOf<String>()
        var score = 0

        if (isOpen) {
            score += 40
            riskReasons.add("Unencrypted open network transmits data in plaintext")
        }
        if (isWep) {
            score += 65
            riskReasons.add("Obsolete WEP cipher is trivially cracked in minutes")
        }
        if (isWps) {
            score += 20
            riskReasons.add("WPS enabled - vulnerable to offline PIN brute-force")
        }
        if (ssid.isEmpty()) {
            score += 15
            riskReasons.add("Hidden SSID beacon detected")
        }
        if (ieData.countryCode != null && ieData.countryCode != "US" && ieData.countryCode != "US ") {
            riskReasons.add("Foreign regulatory country code: ${ieData.countryCode}")
        }

        return EnrichedScanResult(
            ssid = ssid,
            bssid = bssid,
            levelDbm = level,
            frequencyMhz = freq,
            band = band,
            channel = channel,
            timestampMicros = timestamp,
            channelWidth = channelWidth,
            centerFreq0 = centerFreq0,
            centerFreq1 = centerFreq1,
            operatorFriendlyName = operatorName,
            venueName = venueName,
            isRttResponder = isRtt,
            wifiStandard = standard,
            capabilitiesRaw = capabilitiesRaw,
            securityTokens = securityTokens,
            securitySummary = securitySummary,
            isWpsEnabled = isWps,
            isOpen = isOpen,
            isWep = isWep,
            isWpa3 = isWpa3,
            isWpa2 = isWpa2,
            countryCode = ieData.countryCode,
            vendorOuis = ieData.vendorOuis,
            vendorIdentities = ieData.vendorIdentities,
            rawIeCount = ieData.totalIes,
            riskScore = score.coerceIn(0, 100),
            riskReasons = riskReasons
        )
    }

    private fun frequencyToChannel(freq: Int): Int {
        return when {
            freq == 2484 -> 14
            freq in 2412..2472 -> (freq - 2407) / 5
            freq in 5170..5825 -> (freq - 5000) / 5
            freq in 5955..7115 -> (freq - 5950) / 5
            else -> 0
        }
    }

    private fun parseChannelWidth(scanResult: ScanResult): String {
        return when (scanResult.channelWidth) {
            ScanResult.CHANNEL_WIDTH_20MHZ -> "20 MHz"
            ScanResult.CHANNEL_WIDTH_40MHZ -> "40 MHz"
            ScanResult.CHANNEL_WIDTH_80MHZ -> "80 MHz"
            ScanResult.CHANNEL_WIDTH_160MHZ -> "160 MHz"
            ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80 MHz"
            6 -> "320 MHz (Wi-Fi 7)" // CHANNEL_WIDTH_320MHZ added in API 33
            else -> "Default"
        }
    }

    private fun parseWifiStandard(scanResult: ScanResult): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return when (scanResult.wifiStandard) {
                ScanResult.WIFI_STANDARD_11N -> "802.11n (Wi-Fi 4)"
                ScanResult.WIFI_STANDARD_11AC -> "802.11ac (Wi-Fi 5)"
                ScanResult.WIFI_STANDARD_11AX -> "802.11ax (Wi-Fi 6/6E)"
                ScanResult.WIFI_STANDARD_11BE -> "802.11be (Wi-Fi 7)"
                ScanResult.WIFI_STANDARD_LEGACY -> "Legacy (802.11a/b/g)"
                else -> "Standard"
            }
        }
        return "Standard"
    }

    private fun parseSecurityTokens(capabilities: String): List<String> {
        val regex = Regex("\\[(.*?)\\]")
        return regex.findAll(capabilities).map { it.groupValues[1] }.toList()
    }

    private fun isNetworkOpen(capabilities: String): Boolean {
        if (capabilities.isBlank()) return true
        val upper = capabilities.uppercase()
        return !upper.contains("WPA") && !upper.contains("WEP") && !upper.contains("SAE") && !upper.contains("EAP")
    }

    private data class ParsedIes(
        val countryCode: String?,
        val vendorOuis: List<String>,
        val vendorIdentities: List<String>,
        val totalIes: Int
    )

    private fun parseInformationElements(scanResult: ScanResult): ParsedIes {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ParsedIes(null, emptyList(), emptyList(), 0)
        }

        return try {
            val ies = scanResult.informationElements ?: return ParsedIes(null, emptyList(), emptyList(), 0)
            var country: String? = null
            val ouis = mutableListOf<String>()
            val vendors = mutableListOf<String>()

            for (ie in ies) {
                when (ie.id) {
                    7 -> { // Country IE
                        val buffer = ie.bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                        if (buffer.remaining() >= 2) {
                            val c1 = buffer.get().toInt().toChar()
                            val c2 = buffer.get().toInt().toChar()
                            country = "$c1$c2"
                        }
                    }
                    221 -> { // Vendor Specific IE
                        val buffer = ie.bytes.duplicate().order(ByteOrder.BIG_ENDIAN)
                        if (buffer.remaining() >= 3) {
                            val b1 = buffer.get().toInt() and 0xFF
                            val b2 = buffer.get().toInt() and 0xFF
                            val b3 = buffer.get().toInt() and 0xFF
                            val ouiHex = String.format("%02X:%02X:%02X", b1, b2, b3)
                            ouis.add(ouiHex)

                            val vendorName = when (ouiHex) {
                                "00:50:F2" -> "Microsoft (WPS/WMM)"
                                "00:40:96" -> "Cisco Systems"
                                "50:6F:9A" -> "Wi-Fi Alliance"
                                "00:17:F2" -> "Apple Inc."
                                "F8:32:E4" -> "Ubiquiti Networks"
                                "00:03:7F" -> "Atheros / Qualcomm"
                                "00:10:18" -> "Broadcom"
                                "00:0C:E7" -> "Mediatek"
                                else -> "Vendor OUI $ouiHex"
                            }
                            if (!vendors.contains(vendorName)) {
                                vendors.add(vendorName)
                            }
                        }
                    }
                }
            }

            ParsedIes(country, ouis, vendors, ies.size)
        } catch (_: Exception) {
            ParsedIes(null, emptyList(), emptyList(), 0)
        }
    }
}
