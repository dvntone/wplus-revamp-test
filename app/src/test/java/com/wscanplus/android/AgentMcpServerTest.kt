package com.wscanplus.android.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class AgentMcpServerTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var server: AgentMcpServer
    private val testToken = "test_auth_token_1234567890abcdef"

    @Before
    fun setup() {
        mockEditor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "putString" -> mockEditor
                "apply", "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor

        mockPrefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getString" -> testToken
                "edit" -> mockEditor
                else -> null
            }
        } as SharedPreferences

        mockContext = object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = mockPrefs
            override fun getApplicationContext(): Context = this
        }

        server = AgentMcpServer(mockContext, port = 8089)
    }

    @Test
    fun `validateToken returns false for null, blank, or invalid tokens`() {
        assertFalse("Null token must be rejected", server.validateToken(null))
        assertFalse("Empty token must be rejected", server.validateToken(""))
        assertFalse("Whitespace token must be rejected", server.validateToken("   "))
        assertFalse("Mismatched token must be rejected", server.validateToken("wrong_token_abc"))
        assertFalse("Bearer with wrong token must be rejected", server.validateToken("Bearer wrong_token_abc"))
    }

    @Test
    fun `validateToken returns true for exact match and Bearer prefix`() {
        assertTrue("Exact token must match", server.validateToken(testToken))
        assertTrue("Bearer prefix must match", server.validateToken("Bearer $testToken"))
        assertTrue("bearer lowercase prefix must match", server.validateToken("bearer $testToken"))
        assertTrue("Token with surrounding spaces must match", server.validateToken("  $testToken  "))
    }

    @Test
    fun `MCP initialize and ping do not require command execution token`() = runBlocking {
        val initReq = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
        }.toString()

        val initResp = JSONObject(server.handleMcpRequest(initReq, isRequestAuthorized = false))
        assertEquals("2.0", initResp.getString("jsonrpc"))
        assertTrue(initResp.has("result"))
        assertEquals("wscanplus-avf-agent", initResp.getJSONObject("result").getJSONObject("serverInfo").getString("name"))

        val pingReq = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "ping")
        }.toString()

        val pingResp = JSONObject(server.handleMcpRequest(pingReq, isRequestAuthorized = false))
        assertEquals("ok", pingResp.getJSONObject("result").getString("status"))
    }

    @Test
    fun `MCP tools list publishes avf_run_command with mandatory token documentation`() = runBlocking {
        val listReq = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 3)
            put("method", "tools/list")
        }.toString()

        val resp = JSONObject(server.handleMcpRequest(listReq, isRequestAuthorized = false))
        val tools = resp.getJSONObject("result").getJSONArray("tools")
        var foundAvfTool = false
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            if (tool.getString("name") == "avf_run_command") {
                foundAvfTool = true
                val properties = tool.getJSONObject("inputSchema").getJSONObject("properties")
                assertTrue("avf_run_command schema must specify token parameter", properties.has("token"))
                assertTrue("avf_run_command schema must specify command parameter", properties.has("command"))
            }
        }
        assertTrue("avf_run_command must be present in tools list", foundAvfTool)
    }

    @Test
    fun `MCP tools call avf_run_command rejects unauthenticated invocation with code -32001`() = runBlocking {
        val unauthReq = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 4)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", "avf_run_command")
                put("arguments", JSONObject().apply {
                    put("command", "id")
                })
            })
        }.toString()

        val respStr = server.handleMcpRequest(unauthReq, isRequestAuthorized = false)
        val resp = JSONObject(respStr)
        assertTrue("Response must contain error object", resp.has("error"))
        val error = resp.getJSONObject("error")
        assertEquals(-32001, error.getInt("code"))
        assertTrue("Error message must indicate unauthorized", error.getString("message").contains("Unauthorized", ignoreCase = true))
    }

    @Test
    fun `MCP tools call avf_run_command rejects invalid token in arguments`() = runBlocking {
        val invalidTokenReq = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 5)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", "avf_run_command")
                put("arguments", JSONObject().apply {
                    put("command", "uname -a")
                    put("token", "invalid_bogus_token")
                })
            })
        }.toString()

        val respStr = server.handleMcpRequest(invalidTokenReq, isRequestAuthorized = false)
        val resp = JSONObject(respStr)
        assertTrue(resp.has("error"))
        assertEquals(-32001, resp.getJSONObject("error").getInt("code"))
    }

    @Test
    fun `MCP tools call avf_run_command accepts valid token in arguments and executes`() = runBlocking {
        val validTokenReq = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 6)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", "avf_run_command")
                put("arguments", JSONObject().apply {
                    put("command", "echo test")
                    put("backend", "PIXEL_AVF")
                    put("token", testToken)
                })
            })
        }.toString()

        val respStr = server.handleMcpRequest(validTokenReq, isRequestAuthorized = false)
        val resp = JSONObject(respStr)
        // Since token is valid, it proceeds to execution (should not return auth error -32001)
        if (resp.has("error")) {
            val code = resp.getJSONObject("error").getInt("code")
            assertNotEquals("Auth error -32001 should not be returned when token is valid", -32001, code)
        } else {
            assertTrue("Success response must contain result content", resp.has("result"))
        }
    }

    @Test
    fun `MCP tools call avf_run_command succeeds when request was authorized at transport layer`() = runBlocking {
        val req = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 7)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", "avf_run_command")
                put("arguments", JSONObject().apply {
                    put("command", "uptime")
                    put("backend", "PIXEL_AVF")
                })
            })
        }.toString()

        val respStr = server.handleMcpRequest(req, isRequestAuthorized = true)
        val resp = JSONObject(respStr)
        // Transport-authorized request must bypass auth gate
        if (resp.has("error")) {
            val code = resp.getJSONObject("error").getInt("code")
            assertNotEquals("Auth error -32001 should not be returned when session is authorized", -32001, code)
        } else {
            assertTrue(resp.has("result"))
        }
    }

    @Test
    fun `agentActivities initializes empty without fabricated mock telemetry`() {
        assertTrue("MCP activity feed must start empty per zero simulated telemetry policy", server.agentActivities.value.isEmpty())
        
        server.recordActivity("test-client", "test-tool", 42L, "ok", "Real probe executed")
        assertEquals(1, server.agentActivities.value.size)
        val activity = server.agentActivities.value.first()
        assertEquals("test-client", activity.clientName)
        assertEquals("test-tool", activity.toolName)
        assertEquals(42L, activity.latencyMs)
        assertEquals("ok", activity.status)
        assertEquals("Real probe executed", activity.summary)
    }

    @Test
    fun `TailscaleDetector correctly identifies CGNAT 100 64 0 0 slash 10 address range`() {
        val tsIp1 = java.net.InetAddress.getByName("100.64.0.1")
        val tsIp2 = java.net.InetAddress.getByName("100.115.92.14")
        val tsIp3 = java.net.InetAddress.getByName("100.127.255.254")

        val lanIp = java.net.InetAddress.getByName("192.168.1.100")
        val privateIp = java.net.InetAddress.getByName("10.0.0.5")
        val wanIp = java.net.InetAddress.getByName("8.8.8.8")
        val loopback = java.net.InetAddress.getByName("127.0.0.1")

        assertTrue("100.64.0.1 must be recognized as Tailscale", TailscaleDetector.isTailscaleIpv4(tsIp1))
        assertTrue("100.115.92.14 must be recognized as Tailscale", TailscaleDetector.isTailscaleIpv4(tsIp2))
        assertTrue("100.127.255.254 must be recognized as Tailscale", TailscaleDetector.isTailscaleIpv4(tsIp3))

        assertFalse("192.168.1.100 must not be recognized as Tailscale", TailscaleDetector.isTailscaleIpv4(lanIp))
        assertFalse("10.0.0.5 must not be recognized as Tailscale", TailscaleDetector.isTailscaleIpv4(privateIp))
        assertFalse("8.8.8.8 must not be recognized as Tailscale", TailscaleDetector.isTailscaleIpv4(wanIp))
        assertFalse("127.0.0.1 must not be recognized as Tailscale", TailscaleDetector.isTailscaleIpv4(loopback))
    }

    @Test
    fun `isAuthorizedClientAddress strictly enforces local versus remote access policies`() {
        val loopback = java.net.InetAddress.getByName("127.0.0.1")
        val tailscalePeer = java.net.InetAddress.getByName("100.80.45.12")
        val lanUntrusted = java.net.InetAddress.getByName("192.168.1.55")
        val wanUntrusted = java.net.InetAddress.getByName("142.250.190.46")

        // In Local-Only Mode (allowRemote = false):
        assertTrue("Loopback must always be authorized", TailscaleDetector.isAuthorizedClientAddress(loopback, allowRemote = false))
        assertFalse("Tailscale peer must be blocked when remote mode is disabled", TailscaleDetector.isAuthorizedClientAddress(tailscalePeer, allowRemote = false))
        assertFalse("LAN peer must be blocked in local-only mode", TailscaleDetector.isAuthorizedClientAddress(lanUntrusted, allowRemote = false))
        assertFalse("WAN peer must be blocked in local-only mode", TailscaleDetector.isAuthorizedClientAddress(wanUntrusted, allowRemote = false))

        // In Remote Tailscale Mode (allowRemote = true):
        assertTrue("Loopback must remain authorized in remote mode", TailscaleDetector.isAuthorizedClientAddress(loopback, allowRemote = true))
        assertTrue("Tailscale peer must be authorized in remote mode", TailscaleDetector.isAuthorizedClientAddress(tailscalePeer, allowRemote = true))
        assertFalse("LAN peer must be rejected even when remote mode is enabled", TailscaleDetector.isAuthorizedClientAddress(lanUntrusted, allowRemote = true))
        assertFalse("WAN peer must be rejected even when remote mode is enabled", TailscaleDetector.isAuthorizedClientAddress(wanUntrusted, allowRemote = true))
    }

    @Test
    fun `read-only tools execute without requiring authentication token`() = runBlocking {
        val req = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 10)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", "wscan_system_status")
                put("arguments", JSONObject())
            })
        }.toString()

        val respStr = server.handleMcpRequest(req, isRequestAuthorized = false)
        val resp = JSONObject(respStr)
        assertFalse("Read-only system status must not produce auth error", resp.has("error"))
        assertTrue("Read-only system status must return result content", resp.has("result"))
    }

    @Test
    fun `server start accurately tracks local and remote listener configurations`() {
        try {
            val localStarted = server.start(allowRemote = false)
            if (localStarted) {
                assertFalse("Remote mode must be inactive in local-only start", server.isRemoteModeActive)
                assertEquals("127.0.0.1", server.activeBoundAddress)
            }
            server.stop()
            assertFalse(server.isServerRunning())
        } finally {
            server.stop()
        }
    }
}
