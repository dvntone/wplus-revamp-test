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
        val existingSecure = getSecureSecret(context, KEY_MCP_AUTH_TOKEN, "")
        if (existingSecure.isNotBlank()) return existingSecure

        val legacy = main(context).getString(KEY_MCP_AUTH_TOKEN, null)
        if (!legacy.isNullOrBlank()) {
            setSecureSecret(context, KEY_MCP_AUTH_TOKEN, legacy)
            return legacy
        }

        val randomBytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(randomBytes)
        val newToken = randomBytes.joinToString("") { "%02x".format(it) }
        setSecureSecret(context, KEY_MCP_AUTH_TOKEN, newToken)
        return newToken
    }

    // ── Cryptographic KeyStore & AES-256-GCM Encryption ──────────────────────
    private const val KEY_ALIAS = "wscanplus_secret_master_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val CIPHER_AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val ENC_PREFIX = "enc:v1:"
    private var jvmTestKey: java.security.Key? = null

    /**
     * Stores sensitive API keys with AES-256-GCM encryption backed by AndroidKeyStore.
     */
    fun setSecureSecret(context: Context, key: String, secret: String) {
        val prefs = main(context)
        if (secret.isBlank()) {
            prefs.edit().remove(key).apply()
            return
        }
        val encrypted = encryptString(context, secret)
        prefs.edit().putString(key, encrypted).apply()
    }

    /**
     * Retrieves and decrypts sensitive API keys from private app storage.
     * Supports automatic backward compatibility with legacy Base64/plain values.
     */
    fun getSecureSecret(context: Context, key: String, fallback: String = ""): String {
        val raw = main(context).getString(key, null) ?: return fallback
        if (raw.isBlank()) return fallback

        // If encrypted with AES-GCM
        if (raw.startsWith(ENC_PREFIX)) {
            val decrypted = decryptString(context, raw)
            if (decrypted != null) return decrypted
        }

        // Backward-compatible fallback: legacy Base64 or plain string
        return try {
            val bytes = b64Decode(raw)
            val decoded = String(bytes, Charsets.UTF_8)
            // If successfully decoded, migrate to encrypted storage in background
            if (decoded.isNotBlank()) {
                setSecureSecret(context, key, decoded)
                decoded
            } else {
                raw
            }
        } catch (_: Exception) {
            raw
        }
    }

    private fun b64Encode(bytes: ByteArray): String {
        return try {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (_: Throwable) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    private fun b64Decode(str: String): ByteArray {
        return try {
            java.util.Base64.getDecoder().decode(str.trim())
        } catch (_: Throwable) {
            android.util.Base64.decode(str, android.util.Base64.DEFAULT)
        }
    }

    @Synchronized
    private fun getOrCreateKey(context: Context): java.security.Key {
        return try {
            val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGen = javax.crypto.KeyGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
            keyStore.getKey(KEY_ALIAS, null)
        } catch (_: Exception) {
            // JVM unit test environment fallback: create or reuse in-memory AES-256 key
            jvmTestKey ?: run {
                val keyGen = javax.crypto.KeyGenerator.getInstance("AES")
                keyGen.init(256, java.security.SecureRandom())
                val k = keyGen.generateKey()
                jvmTestKey = k
                k
            }
        }
    }

    private fun encryptString(context: Context, plainText: String): String {
        val key = getOrCreateKey(context)
        val cipher = javax.crypto.Cipher.getInstance(CIPHER_AES_GCM)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val ivB64 = b64Encode(iv)
        val encB64 = b64Encode(cipherBytes)
        return "$ENC_PREFIX$ivB64:$encB64"
    }

    private fun decryptString(context: Context, payload: String): String? {
        return try {
            val parts = payload.removePrefix(ENC_PREFIX).split(":")
            if (parts.size != 2) return null
            val iv = b64Decode(parts[0])
            val cipherBytes = b64Decode(parts[1])

            val key = getOrCreateKey(context)
            val cipher = javax.crypto.Cipher.getInstance(CIPHER_AES_GCM)
            val spec = javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, spec)
            val decryptedBytes = cipher.doFinal(cipherBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.w("AppPreferences", "KeyStore decryption failed: ${e.message}")
            null
        }
    }
}

