package com.wscanplus.android.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class PixelAvfDebianGuestTest {

    private lateinit var mockContext: Context

    @Before
    fun setup() {
        val mockEditor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "putString" -> null
                "apply", "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor

        val mockPrefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getString" -> "fake_token_for_avf_testing_123"
                "edit" -> mockEditor
                else -> null
            }
        } as SharedPreferences

        mockContext = object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = mockPrefs
            override fun getApplicationContext(): Context = this
        }
    }

    @Test
    fun `default port constants isolate SSH from AVF guest agent daemon`() {
        assertEquals("Standard OpenSSH port", 2222, PixelAvfDebianGuest.DEFAULT_SSH_PORT)
        assertEquals("Dedicated AVF guest agent port", 8766, PixelAvfDebianGuest.DEFAULT_AGENT_PORT)
        assertEquals("127.0.0.1", PixelAvfDebianGuest.DEFAULT_GUEST_HOST)
    }

    @Test
    fun `AvfGuestState default constructor enforces strict isolation`() {
        val state = PixelAvfDebianGuest.AvfGuestState()
        assertFalse(state.isInstalled)
        assertFalse(state.isAuthorized)
        assertFalse(state.isRunning)
        assertFalse(state.isReachable)
        assertFalse(state.isExecutionVerified)
        assertEquals("127.0.0.1", state.guestHost)
        assertEquals(2222, state.guestPort)
        assertNull(state.osPrettyName)
        assertNull(state.osId)
        assertTrue(state.installedTools.isEmpty())
        assertEquals("Not probed", state.statusMessage)
    }

    @Test
    fun `GuestExecutionResult records channel and verifies debian identity flag`() {
        val res = PixelAvfDebianGuest.GuestExecutionResult(
            success = true,
            exitCode = 0,
            stdout = "Linux pixel-avf 6.6.0-debian #1 SMP PREEMPT",
            stderr = null,
            verifiedDebianEnvironment = true,
            executionChannel = "AVF_GUEST_AGENT"
        )
        assertTrue(res.success)
        assertEquals(0, res.exitCode)
        assertTrue(res.verifiedDebianEnvironment)
        assertEquals("AVF_GUEST_AGENT", res.executionChannel)
        assertNull(res.stderr)
    }

    @Test
    fun `executeGuestCommand fails safely when guest VM is unreachable without substituting host shell`() = runBlocking {
        // Probe an unreachable port on localhost (e.g. 59999)
        val result = PixelAvfDebianGuest.executeGuestCommand(
            context = mockContext,
            command = "cat /etc/os-release",
            host = "127.0.0.1",
            port = 59999,
            timeoutSeconds = 1
        )

        assertFalse("Unreachable guest command must not succeed", result.success)
        assertEquals(-1, result.exitCode)
        assertFalse("Unreachable guest must never be marked verified Debian", result.verifiedDebianEnvironment)
        assertEquals("NONE", result.executionChannel)
        assertNotNull(result.stderr)
        assertTrue(
            "Host shell output must not be substituted",
            result.stderr!!.contains("Android host shell was not substituted")
        )
    }
}
