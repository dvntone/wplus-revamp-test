package com.wscanplus.android.core

import android.content.Context
import android.util.Log
import com.wscanplus.android.data.db.AppDatabase
import com.wscanplus.android.data.db.ThreatLog
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AgentMcpServer – on-device Model Context Protocol (MCP) and REST bridge server.
 *
 * Allows CLI AI agents (Claude Code, Gemini CLI, local terminal agents, GlassHaven / SSH Haven)
 * and local network tools to:
 * 1. Execute commands directly in the Pixel AVF Linux VM or terminal environments.
 * 2. Query real-time Wi-Fi threat detections (Evil Twin, Deauth Floods, PMKID, KRACK).
 * 3. Trigger threat analysis and security remediation audits.
 * 4. Trigger Wi-Fi scans and export PCAP / CSV logs.
 */
data class McpAgentActivity(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val clientName: String,
    val toolName: String,
    val latencyMs: Long,
    val status: String = "ok",
    val summary: String = "",
)

class AgentMcpServer(private val context: Context, val port: Int = 8080) {

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var lastQueryTimestamp: Long = 0
        private set
    var totalQueriesHandled: Int = 0
        private set
    var lastQuerySummary: String = "No requests yet"
        private set

    val agentActivities = kotlinx.coroutines.flow.MutableStateFlow<List<McpAgentActivity>>(
        emptyList()
    )

    fun recordActivity(clientName: String, toolName: String, latencyMs: Long, status: String, summary: String) {
        val entry = McpAgentActivity(
            clientName = clientName,
            toolName = toolName,
            latencyMs = latencyMs,
            status = status,
            summary = summary
        )
        agentActivities.value = (listOf(entry) + agentActivities.value).take(50)
    }

    fun isServerRunning(): Boolean = isRunning.get()

    /**
     * Start the on-device MCP / REST bridge server.
     * Defaults to loopback 127.0.0.1 for zero-trust local security.
     */
    fun start(): Boolean {
        if (isRunning.get()) return true
        return try {
            val bindHost = "127.0.0.1"
            val socket = ServerSocket(port, 50, InetAddress.getByName(bindHost))
            serverSocket = socket
            isRunning.set(true)

            serverJob = scope.launch {
                Log.i(TAG, "Agent MCP Server listening strictly on loopback $bindHost:$port")
                while (isActive && isRunning.get()) {
                    try {
                        val client = socket.accept()
                        launch {
                            handleClient(client)
                        }
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        Log.w(TAG, "Accept error: ${e.message}")
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Agent MCP Server on port $port", e)
            isRunning.set(false)
            false
        }
    }

    /**
     * Stop the server.
     */
    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverJob?.cancel()
        serverSocket = null
        Log.i(TAG, "Agent MCP Server stopped")
    }

    /**
     * Validates an authorization token against the secure persistent secret in AppPreferences.
     */
    fun validateToken(tokenCandidate: String?): Boolean {
        if (tokenCandidate.isNullOrBlank()) return false
        val expected = AppPreferences.getOrCreateMcpAuthToken(context).trim()
        if (expected.isEmpty()) return false
        val clean = tokenCandidate.trim()
            .removePrefix("Bearer ")
            .removePrefix("bearer ")
            .trim()
        return clean.isNotEmpty() && clean == expected
    }

    /**
     * Handle incoming HTTP / MCP request with zero-trust token authentication and loopback enforcement.
     */
    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        var clientOrigin: String? = null
        try {
            // Enforce loopback-only client origin
            val remoteAddr = client.inetAddress
            if (!remoteAddr.isLoopbackAddress && remoteAddr.hostAddress != "127.0.0.1" && remoteAddr.hostAddress != "::1") {
                Log.w(TAG, "Rejected non-loopback connection attempt from ${remoteAddr.hostAddress}")
                client.close()
                return@withContext
            }

            client.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val out = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)

            val requestLine = reader.readLine() ?: return@withContext
            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext

            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val path = rawPath.substringBefore("?")
            val query = if (rawPath.contains("?")) rawPath.substringAfter("?") else ""
            val queryParams = if (query.isNotBlank()) {
                query.split("&").mapNotNull {
                    val kv = it.split("=", limit = 2)
                    if (kv.size == 2) kv[0] to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
                }.toMap()
            } else emptyMap()

            // Read headers
            var contentLength = 0
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                val colonIdx = line!!.indexOf(':')
                if (colonIdx > 0) {
                    val k = line!!.substring(0, colonIdx).trim().lowercase()
                    val v = line!!.substring(colonIdx + 1).trim()
                    headers[k] = v
                    if (k == "content-length") {
                        contentLength = v.toIntOrNull() ?: 0
                    }
                }
            }

            clientOrigin = headers["origin"]

            // Read body
            val body = if (contentLength > 0) {
                val chars = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = reader.read(chars, readTotal, contentLength - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
                String(chars, 0, readTotal)
            } else ""

            val bodyJson = if (body.isNotBlank()) {
                try { JSONObject(body) } catch (_: Exception) { null }
            } else null

            // Handle CORS preflight
            if (method == "OPTIONS") {
                sendResponse(out, 204, "No Content", "text/plain", "", clientOrigin)
                return@withContext
            }

            lastQueryTimestamp = System.currentTimeMillis()
            totalQueriesHandled++

            // Security check: Validate Bearer token on sensitive endpoints
            val authHeader = headers["authorization"] ?: headers["x-mcp-token"] ?: ""
            val queryToken = queryParams["token"] ?: queryParams["authToken"]
            val bodyToken = bodyJson?.optString("token")?.ifBlank { null }
                ?: bodyJson?.optString("authToken")?.ifBlank { null }
            val candidateToken = authHeader.ifBlank { queryToken ?: "" }.ifBlank { bodyToken ?: "" }

            val isAuthorized = validateToken(candidateToken)

            when {
                // Public Health Check
                path == "/health" && method == "GET" -> {
                    lastQuerySummary = "REST: /health"
                    val resp = JSONObject().apply {
                        put("status", "UP")
                        put("loopbackOnly", true)
                        put("bindAddress", "127.0.0.1")
                        put("authRequired", true)
                    }
                    sendResponse(out, 200, "OK", "application/json", resp.toString(), clientOrigin)
                }

                // Public Read-Only System Status
                (path == "/status" || path == "/api/status") && method == "GET" -> {
                    lastQuerySummary = "REST: /status"
                    val pixelAvf = IntegrationsManager.getPixelAvfInfo(context)
                    val shizuku = IntegrationsManager.getShizukuStatus(context)
                    val root = IntegrationsManager.getRootInfo(context)
                    val adb = IntegrationsManager.getAdbInfo(context)

                    val resp = JSONObject().apply {
                        put("app", "wscan+")
                        put("version", "1.0.0")
                        put("loopbackOnly", true)
                        put("bindAddress", "127.0.0.1")
                        put("authRequired", true)
                        put("pixelAvf", JSONObject().apply {
                            put("isPixel", pixelAvf.isPixelDevice)
                            put("avfSupported", pixelAvf.isAvfSupported)
                            put("terminalInstalled", pixelAvf.isTerminalAppInstalled)
                            put("description", pixelAvf.description)
                        })
                        put("shizuku", JSONObject().apply {
                            put("running", shizuku.isBinderAlive)
                            put("permissionGranted", shizuku.isPermissionGranted)
                            put("version", shizuku.version)
                        })
                        put("root", JSONObject().apply {
                            put("available", root.isSuBinaryFound)
                            put("manager", root.rootManager ?: "none")
                            put("mcpRootAllowed", AppPreferences.isMcpAllowRootEnabled(context))
                        })
                        put("wirelessAdb", JSONObject().apply {
                            put("enabled", adb.isWirelessAdbEnabled)
                            put("usbEnabled", adb.isUsbDebuggingEnabled)
                        })
                    }
                    sendResponse(out, 200, "OK", "application/json", resp.toString(), clientOrigin)
                }

                // MCP Endpoint: JSON-RPC 2.0 (allows discovery, but command execution strictly checks token)
                path == "/mcp" || path == "/rpc" -> {
                    lastQuerySummary = "MCP Protocol Request"
                    val responseJson = handleMcpRequest(body, isAuthorized)
                    sendResponse(out, 200, "OK", "application/json", responseJson, clientOrigin)
                }

                // REST: Terminal execution in AVF or selected backend (Strictly Authenticated)
                path == "/api/terminal" && method == "POST" -> {
                    lastQuerySummary = "REST: /api/terminal"
                    val reqJson = bodyJson ?: JSONObject()
                    val inlineToken = reqJson.optString("token").ifBlank { reqJson.optString("authToken") }
                    val isTerminalAuthorized = isAuthorized || validateToken(inlineToken)

                    if (!isTerminalAuthorized) {
                        Log.w(TAG, "Unauthorized /api/terminal invocation attempt from ${client.inetAddress.hostAddress}")
                        val errResp = JSONObject().apply {
                            put("success", false)
                            put("exitCode", 401)
                            put("error", "Unauthorized: Valid authorization token required for command execution. Provide Authorization: Bearer <token> or 'token' in request body.")
                            put("status", 401)
                        }
                        sendResponse(out, 401, "Unauthorized", "application/json", errResp.toString(), clientOrigin)
                        return@withContext
                    }

                    val cmd = reqJson.optString("command", "uname -a")
                    val backendName = reqJson.optString("backend", "PIXEL_AVF")
                    val backend = try {
                        IntegrationsManager.ExecutionBackend.valueOf(backendName.uppercase())
                    } catch (_: Exception) {
                        IntegrationsManager.ExecutionBackend.PIXEL_AVF
                    }

                    // Enforce policy: Root execution over MCP must be explicitly enabled
                    if (backend == IntegrationsManager.ExecutionBackend.ROOT && !AppPreferences.isMcpAllowRootEnabled(context)) {
                        val resp = JSONObject().apply {
                            put("success", false)
                            put("exitCode", 403)
                            put("output", "")
                            put("error", "Root command execution via MCP is disabled by default for device security. Enable in Settings.")
                            put("backend", backend.displayName)
                            put("status", 403)
                        }
                        sendResponse(out, 403, "Forbidden", "application/json", resp.toString(), clientOrigin)
                        return@withContext
                    }

                    val res = IntegrationsManager.executeDiagnostic(context, backend, cmd)
                    val resp = JSONObject().apply {
                        put("success", res.success)
                        put("exitCode", res.exitCode)
                        put("output", res.output)
                        put("error", res.error ?: "")
                        put("command", cmd)
                        put("backend", backend.displayName)
                    }
                    sendResponse(out, 200, "OK", "application/json", resp.toString(), clientOrigin)
                }

                // All other API endpoints require isAuthorized
                !isAuthorized -> {
                    Log.w(TAG, "Unauthorized request attempt to $path from ${client.inetAddress.hostAddress}")
                    val errResp = JSONObject().apply {
                        put("error", "Unauthorized")
                        put("message", "Valid Bearer token required. Configure Authorization: Bearer <token>")
                        put("status", 401)
                    }
                    sendResponse(out, 401, "Unauthorized", "application/json", errResp.toString(), clientOrigin)
                }

                // REST: Ingest raw or parsed Deauth frames from Kismet / tshark / AVF
                path == "/api/frames/deauth" && method == "POST" -> {
                    lastQuerySummary = "REST: /api/frames/deauth"
                    val reqJson = bodyJson ?: JSONObject()
                    val src = reqJson.optString("sourceMac")
                    val dest = reqJson.optString("destMac", "ff:ff:ff:ff:ff:ff")
                    val bssid = reqJson.optString("bssid", src)
                    val reason = reqJson.optInt("reasonCode", 7)
                    val frame = AttackHeuristics.DeauthFrame(
                        sourceMac = src,
                        destMac = dest,
                        bssid = bssid,
                        reasonCode = reason,
                        timestampMs = System.currentTimeMillis()
                    )
                    com.wscanplus.android.service.WifiScanningService.ingestDeauthFrame(frame)
                    val resp = JSONObject().apply {
                        put("status", "accepted")
                        put("frame", "deauth")
                        put("bssid", bssid)
                    }
                    sendResponse(out, 200, "OK", "application/json", resp.toString(), clientOrigin)
                }

                // REST: Ingest EAPOL handshake frames from Kismet / tshark / AVF
                path == "/api/frames/eapol" && method == "POST" -> {
                    lastQuerySummary = "REST: /api/frames/eapol"
                    val reqJson = bodyJson ?: JSONObject()
                    val src = reqJson.optString("sourceMac")
                    val bssid = reqJson.optString("bssid")
                    val msgNum = reqJson.optInt("messageNumber", 1)
                    val replay = reqJson.optLong("replayCounter", 1L)
                    val frame = AttackHeuristics.EapolFrame(
                        sourceMac = src,
                        bssid = bssid,
                        messageNumber = msgNum,
                        replayCounter = replay,
                        nonce = ByteArray(32),
                        timestampMs = System.currentTimeMillis()
                    )
                    com.wscanplus.android.service.WifiScanningService.ingestEapolFrame(frame)
                    val resp = JSONObject().apply {
                        put("status", "accepted")
                        put("frame", "eapol")
                        put("bssid", bssid)
                        put("messageNumber", msgNum)
                    }
                    sendResponse(out, 200, "OK", "application/json", resp.toString(), clientOrigin)
                }

                // REST: Recent threats JSON
                path == "/api/threats" -> {
                    lastQuerySummary = "REST: /api/threats"
                    val db = AppDatabase.getInstance(context)
                    val threats = db.threatLogDao().getRecent(50)
                    val arr = JSONArray()
                    threats.forEach { t ->
                        arr.put(JSONObject().apply {
                            put("id", t.id)
                            put("timestamp", t.timestamp)
                            put("attackType", t.attackType)
                            put("severity", t.severity)
                            put("bssid", t.bssid)
                            put("ssid", t.ssid)
                            put("description", t.description)
                        })
                    }
                    val resp = JSONObject().apply {
                        put("count", threats.size)
                        put("threats", arr)
                    }
                    sendResponse(out, 200, "OK", "application/json", resp.toString(), clientOrigin)
                }

                else -> {
                    sendResponse(
                        out,
                        404,
                        "Not Found",
                        "application/json",
                        JSONObject().apply { put("error", "Endpoint not found. Use /mcp, /api/terminal, /api/threats, /api/frames/deauth, /api/frames/eapol, /status, or /health") }.toString(),
                        clientOrigin
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client request", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Handle Model Context Protocol (MCP) JSON-RPC 2.0 requests.
     */
    suspend fun handleMcpRequest(body: String, isRequestAuthorized: Boolean = false): String {
        val json = try { JSONObject(body) } catch (e: Exception) {
            return makeRpcError(null, -32700, "Parse error: ${e.message}")
        }

        val id = json.opt("id")
        val method = json.optString("method")
        val params = json.optJSONObject("params") ?: JSONObject()

        return when (method) {
            "initialize" -> {
                JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", JSONObject().apply {
                        put("protocolVersion", "2024-11-05")
                        put("serverInfo", JSONObject().apply {
                            put("name", "wscanplus-avf-agent")
                            put("version", "1.0.0")
                        })
                        put("capabilities", JSONObject().apply {
                            put("tools", JSONObject())
                            put("logging", JSONObject())
                        })
                    })
                }.toString()
            }

            "notifications/initialized", "ping" -> {
                JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", JSONObject().apply { put("status", "ok") })
                }.toString()
            }

            "tools/list" -> {
                val tools = JSONArray().apply {
                    // Tool 1: avf_run_command (strictly authenticated)
                    put(JSONObject().apply {
                        put("name", "avf_run_command")
                        put("description", "Execute direct shell commands in the Google Pixel AVF Linux VM or terminal environment. Requires authorization token.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("command", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Shell command to run (e.g. 'iw dev', 'uname -a', 'airmon-ng', 'tcpdump --version')")
                                })
                                put("backend", JSONObject().apply {
                                    put("type", "string")
                                    put("enum", JSONArray(listOf("PIXEL_AVF", "SHIZUKU", "ROOT", "ADB_SHELL", "AUTO")))
                                    put("description", "Execution backend. Default is PIXEL_AVF.")
                                })
                                put("token", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Authorization token configured in wscan+ AppPreferences. Required if not authenticated via HTTP header.")
                                })
                            })
                            put("required", JSONArray().apply { put("command") })
                        })
                    })

                    // Tool 2: wscan_get_threats
                    put(JSONObject().apply {
                        put("name", "wscan_get_threats")
                        put("description", "Retrieve captured Wi-Fi threats detected by wscan+ (Evil Twin, Deauth Floods, PMKID Sniffing, KRACK).")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("limit", JSONObject().apply {
                                    put("type", "integer")
                                    put("description", "Maximum number of threat logs to return (default: 20)")
                                })
                                put("severity", JSONObject().apply {
                                    put("type", "string")
                                    put("enum", JSONArray(listOf("ALL", "HIGH", "MEDIUM", "LOW")))
                                    put("description", "Filter by severity level (default: ALL)")
                                })
                            })
                        })
                    })

                    // Tool 3: wscan_analyze_threats
                    put(JSONObject().apply {
                        put("name", "wscan_analyze_threats")
                        put("description", "Run automated security heuristic audit on captured Wi-Fi threats and produce actionable remediation advice.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject())
                        })
                    })

                    // Tool 4: wscan_system_status
                    put(JSONObject().apply {
                        put("name", "wscan_system_status")
                        put("description", "Check status of Google Pixel AVF, Shizuku, Root, and Wireless Debugging.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject())
                        })
                    })

                    // Tool 5: avf_detect_tools
                    put(JSONObject().apply {
                        put("name", "avf_detect_tools")
                        put("description", "Probe AVF/Terminal for red and blue team tools (Kismet, Wireshark/tshark, rtl_tcp, bettercap, aircrack-ng, etc.).")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject())
                        })
                    })

                    // Tool 6: avf_usb_sdr_config
                    put(JSONObject().apply {
                        put("name", "avf_usb_sdr_config")
                        put("description", "Detect connected USB SDR dongles and generate the definitive AVF vm_config.json usb_config block.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject())
                        })
                    })

                    // Tool 7: avf_hardware_telemetry
                    put(JSONObject().apply {
                        put("name", "avf_hardware_telemetry")
                        put("description", "Get live GPS NMEA forward bridge status and reverse Kismet telemetry.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject())
                        })
                    })

                    // Tool 8: wscan_ingest_deauth
                    put(JSONObject().apply {
                        put("name", "wscan_ingest_deauth")
                        put("description", "Ingest captured 802.11 Deauth/Disassociation frames from Kismet, tshark, or AVF capture tools for intrusion detection.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("sourceMac", JSONObject().apply { put("type", "string"); put("description", "Transmitter MAC address") })
                                put("destMac", JSONObject().apply { put("type", "string"); put("description", "Target MAC or broadcast ff:ff:ff:ff:ff:ff") })
                                put("bssid", JSONObject().apply { put("type", "string"); put("description", "Access Point BSSID") })
                                put("reasonCode", JSONObject().apply { put("type", "integer"); put("description", "802.11 deauth reason code (default: 7)") })
                            })
                            put("required", JSONArray().apply { put("sourceMac") })
                        })
                    })

                    // Tool 9: wscan_ingest_eapol
                    put(JSONObject().apply {
                        put("name", "wscan_ingest_eapol")
                        put("description", "Ingest WPA/WPA2/WPA3 4-way handshake EAPOL frames for PMKID harvesting and KRACK replay attack analysis.")
                        put("inputSchema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("sourceMac", JSONObject().apply { put("type", "string"); put("description", "Transmitting client or AP MAC") })
                                put("bssid", JSONObject().apply { put("type", "string"); put("description", "Access point BSSID") })
                                put("messageNumber", JSONObject().apply { put("type", "integer"); put("description", "EAPOL message number (1-4)") })
                                put("replayCounter", JSONObject().apply { put("type", "integer"); put("description", "Handshake replay counter") })
                            })
                            put("required", JSONArray().apply { put("sourceMac"); put("bssid"); put("messageNumber") })
                        })
                    })
                }

                JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", JSONObject().apply {
                        put("tools", tools)
                    })
                }.toString()
            }

            "tools/call" -> {
                val toolName = params.optString("name")
                val arguments = params.optJSONObject("arguments") ?: JSONObject()
                val paramToken = params.optString("token").ifBlank { params.optString("authToken") }
                val effectiveAuth = isRequestAuthorized || validateToken(paramToken)
                executeToolCall(id, toolName, arguments, effectiveAuth)
            }

            else -> {
                makeRpcError(id, -32601, "Method '$method' not found")
            }
        }
    }

    suspend fun executeToolCall(id: Any?, toolName: String, args: JSONObject, isRequestAuthorized: Boolean = false): String {
        val startMs = System.currentTimeMillis()
        val result = when (toolName) {
            "avf_run_command" -> {
                val argToken = args.optString("token").ifBlank { args.optString("authToken") }
                val isCommandAuthorized = isRequestAuthorized || validateToken(argToken)

                if (!isCommandAuthorized) {
                    Log.w(TAG, "Unauthorized avf_run_command invocation rejected")
                    recordActivity(
                        clientName = "CLI Agent",
                        toolName = toolName,
                        latencyMs = System.currentTimeMillis() - startMs,
                        status = "error",
                        summary = "Rejected unauthorized command execution"
                    )
                    return makeRpcError(id, -32001, "Unauthorized: Valid authorization token required for command execution. Provide token in arguments or Authorization: Bearer <token> header.")
                }

                val cmd = args.optString("command", "")
                val backendStr = args.optString("backend", "PIXEL_AVF")
                val backend = try {
                    IntegrationsManager.ExecutionBackend.valueOf(backendStr.uppercase())
                } catch (_: Exception) {
                    IntegrationsManager.ExecutionBackend.PIXEL_AVF
                }

                if (cmd.isBlank()) {
                    return makeRpcError(id, -32602, "Command parameter cannot be blank")
                }

                if (backend == IntegrationsManager.ExecutionBackend.ROOT && !AppPreferences.isMcpAllowRootEnabled(context)) {
                    return makeRpcError(id, -32003, "Root execution via MCP is disabled by default for device security. Enable in Settings.")
                }

                val res = IntegrationsManager.executeDiagnostic(context, backend, cmd)
                val text = buildString {
                    appendLine("Command: $cmd")
                    appendLine("Backend: ${backend.displayName}")
                    appendLine("Success: ${res.success} (exit code ${res.exitCode})")
                    if (res.output.isNotBlank()) {
                        appendLine("Output:")
                        appendLine(res.output)
                    }
                    if (!res.error.isNullOrBlank()) {
                        appendLine("Error / Stderr:")
                        appendLine(res.error)
                    }
                }
                makeToolSuccessResponse(id, text)
            }

            "wscan_get_threats" -> {
                val limit = args.optInt("limit", 20)
                val severity = args.optString("severity", "ALL").uppercase()
                val db = AppDatabase.getInstance(context)
                val threats = db.threatLogDao().getRecent(limit)
                val filtered = if (severity == "ALL") threats else threats.filter { it.severity.equals(severity, true) }

                val text = if (filtered.isEmpty()) {
                    "No matching threat logs recorded in wscan+ database."
                } else {
                    buildString {
                        appendLine("Found ${filtered.size} threat records:")
                        filtered.forEachIndexed { i, t ->
                            appendLine("${i + 1}. [${t.severity}] ${t.attackType} | BSSID: ${t.bssid} | SSID: ${t.ssid}")
                            appendLine("   Details: ${t.description}")
                        }
                    }
                }
                makeToolSuccessResponse(id, text)
            }

            "wscan_analyze_threats" -> {
                val db = AppDatabase.getInstance(context)
                val threats = db.threatLogDao().getRecent(50)
                val text = if (threats.isEmpty()) {
                    "No threat logs currently in database to analyze."
                } else {
                    val grouped = threats.groupBy { it.attackType }
                    buildString {
                        appendLine("=== WSCAN+ THREAT ASSESSMENT ===")
                        appendLine("Total events: ${threats.size}")
                        appendLine("Attack types detected:")
                        grouped.forEach { (type, list) ->
                            appendLine("  - $type: ${list.size} occurrences")
                        }
                        appendLine("\nRemediation Strategy:")
                        if (grouped.containsKey("EVIL_TWIN")) {
                            appendLine("  • EVIL TWIN: Enable 802.11w Protected Management Frames (PMF). Avoid unauthenticated captive portals.")
                        }
                        if (grouped.containsKey("DEAUTH_FLOOD")) {
                            appendLine("  • DEAUTH FLOOD: Active jamming attempt detected. Relocate to 5GHz/6GHz band or isolate rogue transmitter MAC.")
                        }
                        if (grouped.containsKey("KRACK")) {
                            appendLine("  • KRACK: WPA2 4-way handshake nonce retransmission detected. Ensure client and AP firmware patches are applied.")
                        }
                        if (grouped.containsKey("PMKID_SNIFFING")) {
                            appendLine("  • PMKID SNIFFING: Upgrade to WPA3-SAE to prevent offline dictionary/hashcat recovery of passphrase.")
                        }
                    }
                }
                makeToolSuccessResponse(id, text)
            }

            "wscan_system_status" -> {
                val pixelAvf = IntegrationsManager.getPixelAvfInfo(context)
                val shizuku = IntegrationsManager.getShizukuStatus(context)
                val root = IntegrationsManager.getRootInfo(context)
                val adb = IntegrationsManager.getAdbInfo(context)

                val text = buildString {
                    appendLine("=== WSCAN+ SYSTEM & INTEGRATIONS STATUS ===")
                    appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${pixelAvf.androidVersion})")
                    appendLine("Pixel AVF Terminal: ${if (pixelAvf.isTerminalAppInstalled) "Installed & Ready" else pixelAvf.description}")
                    appendLine("Shizuku ADB Service: ${shizuku.statusMessage}")
                    appendLine("Root Access: ${if (root.isSuBinaryFound) "Detected (${root.rootManager ?: "su"})" else "None"}")
                    appendLine("Wireless Debugging: ${if (adb.isWirelessAdbEnabled) "Active" else "Inactive"}")
                }
                makeToolSuccessResponse(id, text)
            }

            "avf_detect_tools" -> {
                val tools = AvfToolchainDetector.tools.value
                val text = buildString {
                    appendLine("=== AVF & LINUX SECURITY TOOLCHAIN AUDIT ===")
                    tools.forEach { tool ->
                        val status = when {
                            tool.isRunning -> "[ACTIVE DAEMON - PORT ${tool.defaultPort}]"
                            tool.isDetected -> "[INSTALLED: ${tool.binaryPath ?: "PATH"}]"
                            else -> "[NOT DETECTED]"
                        }
                        appendLine("${tool.name} ($status)")
                        appendLine("  Team: ${tool.team.label} | Category: ${tool.category.label}")
                        if (tool.versionInfo != null) appendLine("  Version: ${tool.versionInfo}")
                        appendLine("  Cmd: ${tool.command} ${tool.recommendedArgs}")
                    }
                }
                makeToolSuccessResponse(id, text)
            }

            "avf_usb_sdr_config" -> {
                AvfHardwareBridge.probeUsbSdr(context)
                val state = AvfHardwareBridge.sdrState.value
                val text = buildString {
                    appendLine("=== AVF USB PASSTHROUGH & SDR STATUS ===")
                    appendLine("Discovered USB Devices: ${state.connectedDevices.size}")
                    state.connectedDevices.forEach { dev ->
                        appendLine("  • ${dev.deviceName} [${dev.combinedId}] (SDR: ${dev.isRecognizedSdr})")
                    }
                    appendLine("\nGenerated vm_config.json block:")
                    appendLine(state.generatedVmConfigJson)
                    appendLine("\nVerified Boot Allow-List Gate:")
                    appendLine(state.gateExplanation)
                    appendLine("\nUserspace RTL-TCP Relay: ${if (state.isRtlTcpRelayActive) "Running on port ${state.rtlTcpPort}" else "Inactive"}")
                }
                makeToolSuccessResponse(id, text)
            }

            "avf_hardware_telemetry" -> {
                val gps = AvfHardwareBridge.gpsState.value
                val kismet = AvfHardwareBridge.kismetTelemetry.value
                val text = buildString {
                    appendLine("=== BI-DIRECTIONAL AVF TELEMETRY STATUS ===")
                    appendLine("Forward GPS NMEA Bridge (Android -> AVF Kismet):")
                    appendLine("  Running: ${gps.isRunning} on port ${gps.port}")
                    appendLine("  Connected AVF Clients: ${gps.connectedClients}")
                    appendLine("  Last Coordinates: Lat ${gps.lastLatitude}, Lon ${gps.lastLongitude}, Alt ${gps.lastAltitudeMeters}m")
                    appendLine("  Sentences Broadcast: ${gps.sentencesSent}")
                    appendLine("\nReverse Kismet Telemetry (AVF Kismet -> Android UI):")
                    appendLine("  Server: ${if (kismet.isConnected) "${kismet.serverName} (${kismet.endpointUrl})" else "Disconnected / Not Running"}")
                    appendLine("  Devices Logged: ${kismet.deviceCount} | Packets: ${kismet.packetCount}")
                    appendLine("  Active Alerts: ${kismet.activeAlertsCount}")
                    if (kismet.recentAlerts.isNotEmpty()) {
                        appendLine("  Recent Alerts:")
                        kismet.recentAlerts.take(3).forEach { appendLine("    - $it") }
                    }
                }
                makeToolSuccessResponse(id, text)
            }

            "wscan_ingest_deauth" -> {
                val src = args.optString("sourceMac", "")
                val dest = args.optString("destMac", "ff:ff:ff:ff:ff:ff")
                val bssid = args.optString("bssid", src)
                val reason = args.optInt("reasonCode", 7)
                if (src.isBlank()) {
                    return makeRpcError(id, -32602, "sourceMac cannot be blank")
                }
                val frame = AttackHeuristics.DeauthFrame(
                    sourceMac = src,
                    destMac = dest,
                    bssid = bssid,
                    reasonCode = reason,
                    timestampMs = System.currentTimeMillis()
                )
                com.wscanplus.android.service.WifiScanningService.ingestDeauthFrame(frame)
                makeToolSuccessResponse(id, "Deauth frame ingested for BSSID $bssid targeting $dest (reason: $reason).")
            }

            "wscan_ingest_eapol" -> {
                val src = args.optString("sourceMac", "")
                val bssid = args.optString("bssid", "")
                val msgNum = args.optInt("messageNumber", 1)
                val replay = args.optLong("replayCounter", 1L)
                if (src.isBlank() || bssid.isBlank()) {
                    return makeRpcError(id, -32602, "sourceMac and bssid are required")
                }
                val frame = AttackHeuristics.EapolFrame(
                    sourceMac = src,
                    bssid = bssid,
                    messageNumber = msgNum,
                    replayCounter = replay,
                    nonce = ByteArray(32),
                    timestampMs = System.currentTimeMillis()
                )
                com.wscanplus.android.service.WifiScanningService.ingestEapolFrame(frame)
                makeToolSuccessResponse(id, "EAPOL handshake message $msgNum ingested for BSSID $bssid.")
            }

            else -> makeRpcError(id, -32601, "Unknown tool: $toolName")
        }
        val latencyMs = System.currentTimeMillis() - startMs
        val isSuccess = !result.contains("\"error\":")
        recordActivity(
            clientName = "CLI Agent",
            toolName = toolName,
            latencyMs = latencyMs,
            status = if (isSuccess) "ok" else "error",
            summary = "Args: ${args.toString().take(60)}"
        )
        return result
    }

    private fun makeToolSuccessResponse(id: Any?, contentText: String): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", JSONObject().apply {
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", contentText)
                    })
                })
            })
        }.toString()
    }

    private fun makeRpcError(id: Any?, code: Int, message: String): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }.toString()
    }

    private fun sendResponse(
        out: OutputStreamWriter,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String,
        requestOrigin: String? = null
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 $statusCode $statusText\r\n")
        out.write("Content-Type: $contentType; charset=utf-8\r\n")
        out.write("Content-Length: ${bytes.size}\r\n")
        if (!requestOrigin.isNullOrBlank() && (requestOrigin.startsWith("http://localhost") || requestOrigin.startsWith("http://127.0.0.1") || requestOrigin.startsWith("https://localhost"))) {
            out.write("Access-Control-Allow-Origin: $requestOrigin\r\n")
        } else {
            out.write("Access-Control-Allow-Origin: http://127.0.0.1\r\n")
        }
        out.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        out.write("Access-Control-Allow-Headers: Content-Type, Authorization, X-MCP-Token\r\n")
        out.write("Connection: close\r\n")
        out.write("\r\n")
        out.write(body)
        out.flush()
    }

    companion object {
        private const val TAG = "AgentMcpServer"

        /**
         * Resolves the primary local IPv4 address of the device on Wi-Fi or LAN.
         */
        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress
                            if (!host.isNullOrBlank() && !host.startsWith("127.")) {
                                return host
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve IP: ${e.message}")
            }
            return "127.0.0.1"
        }
    }
}
