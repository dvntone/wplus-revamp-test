package com.wscanplus.android.core

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AttackHeuristicsTest {

    private lateinit var heuristics: AttackHeuristics

    @Before
    fun setUp() {
        heuristics = AttackHeuristics()
    }

    // ── Evil Twin / Karma ─────────────────────────────────────────────────────

    @Test
    fun `analyzeEvilTwin - no threat when single AP per SSID`() {
        val aps = listOf(
            AttackHeuristics.AccessPoint("AA:BB:CC:DD:EE:01", "HomeNetwork", -60),
            AttackHeuristics.AccessPoint("AA:BB:CC:DD:EE:02", "Neighbour", -70),
        )
        val threats = heuristics.analyzeEvilTwin(aps)
        assertTrue(threats.isEmpty())
    }

    @Test
    fun `analyzeEvilTwin - threat when same SSID seen from new BSSID`() {
        val firstScan = listOf(
            AttackHeuristics.AccessPoint("AA:BB:CC:DD:EE:01", "HomeNetwork", -60),
        )
        heuristics.analyzeEvilTwin(firstScan)  // seed known BSSIDs

        val secondScan = listOf(
            AttackHeuristics.AccessPoint("AA:BB:CC:DD:EE:01", "HomeNetwork", -60),
            AttackHeuristics.AccessPoint("11:22:33:44:55:66", "HomeNetwork", -45), // evil twin
        )
        val threats = heuristics.analyzeEvilTwin(secondScan)
        assertEquals(1, threats.size)
        assertEquals(AttackHeuristics.AttackType.EVIL_TWIN, threats[0].attackType)
        assertEquals(AttackHeuristics.Severity.HIGH, threats[0].severity)
        assertEquals("11:22:33:44:55:66", threats[0].bssid)
    }

    @Test
    fun `analyzeEvilTwin - no false positive for hidden SSID`() {
        val aps = listOf(
            AttackHeuristics.AccessPoint("AA:BB:CC:DD:EE:01", "", -60),
            AttackHeuristics.AccessPoint("11:22:33:44:55:66", "", -45),
        )
        val threats = heuristics.analyzeEvilTwin(aps)
        assertTrue("Hidden SSIDs should not be flagged", threats.isEmpty())
    }

    // ── Deauth flood ──────────────────────────────────────────────────────────

    @Test
    fun `analyzeDeauthFrame - no alert below threshold`() {
        val bssid = "AA:BB:CC:DD:EE:01"
        val frame = makeDeauthFrame(bssid = bssid)
        repeat(AttackHeuristics.DEFAULT_DEAUTH_THRESHOLD - 1) {
            val result = heuristics.analyzeDeauthFrame(frame.copy(timestampMs = System.currentTimeMillis() + it))
            assertNull(result)
        }
    }

    @Test
    fun `analyzeDeauthFrame - alert when threshold exceeded`() {
        val bssid = "AA:BB:CC:DD:EE:01"
        val now = System.currentTimeMillis()
        var lastResult: AttackHeuristics.ThreatEvent? = null
        repeat(AttackHeuristics.DEFAULT_DEAUTH_THRESHOLD + 1) { i ->
            lastResult = heuristics.analyzeDeauthFrame(
                makeDeauthFrame(bssid = bssid, timestampMs = now + i)
            )
        }
        assertNotNull(lastResult)
        assertEquals(AttackHeuristics.AttackType.DEAUTH_FLOOD, lastResult!!.attackType)
    }

    @Test
    fun `analyzeDeauthFrame - broadcast deauth is HIGH severity`() {
        val bssid = "AA:BB:CC:DD:EE:01"
        val now = System.currentTimeMillis()
        var lastResult: AttackHeuristics.ThreatEvent? = null
        repeat(AttackHeuristics.DEFAULT_DEAUTH_THRESHOLD + 1) { i ->
            lastResult = heuristics.analyzeDeauthFrame(
                makeDeauthFrame(
                    bssid = bssid,
                    destMac = "ff:ff:ff:ff:ff:ff",
                    timestampMs = now + i
                )
            )
        }
        assertEquals(AttackHeuristics.Severity.HIGH, lastResult?.severity)
    }

    @Test
    fun `analyzeDeauthFrame - old frames evicted from window`() {
        val bssid = "AA:BB:CC:DD:EE:01"
        val old = 1L
        // Send threshold-1 frames with ancient timestamps (outside window)
        repeat(AttackHeuristics.DEFAULT_DEAUTH_THRESHOLD - 1) { i ->
            heuristics.analyzeDeauthFrame(makeDeauthFrame(bssid = bssid, timestampMs = old + i))
        }
        // Now send 1 frame with a current timestamp — should NOT trigger alert
        val result = heuristics.analyzeDeauthFrame(
            makeDeauthFrame(bssid = bssid, timestampMs = System.currentTimeMillis())
        )
        assertNull("Old frames outside window should be evicted", result)
    }

    // ── PMKID / KRACK ─────────────────────────────────────────────────────────

    @Test
    fun `analyzeEapolFrame - PMKID alert for unassociated MAC sending M1`() {
        val frame = makeEapolFrame(messageNumber = 1, bssid = "AA:BB:CC:DD:EE:01", sourceMac = "11:22:33:44:55:66")
        val threats = heuristics.analyzeEapolFrame(frame)
        assertEquals(1, threats.size)
        assertEquals(AttackHeuristics.AttackType.PMKID_SNIFFING, threats[0].attackType)
    }

    @Test
    fun `analyzeEapolFrame - no PMKID alert for associated MAC`() {
        heuristics.recordAssociation("11:22:33:44:55:66", "AA:BB:CC:DD:EE:01")
        val frame = makeEapolFrame(messageNumber = 1, bssid = "AA:BB:CC:DD:EE:01", sourceMac = "11:22:33:44:55:66")
        val threats = heuristics.analyzeEapolFrame(frame)
        assertTrue(threats.isEmpty())
    }

    @Test
    fun `analyzeEapolFrame - KRACK alert on duplicate M3 replay counter`() {
        val bssid = "AA:BB:CC:DD:EE:01"
        val firstM3 = makeEapolFrame(messageNumber = 3, bssid = bssid, replayCounter = 42L)
        val duplicateM3 = makeEapolFrame(messageNumber = 3, bssid = bssid, replayCounter = 42L)
        heuristics.analyzeEapolFrame(firstM3)
        val threats = heuristics.analyzeEapolFrame(duplicateM3)
        assertEquals(1, threats.size)
        assertEquals(AttackHeuristics.AttackType.KRACK, threats[0].attackType)
        assertEquals(AttackHeuristics.Severity.HIGH, threats[0].severity)
    }

    @Test
    fun `analyzeEapolFrame - no KRACK alert for unique M3 replay counters`() {
        val bssid = "AA:BB:CC:DD:EE:01"
        val m3a = makeEapolFrame(messageNumber = 3, bssid = bssid, replayCounter = 1L)
        val m3b = makeEapolFrame(messageNumber = 3, bssid = bssid, replayCounter = 2L)
        heuristics.analyzeEapolFrame(m3a)
        val threats = heuristics.analyzeEapolFrame(m3b)
        assertTrue(threats.isEmpty())
    }

    // ── OUI normalisation ─────────────────────────────────────────────────────

    @Test
    fun `normalizeMacPrefix - colon-separated MAC`() {
        assertEquals("AABBCC", OuiLookup.normalizeMacPrefix("aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `normalizeMacPrefix - dash-separated MAC`() {
        assertEquals("AABBCC", OuiLookup.normalizeMacPrefix("AA-BB-CC-DD-EE-FF"))
    }

    @Test
    fun `normalizeMacPrefix - raw hex MAC`() {
        assertEquals("AABBCC", OuiLookup.normalizeMacPrefix("AABBCCDDEEFF"))
    }

    @Test
    fun `normalizeMacPrefix - returns null for short input`() {
        assertNull(OuiLookup.normalizeMacPrefix("AA:BB"))
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    fun `reset - clears all state`() {
        val ap = AttackHeuristics.AccessPoint("AA:BB:CC:DD:EE:01", "Net", -60)
        heuristics.analyzeEvilTwin(listOf(ap))
        heuristics.reset()
        // After reset, the same AP should be added fresh with no prior knowledge
        val threats = heuristics.analyzeEvilTwin(listOf(ap))
        assertTrue("No threats expected after reset", threats.isEmpty())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeDeauthFrame(
        bssid: String = "AA:BB:CC:DD:EE:01",
        sourceMac: String = bssid,
        destMac: String = "CC:DD:EE:FF:00:11",
        reasonCode: Int = 3,
        timestampMs: Long = System.currentTimeMillis(),
    ) = AttackHeuristics.DeauthFrame(sourceMac, destMac, bssid, reasonCode, timestampMs)

    private fun makeEapolFrame(
        sourceMac: String = "11:22:33:44:55:66",
        bssid: String = "AA:BB:CC:DD:EE:01",
        messageNumber: Int = 1,
        replayCounter: Long = 1L,
        nonce: ByteArray = ByteArray(32) { it.toByte() },
        timestampMs: Long = System.currentTimeMillis(),
    ) = AttackHeuristics.EapolFrame(sourceMac, bssid, messageNumber, replayCounter, nonce, timestampMs)
}
