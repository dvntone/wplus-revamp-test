package com.wscanplus.android.core

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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * GeminiApiService – sends scan summaries to the Google Gemini API for
 * natural-language threat assessment.
 *
 * Users must supply their own API key (stored in app preferences, never
 * hard-coded).
 *
 * API reference: https://ai.google.dev/api/generate-content
 */
class GeminiApiService(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val dateFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("UTC"))

    /**
     * Analyse a list of threat logs using Gemini and return the model's
     * natural-language assessment.
     *
     * @param logs     threat events to analyse (up to [MAX_LOGS] entries are sent)
     * @param model    Gemini model identifier (default: gemini-1.5-flash)
     * @return AI-generated assessment string, or null on failure
     */
    suspend fun analyzeThreatLogs(
        logs: List<ThreatLog>,
        model: String = DEFAULT_MODEL,
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "Gemini API key is not set")
            return@withContext null
        }

        val trimmedLogs = logs.takeLast(MAX_LOGS)
        val csvSummary = buildCsvSummary(trimmedLogs)

        val prompt = """
            You are a cybersecurity expert specialising in 802.11 wireless networks.
            Analyse the following Wi-Fi threat log exported from the wscan+ IDS application
            and provide a concise assessment:
            1. Summarise the detected attacks.
            2. Rate the overall threat level (Low / Medium / High / Critical).
            3. Suggest immediate mitigation actions for the user.

            === THREAT LOG (CSV) ===
            $csvSummary
            =======================
        """.trimIndent()

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val url = "$GEMINI_BASE_URL/models/$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        return@withContext try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini API error ${response.code}: ${response.message}")
                return@withContext null
            }
            val json = JSONObject(response.body?.string() ?: return@withContext null)
            json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API call failed", e)
            null
        }
    }

    private fun buildCsvSummary(logs: List<ThreatLog>): String {
        val sb = StringBuilder("timestamp,attack_type,bssid,ssid,severity,description\n")
        for (log in logs) {
            sb.append(
                "${dateFormat.format(Instant.ofEpochMilli(log.timestamp))}," +
                    "${log.attackType}," +
                    "${log.bssid}," +
                    "${log.ssid}," +
                    "${log.severity}," +
                    "\"${log.description.replace("\"", "\"\"")}\"\n"
            )
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "GeminiApiService"
        private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val DEFAULT_MODEL = "gemini-1.5-flash"
        private const val MAX_LOGS = 100
    }
}
