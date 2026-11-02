package com.wscanplus.android.core

import android.content.Context
import android.content.SharedPreferences

/**
 * AppPreferences – centralised SharedPreferences key registry and typed
 * accessors for all wscan+ settings.
 *
 * All feature flags, numeric parameters, and user-preference strings are
 * stored here so screens and services can read from a single source of truth.
 *
 * Naming convention: `KEY_*` for prefs keys, `DEFAULT_*` for default values.
 */
object AppPreferences {

    // ── Prefs file names ──────────────────────────────────────────────────────
    const val PREFS_MAIN      = "wscanplus_prefs"
    const val PREFS_FEATURES  = "wscanplus_features"

    // ── Cloud / companion ─────────────────────────────────────────────────────
    const val KEY_FIREBASE_URL   = "firebase_url"
    const val KEY_DEVICE_ID      = "device_id"
    const val KEY_GEMINI_KEY     = "gemini_api_key"
    const val KEY_DESKTOP_HOST   = "desktop_host"

    // ── Scan parameters ───────────────────────────────────────────────────────
    /** Interval between scan cycles in milliseconds. Default 5 000 ms. */
    const val KEY_SCAN_INTERVAL_MS    = "scan_interval_ms"
    const val DEFAULT_SCAN_INTERVAL_MS = 5_000L

    /** Maximum duration of a single scan attempt in milliseconds. Default 8 000 ms. */
    const val KEY_SCAN_TIMEOUT_MS     = "scan_timeout_ms"
    const val DEFAULT_SCAN_TIMEOUT_MS = 8_000L

    // ── Feature toggles ───────────────────────────────────────────────────────
    const val KEY_FEATURE_EVIL_TWIN       = "feature_evil_twin"
    const val KEY_FEATURE_DEAUTH_FLOOD    = "feature_deauth_flood"
    const val KEY_FEATURE_PMKID           = "feature_pmkid"
    const val KEY_FEATURE_KRACK           = "feature_krack"
    const val KEY_FEATURE_FIREBASE_SYNC   = "feature_firebase_sync"
    const val KEY_FEATURE_GEMINI_AI       = "feature_gemini_ai"
    const val KEY_FEATURE_COMPANION_RELAY = "feature_companion_relay"
    const val KEY_FEATURE_NEXMON          = "feature_nexmon"
    const val KEY_FEATURE_SHIZUKU         = "feature_shizuku"
    const val KEY_FEATURE_ROOT_SCANNER    = "feature_root_scanner"
    const val KEY_FEATURE_USB_WIFI        = "feature_usb_wifi"
    const val KEY_FEATURE_LINUX_ENV       = "feature_linux_env"
    const val KEY_FEATURE_AUTO_UPDATE     = "feature_auto_update"

    // ── Terminal preference ───────────────────────────────────────────────────
    const val KEY_PREFERRED_TERMINAL      = "preferred_terminal"
    const val TERMINAL_TERMUX             = "termux"
    const val TERMINAL_TMUX               = "tmux"
    const val TERMINAL_DEFAULT            = "default"

    // ── Android Linux environment ─────────────────────────────────────────────
    const val KEY_LINUX_ENV_DISTRO        = "linux_env_distro"
    const val DEFAULT_LINUX_ENV_DISTRO    = "debian"

    // ── AI Provider & Presentation ────────────────────────────────────────────
    const val KEY_AI_PROVIDER             = "ai_provider"
    const val AI_PROVIDER_OFFLINE_RULES   = "offline_rules"
    const val AI_PROVIDER_GEMINI          = "gemini"
    const val AI_PROVIDER_LOCAL_AVF       = "local_avf"
    const val AI_PROVIDER_OPENAI          = "openai_compatible"

    const val KEY_AI_UI_MODE              = "ai_ui_mode"
    const val AI_UI_MODE_MASCOT           = "mascot"
    const val AI_UI_MODE_FEED             = "feed"
    const val AI_UI_MODE_TERMINAL         = "terminal"

    const val KEY_OPENAI_BASE_URL         = "openai_base_url"
    const val DEFAULT_OPENAI_BASE_URL     = "http://127.0.0.1:11434/v1"

    const val KEY_OPENAI_MODEL            = "openai_model"
    const val DEFAULT_OPENAI_MODEL        = "llama3.2"

    const val KEY_OPENAI_API_KEY          = "openai_api_key"

    const val KEY_AVF_ENDPOINT            = "avf_endpoint"
    const val DEFAULT_AVF_ENDPOINT        = "http://127.0.0.1:8080"

    // ── MCP / Agent Server Security ──────────────────────────────────────────
    const val KEY_MCP_ALLOW_REMOTE        = "mcp_allow_remote_connections"
    const val DEFAULT_MCP_ALLOW_REMOTE    = false

    const val KEY_MCP_ALLOW_ROOT          = "mcp_allow_root_execution"
    const val DEFAULT_MCP_ALLOW_ROOT      = false

    const val KEY_MCP_AUTH_TOKEN          = "mcp_auth_token"

    // ── Typed read helpers ────────────────────────────────────────────────────

    fun main(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_MAIN, Context.MODE_PRIVATE)

    fun features(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FEATURES, Context.MODE_PRIVATE)

    fun scanIntervalMs(context: Context): Long =
        main(context).getLong(KEY_SCAN_INTERVAL_MS, DEFAULT_SCAN_INTERVAL_MS)

    fun scanTimeoutMs(context: Context): Long =
        main(context).getLong(KEY_SCAN_TIMEOUT_MS, DEFAULT_SCAN_TIMEOUT_MS)

    fun isFeatureEnabled(context: Context, key: String, default: Boolean = true): Boolean =
        features(context).getBoolean(key, default)

    fun preferredTerminal(context: Context): String =
        main(context).getString(KEY_PREFERRED_TERMINAL, TERMINAL_TERMUX) ?: TERMINAL_TERMUX

    fun isMcpAllowRemoteEnabled(context: Context): Boolean =
        main(context).getBoolean(KEY_MCP_ALLOW_REMOTE, DEFAULT_MCP_ALLOW_REMOTE)

    fun isMcpAllowRootEnabled(context: Context): Boolean =
        main(context).getBoolean(KEY_MCP_ALLOW_ROOT, DEFAULT_MCP_ALLOW_ROOT)

    /**
     * Retrieves the secure authentication token for on-device MCP and REST agents.
     * Generates a cryptographically strong 32-character hex token on first access.
     */
    fun getOrCreateMcpAuthToken(context: Context): String {
        val prefs = main(context)
        val existing = prefs.getString(KEY_MCP_AUTH_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing

        val randomBytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(randomBytes)
        val newToken = randomBytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_MCP_AUTH_TOKEN, newToken).apply()
        return newToken
    }

    /**
     * Stores sensitive API keys with encryption or obfuscation in private app storage.
     */
    fun setSecureSecret(context: Context, key: String, secret: String) {
        val prefs = main(context)
        if (secret.isBlank()) {
            prefs.edit().remove(key).apply()
            return
        }
        val encoded = android.util.Base64.encodeToString(secret.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        prefs.edit().putString(key, encoded).apply()
    }

    /**
     * Retrieves sensitive API keys from private app storage.
     */
    fun getSecureSecret(context: Context, key: String, fallback: String = ""): String {
        val raw = main(context).getString(key, null) ?: return fallback
        return try {
            val bytes = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            raw
        }
    }
}
