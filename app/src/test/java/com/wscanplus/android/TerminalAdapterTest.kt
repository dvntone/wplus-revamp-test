package com.wscanplus.android.core

import org.junit.Assert.*
import org.junit.Test

class TerminalAdapterTest {

    @Test
    fun `terminalConnectionState lifecycle values match specification`() {
        val states = TerminalConnectionState.entries
        assertTrue(states.contains(TerminalConnectionState.IDLE))
        assertTrue(states.contains(TerminalConnectionState.DISCOVERING))
        assertTrue(states.contains(TerminalConnectionState.CONNECTING))
        assertTrue(states.contains(TerminalConnectionState.AUTHENTICATING))
        assertTrue(states.contains(TerminalConnectionState.READY))
        assertTrue(states.contains(TerminalConnectionState.BUSY))
        assertTrue(states.contains(TerminalConnectionState.RECOVERING))
        assertTrue(states.contains(TerminalConnectionState.FAILED))
    }

    @Test
    fun `terminalAdapterRegistry returns correct adapter instances`() {
        val avf = TerminalAdapterRegistry.getAdapter(IntegrationsManager.ExecutionBackend.PIXEL_AVF)
        assertEquals("pixel_avf", avf.id)
        assertEquals(TerminalConnectionState.IDLE, avf.state.value)

        val termux = TerminalAdapterRegistry.getAdapter(IntegrationsManager.ExecutionBackend.TERMUX)
        assertEquals("termux", termux.id)
        assertEquals(TerminalConnectionState.IDLE, termux.state.value)

        val shizuku = TerminalAdapterRegistry.getAdapter(IntegrationsManager.ExecutionBackend.SHIZUKU)
        assertEquals("shizuku", shizuku.id)
        assertEquals(TerminalConnectionState.IDLE, shizuku.state.value)
    }

    @Test
    fun `terminalExecutionResult encapsulates idempotency key and timing`() {
        val result = TerminalExecutionResult(
            success = true,
            exitCode = 0,
            stdout = "Debian GNU/Linux 13",
            stderr = null,
            executionChannel = "AVF_GUEST_SOCKET",
            idempotencyKey = "req-12345",
            durationMs = 45L
        )
        assertEquals("req-12345", result.idempotencyKey)
        assertTrue(result.success)
        assertEquals(0, result.exitCode)
        assertEquals(45L, result.durationMs)
    }
}
