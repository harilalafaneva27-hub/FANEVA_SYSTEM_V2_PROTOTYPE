package com.example.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the opaque server Bearer token encrypted with an Android Keystore AES-GCM key.
 * Only ciphertext and the GCM IV are persisted in app-private SharedPreferences.
 */
interface BearerSessionTokenProvider {
    fun read(): String?
}

class EncryptedSessionTokenStore(context: Context) : BearerSessionTokenProvider {
    private val preferences = context.getSharedPreferences("faneva_v2_auth_session", Context.MODE_PRIVATE)

    @Synchronized
    fun save(token: String, expiresAt: Long) {
        require(token.matches(Regex("[A-Za-z0-9_-]{43}"))) { "SESSION_TOKEN_INVALID" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putLong(KEY_EXPIRY, expiresAt)
            .apply()
    }

    @Synchronized
    override fun read(): String? {
        val expiry = preferences.getLong(KEY_EXPIRY, 0L)
        val encoded = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val ivText = preferences.getString(KEY_IV, null) ?: return clearAndReturnNull()
        if (expiry <= System.currentTimeMillis() / 1000L) return clearAndReturnNull()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
                .takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) }
                ?: clearAndReturnNull()
        } catch (_: Exception) {
            clearAndReturnNull()
        }
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun clearAndReturnNull(): String? {
        clear()
        return null
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "faneva_v2_session_aes_gcm_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_CIPHERTEXT = "token_ciphertext"
        private const val KEY_IV = "token_iv"
        private const val KEY_EXPIRY = "expires_at"
    }
}
