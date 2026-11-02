package com.wscanplus.android.core

import org.junit.Assert.*
import org.junit.Test

class AiProviderManagerTest {

    @Test
    fun `providerType enum mapping works correctly`() {
        assertEquals(
            AiProviderManager.ProviderType.OFFLINE_RULES,
            AiProviderManager.ProviderType.fromId(AppPreferences.AI_PROVIDER_OFFLINE_RULES)
        )
        assertEquals(
            AiProviderManager.ProviderType.GEMINI,
            AiProviderManager.ProviderType.fromId(AppPreferences.AI_PROVIDER_GEMINI)
        )
        assertEquals(
            AiProviderManager.ProviderType.LOCAL_AVF,
            AiProviderManager.ProviderType.fromId(AppPreferences.AI_PROVIDER_LOCAL_AVF)
        )
        assertEquals(
            AiProviderManager.ProviderType.OPENAI_COMPATIBLE,
            AiProviderManager.ProviderType.fromId(AppPreferences.AI_PROVIDER_OPENAI)
        )
        // Unknown id defaults safely to OFFLINE_RULES
        assertEquals(
            AiProviderManager.ProviderType.OFFLINE_RULES,
            AiProviderManager.ProviderType.fromId("unknown_provider")
        )
    }

    @Test
    fun `local providers are marked local for privacy`() {
        assertTrue(AiProviderManager.ProviderType.OFFLINE_RULES.isLocal)
        assertTrue(AiProviderManager.ProviderType.LOCAL_AVF.isLocal)
        assertFalse(AiProviderManager.ProviderType.GEMINI.isLocal)
        assertFalse(AiProviderManager.ProviderType.OPENAI_COMPATIBLE.isLocal)
    }

    @Test
    fun `mcp agent activity records latency and status correctly`() {
        val activity = McpAgentActivity(
            clientName = "cli-agent",
            toolName = "avf_run_command",
            latencyMs = 18,
            status = "ok",
            summary = "Command: iw dev"
        )
        assertEquals("cli-agent", activity.clientName)
        assertEquals("avf_run_command", activity.toolName)
        assertEquals(18L, activity.latencyMs)
        assertEquals("ok", activity.status)
        assertTrue(activity.id.isNotBlank())
    }
}
