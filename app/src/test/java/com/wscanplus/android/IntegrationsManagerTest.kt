package com.wscanplus.android.core

import org.junit.Assert.*
import org.junit.Test

class IntegrationsManagerTest {

    @Test
    fun `executionBackend entries contain all expected backends`() {
        val backends = IntegrationsManager.ExecutionBackend.entries
        assertTrue(backends.contains(IntegrationsManager.ExecutionBackend.AUTO))
        assertTrue(backends.contains(IntegrationsManager.ExecutionBackend.PIXEL_AVF))
        assertTrue(backends.contains(IntegrationsManager.ExecutionBackend.TERMUX))
        assertTrue(backends.contains(IntegrationsManager.ExecutionBackend.SHIZUKU))
        assertTrue(backends.contains(IntegrationsManager.ExecutionBackend.ROOT))
        assertTrue(backends.contains(IntegrationsManager.ExecutionBackend.ADB_SHELL))
    }

    @Test
    fun `pixel terminal package constants match official specifications`() {
        assertEquals("com.google.android.virtualmachine.terminal", IntegrationsManager.PKG_PIXEL_TERMINAL)
        assertEquals("com.android.virtualmachine.terminal", IntegrationsManager.PKG_AOSP_TERMINAL)
        assertEquals("android.software.virtualization", IntegrationsManager.FEATURE_VIRTUALIZATION)
    }

    @Test
    fun `agentMcpServer getLocalIpAddress returns non-empty ip`() {
        val ip = AgentMcpServer.getLocalIpAddress()
        assertNotNull(ip)
        assertTrue(ip.isNotBlank())
    }

    @Test
    fun `termuxBridge constants and default status are correct`() {
        assertEquals("com.termux", TermuxBridge.PKG_TERMUX)
        assertEquals("com.termux.permission.RUN_COMMAND", TermuxBridge.PERMISSION_RUN_COMMAND)
        assertEquals("/data/data/com.termux/files/usr", TermuxBridge.TERMUX_PREFIX)
        val defaultStatus = TermuxBridge.TermuxStatus()
        assertFalse(defaultStatus.isInstalled)
        assertFalse(defaultStatus.isPermissionGranted)
        assertFalse(defaultStatus.isExecutionVerified)
    }

    @Test
    fun `pixelAvfDebianGuest constants and default state are isolated`() {
        assertEquals("127.0.0.1", PixelAvfDebianGuest.DEFAULT_GUEST_HOST)
        assertEquals(2222, PixelAvfDebianGuest.DEFAULT_SSH_PORT)
        assertEquals(8766, PixelAvfDebianGuest.DEFAULT_AGENT_PORT)
        val defaultState = PixelAvfDebianGuest.AvfGuestState()
        assertFalse(defaultState.isInstalled)
        assertFalse(defaultState.isReachable)
        assertFalse(defaultState.isExecutionVerified)
    }

    @Test
    fun `attackHeuristics exposes rich supportingEvidence on evil twin detection`() {
        val heuristics = AttackHeuristics()
        val firstScan = listOf(
            AttackHeuristics.AccessPoint("00:11:22:33:44:55", "CorpNet", -65, "WPA2-PSK")
        )
        heuristics.analyzeEvilTwin(firstScan)

        val secondScan = listOf(
            AttackHeuristics.AccessPoint("00:11:22:33:44:55", "CorpNet", -65, "WPA2-PSK"),
            AttackHeuristics.AccessPoint("66:77:88:99:AA:BB", "CorpNet", -40, "") // Rogue AP: security downgrade + OUI mismatch
        )
        val threats = heuristics.analyzeEvilTwin(secondScan)
        assertEquals(1, threats.size)
        val threat = threats[0]
        assertEquals(AttackHeuristics.AttackType.EVIL_TWIN, threat.attackType)
        assertNotNull(threat.supportingEvidence)
        assertTrue(threat.supportingEvidence.containsKey("rogueBssid"))
        assertEquals("66:77:88:99:AA:BB", threat.supportingEvidence["rogueBssid"])
        assertTrue(threat.supportingEvidence.containsKey("observedBssids"))
        assertTrue(threat.supportingEvidence.containsKey("rssiDelta"))
        assertTrue(threat.supportingEvidence.containsKey("encryptionSuiteMismatch"))
        assertTrue(threat.supportingEvidence.containsKey("ouiMatch"))
    }
}
