package com.wscanplus.android.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * UpdateChecker – checks for a newer wscan+ release on GitHub and verifies
 * that the new release carries a build-confirmed asset before advertising it.
 *
 * Two checks are performed:
 *  1. **Version check** – compares [CURRENT_VERSION] against the latest tag on
 *     the GitHub releases API.
 *  2. **Build-confirmation check** – the release must contain a
 *     `build-confirmed.txt` asset; this file is uploaded by CI only after all
 *     tests pass.
 *
 * An optional connectivity guard returns [UpdateStatus.OFFLINE] immediately
 * when [SystemStatusChecker.hasInternetConnectivity] returns false.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /** Current app version — keep in sync with build.gradle versionName. */
    const val CURRENT_VERSION = "1.0.0"

    private const val RELEASES_URL =
        "https://api.github.com/repos/dvntone/wscanPLUS/releases/latest"

    private const val BUILD_CONFIRMED_ASSET = "build-confirmed.txt"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS    = 15_000

    // ── Public API ────────────────────────────────────────────────────────────

    enum class UpdateStatus {
        OFFLINE,
        ERROR,
        UP_TO_DATE,
        /** Newer version found but missing build-confirmed asset. */
        UPDATE_AVAILABLE_UNCONFIRMED,
        /** Newer confirmed build available; safe to update. */
        UPDATE_AVAILABLE,
    }

    data class UpdateResult(
        val status: UpdateStatus,
        val currentVersion: String,
        val latestVersion: String?,
        val releaseUrl: String?,
        val releaseNotes: String?,
    )

    /**
     * Perform a full update check on [Dispatchers.IO].
     * Never throws; errors are returned as [UpdateStatus.ERROR].
     */
    suspend fun check(context: Context): UpdateResult = withContext(Dispatchers.IO) {
        // 1. Connectivity guard
        if (!SystemStatusChecker.hasInternetConnectivity(context)) {
            return@withContext UpdateResult(
                status = UpdateStatus.OFFLINE,
                currentVersion = CURRENT_VERSION,
                latestVersion = null,
                releaseUrl = null,
                releaseNotes = null,
            )
        }

        // 2. Fetch latest release
        return@withContext try {
            val conn = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout    = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "wscanPlus-Android/${CURRENT_VERSION}")
            }

            conn.inputStream.use { stream ->
                val json = JSONObject(stream.bufferedReader(Charsets.UTF_8).readText())
                val tagName  = json.optString("tag_name", "")
                val htmlUrl  = json.optString("html_url", "")
                val body     = json.optString("body", "")
                val assets   = json.optJSONArray("assets")

                val latestVer = tagName.trimStart('v', 'V').split("-").first()

                if (!isNewer(latestVer, CURRENT_VERSION)) {
                    return@use UpdateResult(
                        status = UpdateStatus.UP_TO_DATE,
                        currentVersion = CURRENT_VERSION,
                        latestVersion = latestVer,
                        releaseUrl = htmlUrl,
                        releaseNotes = body,
                    )
                }

                // 3. Build-confirmation asset check
                val confirmed = (0 until (assets?.length() ?: 0)).any { i ->
                    assets?.getJSONObject(i)?.optString("name") == BUILD_CONFIRMED_ASSET
                }

                UpdateResult(
                    status = if (confirmed) UpdateStatus.UPDATE_AVAILABLE
                             else           UpdateStatus.UPDATE_AVAILABLE_UNCONFIRMED,
                    currentVersion = CURRENT_VERSION,
                    latestVersion  = latestVer,
                    releaseUrl     = htmlUrl,
                    releaseNotes   = body,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            UpdateResult(
                status = UpdateStatus.ERROR,
                currentVersion = CURRENT_VERSION,
                latestVersion = null,
                releaseUrl = null,
                releaseNotes = e.message,
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns true when [candidate] is strictly newer than [current] (semver). */
    private fun isNewer(candidate: String, current: String): Boolean {
        return try {
            val c = candidate.split(".").map { it.toIntOrNull() ?: 0 }
            val v = current.split(".").map   { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(c.size, v.size)) {
                val a = c.getOrElse(i) { 0 }
                val b = v.getOrElse(i) { 0 }
                if (a > b) return true
                if (a < b) return false
            }
            false // equal
        } catch (_: Exception) { false }
    }
}
