package com.wscanplus.android.core

import android.content.Context
import android.util.Log
import com.wscanplus.android.data.db.ThreatLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AiProviderManager – Unified AI threat assessment and interactive agent engine.
 *
 * Supports:
 * 1. Google Gemini Cloud API
 * 2. On-Device Pixel AVF Linux VM LLM / Agent (via AVF bridge or localhost)
 * 3. Custom OpenAI-Compatible HTTP endpoint (Ollama, LM Studio, DeepSeek, vLLM)
 * 4. Deterministic Local Security Heuristic Engine (100% offline, zero-network)
 */
class AiProviderManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    data class AiAssessment(
        val providerName: String,
        val threatLevel: String, // CRITICAL, HIGH, MEDIUM, LOW, CLEAN
        val executiveSummary: String,
        val fullAnalysis: String,
        val mitigations: List<String>,
        val latencyMs: Long,
        val isLocal: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class ProviderType(val id: String, val displayName: String, val isLocal: Boolean) {
        OFFLINE_RULES(AppPreferences.AI_PROVIDER_OFFLINE_RULES, "Local Heuristic Engine (Offline)", true),
        LOCAL_AVF(AppPreferences.AI_PROVIDER_LOCAL_AVF, "Pixel AVF Linux Agent / LLM", true),
        OPENAI_COMPATIBLE(AppPreferences.AI_PROVIDER_OPENAI, "OpenAI-Compatible (Ollama / Local)", false),
        GEMINI(AppPreferences.AI_PROVIDER_GEMINI, "Google Gemini Cloud AI", false);

        companion object {
            fun fromId(id: String): ProviderType =
                values().firstOrNull { it.id == id } ?: OFFLINE_RULES
        }
    }

    /**
     * Run threat assessment using the selected or preferred AI provider.
     */
    suspend fun analyzeThreats(
        logs: List<ThreatLog>,
        overrideProvider: ProviderType? = null
    ): AiAssessment = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val prefs = AppPreferences.main(context)
        val providerId = prefs.getString(AppPreferences.KEY_AI_PROVIDER, AppPreferences.AI_PROVIDER_OFFLINE_RULES)
            ?: AppPreferences.AI_PROVIDER_OFFLINE_RULES
        val provider = overrideProvider ?: ProviderType.fromId(providerId)

        if (logs.isEmpty()) {
            return@withContext AiAssessment(
                providerName = provider.displayName,
                threatLevel = "CLEAN",
                executiveSummary = "No threat vectors logged. Environment is clear.",
                fullAnalysis = "The wscan+ IDS database contains no active threat entries. Ongoing radio scans are nominal with no detected rogue beacons, deauth floods, PMKID anomalies, or KRACK replay attempts.",
                mitigations = listOf(
                    "Maintain active background monitoring.",
                    "Ensure 802.11w Protected Management Frames (PMF) are enabled on trusted APs."
                ),
                latencyMs = System.currentTimeMillis() - startMs,
                isLocal = true
            )
        }

        try {
            when (provider) {
                ProviderType.OFFLINE_RULES -> evaluateLocalRules(logs, startMs)
                ProviderType.GEMINI -> evaluateGemini(logs, startMs)
                ProviderType.LOCAL_AVF -> evaluateLocalAvf(logs, startMs)
                ProviderType.OPENAI_COMPATIBLE -> evaluateOpenAiCompatible(logs, startMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating with provider ${provider.displayName}, falling back to local rules", e)
            val fallback = evaluateLocalRules(logs, startMs)
            fallback.copy(
                fullAnalysis = "Note: ${provider.displayName} was unreachable (${e.message ?: "network error"}). Defaulting to on-device heuristic engine.\n\n${fallback.fullAnalysis}"
            )
        }
    }

    /**
     * Interactive chat with the selected AI agent (for mascot dialog or terminal CLI).
     */
    suspend fun queryAgent(userMessage: String, recentLogs: List<ThreatLog>): String = withContext(Dispatchers.IO) {
        val prefs = AppPreferences.main(context)
        val providerId = prefs.getString(AppPreferences.KEY_AI_PROVIDER, AppPreferences.AI_PROVIDER_OFFLINE_RULES)
            ?: AppPreferences.AI_PROVIDER_OFFLINE_RULES
        val provider = ProviderType.fromId(providerId)

        val logSummary = if (recentLogs.isEmpty()) "No active threats." else {
            recentLogs.take(5).joinToString("; ") { "[${it.severity}] ${it.attackType} on ${it.ssid.ifBlank { it.bssid }}" }
        }

        when (provider) {
            ProviderType.OFFLINE_RULES -> {
                queryLocalRuleAgent(userMessage, recentLogs)
            }
            ProviderType.GEMINI -> {
                val apiKey = prefs.getString(AppPreferences.KEY_GEMINI_KEY, "") ?: ""
                if (apiKey.isBlank()) {
                    queryLocalRuleAgent(userMessage, recentLogs)
                } else {
                    val prompt = """
                        You are Nyx, the tactical AI cybersecurity mascot and wireless analyst for the wscan+ IDS application.
                        Current threat environment: $logSummary
                        User: $userMessage
                        Respond concisely (2-4 sentences max), tactical, knowledgeable, and helpful.
                    """.trimIndent()
                    val gemini = GeminiApiService(apiKey)
                    gemini.analyzeThreatLogs(recentLogs) ?: queryLocalRuleAgent(userMessage, recentLogs)
                }
            }
            ProviderType.LOCAL_AVF, ProviderType.OPENAI_COMPATIBLE -> {
                queryOpenAiHttp(userMessage, logSummary) ?: queryLocalRuleAgent(userMessage, recentLogs)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Provider Implementations
    // ──────────────────────────────────────────────────────────────────────────

    private fun evaluateLocalRules(logs: List<ThreatLog>, startMs: Long): AiAssessment {
        val grouped = logs.groupBy { it.attackType }
        val severities = logs.map { it.severity.uppercase() }

        val threatLevel = when {
            severities.contains("CRITICAL") -> "CRITICAL"
            severities.contains("HIGH") -> "HIGH"
            severities.contains("MEDIUM") -> "MEDIUM"
            else -> "LOW"
        }

        val totalEvents = logs.size
        val uniqueBssids = logs.map { it.bssid }.distinct().size
        val uniqueSsids = logs.mapNotNull { it.ssid.takeIf { s -> s.isNotBlank() } }.distinct()

        val mitigations = mutableListOf<String>()
        val analysis = StringBuilder()

        analysis.appendLine("=== WSCAN+ REAL-TIME HEURISTIC AUDIT ===")
        analysis.appendLine("Telemetry Summary: $totalEvents intrusion events recorded across $uniqueBssids target BSSIDs.")
        if (uniqueSsids.isNotEmpty()) {
            analysis.appendLine("Impacted Networks: ${uniqueSsids.joinToString(", ")}")
        }
        analysis.appendLine("Threat Vectors Identified:")

        if (grouped.containsKey("EVIL_TWIN")) {
            val count = grouped["EVIL_TWIN"]?.size ?: 0
            analysis.appendLine(" • [HIGH] Evil Twin / Rogue AP ($count occurrences): Unsanctioned transmitter mirroring legitimate ESSID detected with mismatched OUI/vendor or signal divergence.")
            mitigations.add("Isolate rogue AP transmitter using directional signal / RSSI scanner.")
            mitigations.add("Enforce WPA3-SAE or 802.1X certificate validation to prevent rogue association.")
        }

        if (grouped.containsKey("DEAUTH_FLOOD")) {
            val count = grouped["DEAUTH_FLOOD"]?.size ?: 0
            analysis.appendLine(" • [HIGH] 802.11 Deauth Jamming ($count bursts): Rapid broadcast/unicast deauthentication storm attempting to forcibly disconnect clients.")
            mitigations.add("Enable 802.11w Management Frame Protection (MFP) on the access point.")
            mitigations.add("Relocate communication to 5 GHz or 6 GHz channels less prone to legacy deauth tools.")
        }

        if (grouped.containsKey("PMKID_SNIFFING")) {
            val count = grouped["PMKID_SNIFFING"]?.size ?: 0
            analysis.appendLine(" • [MEDIUM] PMKID EAPOL Sniffing ($count frames): Attacker captured Robust Security Network (RSN) information element for offline dictionary recovery.")
            mitigations.add("Upgrade pre-shared key (PSK) to 20+ character alphanumeric passphrase.")
            mitigations.add("Transition to WPA3-Personal (Simultaneous Authentication of Equals) to neutralize PMKID hash attacks.")
        }

        if (grouped.containsKey("KRACK")) {
            val count = grouped["KRACK"]?.size ?: 0
            analysis.appendLine(" • [HIGH] Key Reinstallation (KRACK) Attack ($count nonce replays): Detected replay counter anomaly in WPA2 4-Way handshake state machine.")
            mitigations.add("Verify client operating system and access point firmware security patches.")
        }

        if (mitigations.isEmpty()) {
            mitigations.add("Continue passive monitoring across 2.4 GHz, 5 GHz, and 6 GHz spectrum.")
            mitigations.add("Review network access lists and disable WPS (Wi-Fi Protected Setup).")
        }

        val executiveSummary = "Detected $totalEvents events. Overall risk level: $threatLevel. Primary vectors: ${grouped.keys.joinToString(", ")}."

        return AiAssessment(
            providerName = "Deterministic Heuristic Engine (Offline)",
            threatLevel = threatLevel,
            executiveSummary = executiveSummary,
            fullAnalysis = analysis.toString().trim(),
            mitigations = mitigations,
            latencyMs = System.currentTimeMillis() - startMs,
            isLocal = true
        )
    }

    private suspend fun evaluateGemini(logs: List<ThreatLog>, startMs: Long): AiAssessment {
        val prefs = AppPreferences.main(context)
        val apiKey = prefs.getString(AppPreferences.KEY_GEMINI_KEY, "") ?: ""
        if (apiKey.isBlank()) {
            val local = evaluateLocalRules(logs, startMs)
            return local.copy(
                fullAnalysis = "No Gemini API key configured. Evaluated with local heuristic engine:\n\n${local.fullAnalysis}"
            )
        }

        val service = GeminiApiService(apiKey)
        val text = service.analyzeThreatLogs(logs)
            ?: throw IllegalStateException("Gemini API call returned null or failed.")

        val threatLevel = when {
            text.contains("Critical", ignoreCase = true) -> "CRITICAL"
            text.contains("High", ignoreCase = true) -> "HIGH"
            text.contains("Medium", ignoreCase = true) -> "MEDIUM"
            else -> "LOW"
        }

        val mitigations = extractMitigations(text)

        return AiAssessment(
            providerName = "Google Gemini 1.5 Flash",
            threatLevel = threatLevel,
            executiveSummary = "AI assessment complete. Threat status: $threatLevel.",
            fullAnalysis = text,
            mitigations = mitigations,
            latencyMs = System.currentTimeMillis() - startMs,
            isLocal = false
        )
    }

    private suspend fun evaluateLocalAvf(logs: List<ThreatLog>, startMs: Long): AiAssessment {
        val prefs = AppPreferences.main(context)
        val endpoint = prefs.getString(AppPreferences.KEY_AVF_ENDPOINT, AppPreferences.DEFAULT_AVF_ENDPOINT)
            ?: AppPreferences.DEFAULT_AVF_ENDPOINT

        // Check if an HTTP LLM server is answering in AVF / localhost, or if we can run command in AVF
        val prompt = buildThreatPrompt(logs)
        val response = tryOpenAiCall(
            baseUrl = if (endpoint.endsWith("/v1")) endpoint else "$endpoint/v1",
            apiKey = "",
            model = "llama3.2",
            prompt = prompt
        )

        if (response != null) {
            val threatLevel = parseThreatLevel(response)
            return AiAssessment(
                providerName = "Pixel AVF On-Device LLM ($endpoint)",
                threatLevel = threatLevel,
                executiveSummary = "Local AVF container completed neural threat evaluation.",
                fullAnalysis = response,
                mitigations = extractMitigations(response),
                latencyMs = System.currentTimeMillis() - startMs,
                isLocal = true
            )
        }

        // If HTTP endpoint inside AVF was not running, evaluate via local rules with AVF info
        val local = evaluateLocalRules(logs, startMs)
        return local.copy(
            providerName = "Pixel AVF Heuristic Engine",
            fullAnalysis = "AVF Container Bridge active. Local neural model offline at $endpoint. Executed on-device heuristic parser:\n\n${local.fullAnalysis}"
        )
    }

    private suspend fun evaluateOpenAiCompatible(logs: List<ThreatLog>, startMs: Long): AiAssessment {
        val prefs = AppPreferences.main(context)
        val baseUrl = prefs.getString(AppPreferences.KEY_OPENAI_BASE_URL, AppPreferences.DEFAULT_OPENAI_BASE_URL)
            ?: AppPreferences.DEFAULT_OPENAI_BASE_URL
        val model = prefs.getString(AppPreferences.KEY_OPENAI_MODEL, AppPreferences.DEFAULT_OPENAI_MODEL)
            ?: AppPreferences.DEFAULT_OPENAI_MODEL
        val apiKey = prefs.getString(AppPreferences.KEY_OPENAI_API_KEY, "") ?: ""

        val prompt = buildThreatPrompt(logs)
        val response = tryOpenAiCall(baseUrl, apiKey, model, prompt)
            ?: throw IllegalStateException("OpenAI-compatible endpoint at $baseUrl failed to respond.")

        val threatLevel = parseThreatLevel(response)

        return AiAssessment(
            providerName = "OpenAI-Compatible ($model @ ${baseUrl.take(28)})",
            threatLevel = threatLevel,
            executiveSummary = "Custom AI endpoint parsed ${logs.size} threat events. Threat level: $threatLevel.",
            fullAnalysis = response,
            mitigations = extractMitigations(response),
            latencyMs = System.currentTimeMillis() - startMs,
            isLocal = baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")
        )
    }

    private fun tryOpenAiCall(baseUrl: String, apiKey: String, model: String, prompt: String): String? {
        val cleanUrl = baseUrl.trimEnd('/')
        val url = if (cleanUrl.endsWith("/chat/completions")) cleanUrl else "$cleanUrl/chat/completions"

        val bodyJson = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are an expert wireless network intrusion detection analyst for wscan+. Be concise and direct.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.3)
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        val resp = client.newCall(requestBuilder.build()).execute()
        if (!resp.isSuccessful) {
            Log.w(TAG, "OpenAI HTTP call error ${resp.code}: ${resp.message}")
            return null
        }

        val respString = resp.body?.string() ?: return null
        val json = JSONObject(respString)
        val choices = json.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        return choices.getJSONObject(0).optJSONObject("message")?.optString("content")
    }

    private fun queryOpenAiHttp(userMsg: String, contextLog: String): String? {
        val prefs = AppPreferences.main(context)
        val baseUrl = prefs.getString(AppPreferences.KEY_OPENAI_BASE_URL, AppPreferences.DEFAULT_OPENAI_BASE_URL)
            ?: AppPreferences.DEFAULT_OPENAI_BASE_URL
        val model = prefs.getString(AppPreferences.KEY_OPENAI_MODEL, AppPreferences.DEFAULT_OPENAI_MODEL)
            ?: AppPreferences.DEFAULT_OPENAI_MODEL
        val apiKey = prefs.getString(AppPreferences.KEY_OPENAI_API_KEY, "") ?: ""

        val prompt = "Context: $contextLog\nUser: $userMsg\nRespond as Nyx, the tactical wscan+ security mascot."
        return tryOpenAiCall(baseUrl, apiKey, model, prompt)
    }

    private fun queryLocalRuleAgent(userMsg: String, logs: List<ThreatLog>): String {
        val msg = userMsg.lowercase()
        return when {
            msg.contains("status") || msg.contains("check") -> {
                if (logs.isEmpty()) "Environment is all clear! Passive 802.11 monitoring active. No anomalies detected."
                else "Warning: ${logs.size} active threat frames detected in current buffer. Highest severity: ${logs.maxOfOrNull { it.severity } ?: "UNKNOWN"}."
            }
            msg.contains("evil") || msg.contains("twin") || msg.contains("rogue") -> {
                "Evil Twin detection active: comparing BSSID OUI prefixes, signal delta, and encryption flags against legitimate APs. Check Alerts tab for rogue transmitters."
            }
            msg.contains("deauth") || msg.contains("jam") -> {
                "Deauth flood protection: frames are monitored for burst rates exceeding 15 frames/sec. Ensure 802.11w Protected Management Frames (PMF) are enabled."
            }
            msg.contains("pmkid") || msg.contains("handshake") -> {
                "PMKID sniffer protection: monitors initial EAPOL frame 1 captures. Recommended defense: migrate to WPA3-SAE to neutralize hashcat dictionary attacks."
            }
            msg.contains("help") || msg.contains("nyx") -> {
                "I'm Nyx, your wscan+ security companion! I monitor wireless signals, diagnose AVF & Shizuku backends, and assist in threat countermeasures. Ask me about your network health or threat mitigations!"
            }
            else -> {
                if (logs.isEmpty()) {
                    "Systems nominal. Radio interfaces listening on active channels. Ask me about Evil Twins, Deauth attacks, PMKID, or run a diagnostic in Integrations."
                } else {
                    "Current analysis indicates active ${logs.first().attackType} targeting ${logs.first().ssid.ifBlank { logs.first().bssid }}. Review the Mitigations checklist below to protect your clients."
                }
            }
        }
    }

    private fun buildThreatPrompt(logs: List<ThreatLog>): String {
        val summary = logs.take(20).joinToString("\n") {
            "- [${it.severity}] ${it.attackType} | BSSID: ${it.bssid} | SSID: ${it.ssid} | ${it.description}"
        }
        return """
            Analyze these Wi-Fi security intrusion events from wscan+:
            $summary

            Provide:
            1. Threat Summary
            2. Threat Level (CRITICAL / HIGH / MEDIUM / LOW)
            3. Recommended Operator Mitigations
        """.trimIndent()
    }

    private fun parseThreatLevel(text: String): String {
        return when {
            text.contains("CRITICAL", ignoreCase = true) -> "CRITICAL"
            text.contains("HIGH", ignoreCase = true) -> "HIGH"
            text.contains("MEDIUM", ignoreCase = true) -> "MEDIUM"
            else -> "LOW"
        }
    }

    private fun extractMitigations(text: String): List<String> {
        val lines = text.lines()
        val results = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("•") || trimmed.startsWith("-") || trimmed.matches(Regex("^\\d+\\..*"))) {
                val clean = trimmed.replace(Regex("^[•\\-\\d\\.]+\\s*"), "")
                if (clean.length > 10 && !clean.contains("Summary", ignoreCase = true)) {
                    results.add(clean)
                }
            }
        }
        return if (results.isNotEmpty()) results.take(4) else listOf(
            "Enable 802.11w Protected Management Frames (PMF) on access points.",
            "Inspect physical perimeter for unsanctioned access points broadcasting identical SSIDs.",
            "Verify all network clients use updated security patches and WPA3 authentication."
        )
    }

    companion object {
        private const val TAG = "AiProviderManager"
    }
}
