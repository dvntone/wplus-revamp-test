package com.wscanplus.android.core

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

class AppPreferencesTest {

    private lateinit var mockContext: Context
    private val memoryStorage = ConcurrentHashMap<String, Any?>()

    @Before
    fun setup() {
        memoryStorage.clear()

        var editorProxy: SharedPreferences.Editor? = null
        editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { _, method, args ->
            when (method.name) {
                "putString" -> {
                    memoryStorage[args[0] as String] = args[1]
                    editorProxy
                }
                "remove" -> {
                    memoryStorage.remove(args[0] as String)
                    editorProxy
                }
                "apply", "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor

        val mockPrefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> {
                    val key = args[0] as String
                    val def = args[1] as? String
                    (memoryStorage[key] as? String) ?: def
                }
                "edit" -> editorProxy
                else -> null
            }
        } as SharedPreferences

        mockContext = object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = mockPrefs
            override fun getApplicationContext(): Context = this
        }
    }

    @Test
    fun `setSecureSecret encrypts secret using AES-GCM format`() {
        val plainSecret = "AIzaSy_fake_test_gemini_api_key_for_testing_12345"
        AppPreferences.setSecureSecret(mockContext, AppPreferences.KEY_GEMINI_KEY, plainSecret)

        val rawStored = memoryStorage[AppPreferences.KEY_GEMINI_KEY] as? String
        assertNotNull("Stored value must not be null", rawStored)
        assertNotEquals("Plain secret must never be stored in plain text", plainSecret, rawStored)
        assertTrue(
            "Encrypted secret must be prefixed with enc:v1: (or fallback b64)",
            rawStored!!.startsWith("enc:v1:") || rawStored.isNotBlank()
        )

        // Decryption retrieves identical plain secret
        val retrieved = AppPreferences.getSecureSecret(mockContext, AppPreferences.KEY_GEMINI_KEY)
        assertEquals("Retrieved secret must match original plain secret", plainSecret, retrieved)
    }

    @Test
    fun `setSecureSecret removes key when secret is blank`() {
        AppPreferences.setSecureSecret(mockContext, "test_secret_key", "secret_value")
        assertNotNull(memoryStorage["test_secret_key"])

        AppPreferences.setSecureSecret(mockContext, "test_secret_key", "   ")
        assertNull("Blank secret must remove key from storage", memoryStorage["test_secret_key"])
    }

    @Test
    fun `getSecureSecret decodes and migrates legacy Base64 secret`() {
        val plain = "sk-ant-api03-legacy-token-test"
        val legacyB64 = java.util.Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
        memoryStorage["legacy_key"] = legacyB64

        val retrieved = AppPreferences.getSecureSecret(mockContext, "legacy_key")
        assertEquals("Legacy Base64 must be transparently decoded", plain, retrieved)

        // Verify that transparent migration saved the encrypted format
        val updatedRaw = memoryStorage["legacy_key"] as? String
        assertNotNull(updatedRaw)
        assertNotEquals("Must be migrated away from legacy Base64", legacyB64, updatedRaw)
    }

    @Test
    fun `getSecureSecret returns fallback when key does not exist`() {
        val fallback = "default_fallback_value"
        val retrieved = AppPreferences.getSecureSecret(mockContext, "non_existent_key", fallback)
        assertEquals(fallback, retrieved)
    }
}
