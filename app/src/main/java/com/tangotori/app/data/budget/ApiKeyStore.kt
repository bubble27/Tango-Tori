package com.tangotori.app.data.budget

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the user's own Anthropic API key (bring-your-own-key) encrypted at
 * rest:
 *
 * - The AES-256-GCM key lives in the **Android Keystore** (hardware-backed
 *   where available) and never leaves it — app code only sees encrypt/decrypt
 *   operations, and the ciphertext is useless without this device's keystore.
 * - Ciphertext + IV are kept in a dedicated SharedPreferences file
 *   (`secure_prefs`) that is **excluded from Android backups** (see
 *   `res/xml/backup_rules.xml` / `data_extraction_rules.xml`), so the key
 *   never reaches Google's backup servers.
 * - The plaintext key is sent only over HTTPS to the Tango Tori worker, which
 *   forwards it to Anthropic for that single call and never stores or logs it.
 */
@Singleton
class ApiKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
    }

    private val _hasKey = MutableStateFlow(false)
    /** Whether a user API key is currently saved (the key itself is never exposed in state). */
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    init {
        _hasKey.value = prefs.contains(PREF_CIPHERTEXT)
    }

    /** Decrypts and returns the stored key, or null if none / undecryptable. */
    fun getKey(): String? {
        val cipherB64 = prefs.getString(PREF_CIPHERTEXT, null) ?: return null
        val ivB64 = prefs.getString(PREF_IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keystoreKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivB64, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(cipherB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Keystore key rotated/invalidated (e.g. OS restore to a new device)
            // → stored blob is unreadable; treat as "no key".
            clearKey()
            null
        }
    }

    /** Encrypts and persists [key]. */
    fun saveKey(key: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ciphertext = cipher.doFinal(key.trim().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(PREF_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        _hasKey.value = true
    }

    /** Removes the stored key. */
    fun clearKey() {
        prefs.edit().remove(PREF_CIPHERTEXT).remove(PREF_IV).apply()
        _hasKey.value = false
    }

    enum class Validation { VALID, INVALID, NETWORK_ERROR }

    /**
     * Checks [key] directly against Anthropic's free models endpoint (no tokens
     * billed, and the key goes only to Anthropic — not through the Tango Tori
     * worker) before we accept it.
     */
    suspend fun validate(key: String): Validation = withContext(Dispatchers.IO) {
        try {
            val conn = URL("https://api.anthropic.com/v1/models?limit=1")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("x-api-key", key.trim())
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            when (conn.responseCode) {
                200 -> Validation.VALID
                401, 403 -> Validation.INVALID
                else -> Validation.NETWORK_ERROR
            }
        } catch (_: Exception) {
            Validation.NETWORK_ERROR
        }
    }

    /** Fetches (or creates on first use) the AES key inside the Android Keystore. */
    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "tango_tori_user_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PREF_CIPHERTEXT = "user_api_key_ct"
        const val PREF_IV = "user_api_key_iv"
    }
}
