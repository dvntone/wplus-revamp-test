package com.wscanplus.android.core

import org.junit.Assert.*
import org.junit.Test

class AvfModularToolchainTest {

    @Test
    fun `tool catalog contains expected red and blue tools`() {
        val tools = AvfToolchainDetector.tools.value
        val kismet = tools.find { it.id == "kismet" }
        assertNotNull(kismet)
        assertEquals(AvfToolchainDetector.SecurityTeam.BLUE_TEAM, kismet?.team)
        assertEquals(2501, kismet?.defaultPort)

        val aircrack = tools.find { it.id == "aircrack-ng" }
        assertNotNull(aircrack)
        assertEquals(AvfToolchainDetector.SecurityTeam.RED_TEAM, aircrack?.team)

        val rtlTcp = tools.find { it.id == "rtl_tcp" }
        assertNotNull(rtlTcp)
        assertEquals(AvfToolchainDetector.SecurityTeam.SDR_RF, rtlTcp?.team)
        assertEquals(1234, rtlTcp?.defaultPort)
    }

    @Test
    fun `enriched scan result serialization produces valid json`() {
        val result = EnrichedScanResult(
            ssid = "CorpSec-Audit",
            bssid = "00:11:22:33:44:55",
            levelDbm = -54,
            frequencyMhz = 5180,
            band = "5 GHz",
            channel = 36,
            timestampMicros = 123456789L,
            channelWidth = "80 MHz",
            centerFreq0 = 5210,
            centerFreq1 = 0,
            operatorFriendlyName = null,
            venueName = null,
            isRttResponder = true,
            wifiStandard = "802.11ax (Wi-Fi 6/6E)",
            capabilitiesRaw = "[WPA3-SAE-CCMP][RSN-SAE-CCMP][ESS]",
            securityTokens = listOf("WPA3-SAE-CCMP", "RSN-SAE-CCMP", "ESS"),
            securitySummary = "WPA3-SAE",
            isWpsEnabled = false,
            isOpen = false,
            isWep = false,
            isWpa3 = true,
            isWpa2 = false,
            countryCode = "US",
            vendorOuis = listOf("00:40:96"),
            vendorIdentities = listOf("Cisco Systems"),
            rawIeCount = 8,
            riskScore = 0,
            riskReasons = emptyList()
        )

        val json = result.toJson()
        assertTrue(json.contains("\"ssid\":\"CorpSec-Audit\""))
        assertTrue(json.contains("\"bssid\":\"00:11:22:33:44:55\""))
        assertTrue(json.contains("\"frequency_mhz\":5180"))
        assertTrue(json.contains("\"is_rtt_responder\":true"))
        assertTrue(json.contains("\"vendor_identities\":[\"Cisco Systems\"]"))
        assertTrue(json.contains("\"country_code\":\"US\""))
    }

    @Test
    fun `sdr usb bridge state initial values meet specifications`() {
        val state = AvfHardwareBridge.sdrState.value
        assertTrue(state.gateExplanation.contains("/vendor/etc/avf/assignable_devices.xml"))
        assertEquals(1234, state.rtlTcpPort)
    }
}
