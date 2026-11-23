package com.example.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Device-bound authentication for the FANEVA V2 Worker.
 *
 * The private P-256 key never leaves AndroidKeyStore. Session tokens are
 * encrypted with a separate AndroidKeyStore AES-GCM key before persistence.
 * No local-only authentication result is treated as a server session.
 */
class DeviceAuthClient(
    context: Context,
    private val http: OkHttpClient = OkHttpClient()
) {
    data class HttpResult(val statusCode: Int, val body: JSONObject)

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    data class DeviceIdentity(
        val deviceId: String,
        val publicKeySpkiB64Url: String
    )

    fun identity(): DeviceIdentity {
        val pair = loadOrCreateSigningKeyPair()
        val publicKey = pair.public.encoded
        val fingerprint = sha256Hex(publicKey)
        return DeviceIdentity(
            deviceId = "dev_$fingerprint",
            publicKeySpkiB64Url = base64Url(publicKey)
        )
    }

    suspend fun enroll(baseUrl: String): HttpResult = signedRequest(
        baseUrl = baseUrl,
        path = "/v1/devices/enroll",
        method = "POST",
        body = JSONObject().put("deviceId", identity().deviceId)
            .put("publicKeySpkiB64Url", identity().publicKeySpkiB64Url).toString()
            .toByteArray(StandardCharsets.UTF_8),
        bearerToken = null
    )

    suspend fun ownerBootstrap(
        baseUrl: String,
        bootstrapCode: String,
        username: String,
        password: CharArray,
        displayName: String
    ): HttpResult {
        require(bootstrapCode.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Code initial invalide." }
        require(password.size in 12..128) { "Le mot de passe serveur doit contenir 12 à 128 caractères." }
        require(username.matches(Regex("[A-Za-z0-9._-]{3,64}"))) { "Identifiant serveur invalide." }
        require(displayName.trim().length in 2..100) { "Nom d'affichage invalide." }
        val payload = JSONObject()
            .put("bootstrapCode", bootstrapCode)
            .put("username", username)
            .put("password", String(password))
            .put("displayName", displayName.trim())
        val result = signedRequest(
            baseUrl, "/v1/auth/bootstrap", "POST",
            payload.toString().toByteArray(StandardCharsets.UTF_8), null
        )
        if (result.statusCode in 200..299) persistAccessToken(result.body.getJSONObject("session").getString("accessToken"))
        password.fill('\u0000')
        return result
    }

    suspend fun login(baseUrl: String, username: String, password: CharArray): HttpResult {
        require(username.matches(Regex("[A-Za-z0-9._-]{3,64}"))) { "Identifiant serveur invalide." }
        require(password.size in 12..128) { "Le mot de passe serveur doit contenir 12 à 128 caractères." }
        val payload = JSONObject().put("username", username).put("password", String(password))
        val result = signedRequest(
            baseUrl, "/v1/auth/login", "POST",
            payload.toString().toByteArray(StandardCharsets.UTF_8), null
        )
        password.fill('\u0000')
        if (result.statusCode in 200..299) persistAccessToken(result.body.getJSONObject("session").getString("accessToken"))
        return result
    }

    suspend fun approvePendingDevice(baseUrl: String, targetDeviceId: String): HttpResult {
        require(targetDeviceId.matches(Regex("dev_[a-f0-9]{64}"))) { "Identifiant appareil invalide." }
        require(targetDeviceId != identity().deviceId) { "Un appareil ne peut pas s'approuver lui-même." }
        val token = readAccessToken() ?: throw IllegalStateException("Session serveur ADMIN absente.")
        val payload = JSONObject().put("deviceId", targetDeviceId).toString()
        return signedRequest(
            baseUrl, "/v1/devices/approve", "POST",
            payload.toByteArray(StandardCharsets.UTF_8), token
        )
    }

    suspend fun validateSession(baseUrl: String): HttpResult {
        val token = readAccessToken() ?: throw IllegalStateException("Session serveur absente.")
        return signedRequest(baseUrl, "/v1/auth/session", "GET", ByteArray(0), token)
    }

    suspend fun logout(baseUrl: String): HttpResult {
        val token = readAccessToken() ?: throw IllegalStateException("Session serveur absente.")
        val result = signedRequest(baseUrl, "/v1/auth/logout", "POST", ByteArray(0), token)
        if (result.statusCode in 200..299) clearAccessToken()
        return result
    }

    fun hasServerSession(): Boolean = readAccessToken() != null

    fun clearAccessToken() {
        preferences.edit().remove(TOKEN_CIPHER).remove(TOKEN_IV).apply()
    }

    private suspend fun signedRequest(
        baseUrl: String,
        path: String,
        method: String,
        body: ByteArray,
        bearerToken: String?
    ): HttpResult = withContext(Dispatchers.IO) {
        require(baseUrl.startsWith("https://")) { "L'URL serveur doit utiliser HTTPS." }
        require(path.startsWith("/") && !path.contains("?") && !path.contains("#")) { "Chemin API invalide." }
        val normalizedBase = baseUrl.trimEnd('/')
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val nonce = ByteArray(16).also(java.security.SecureRandom()::nextBytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        val device = identity()
        val bodyHash = sha256Hex(body)
        val canonical = listOf(
            "FANEVA-V2-REQUEST", "1", method.uppercase(), path,
            timestamp, nonce, device.deviceId, bodyHash
        ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(loadOrCreateSigningKeyPair().private)
        signer.update(canonical)
        val signature = base64Url(signer.sign())

        val builder = Request.Builder()
            .url(normalizedBase + path)
            .header("Accept", "application/json")
            .header("X-Faneva-Device-Id", device.deviceId)
            .header("X-Faneva-Timestamp", timestamp)
            .header("X-Faneva-Nonce", nonce)
            .header("X-Faneva-Signature", signature)
        if (bearerToken != null) builder.header("Authorization", "Bearer $bearerToken")
        if (method == "GET") builder.get()
        else builder.method(method, body.toRequestBody(mediaType))

        http.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }
                .getOrElse { JSONObject().put("error", "SERVER_RESPONSE_INVALID") }
            HttpResult(response.code, json)
        }
    }

    private fun loadOrCreateSigningKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(SIGNING_ALIAS, null)
        if (existing != null) {
            val certificate = keyStore.getCertificate(SIGNING_ALIAS)
                ?: throw IllegalStateException("Certificat device absent.")
            return KeyPair(certificate.publicKey, existing as java.security.PrivateKey)
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                SIGNING_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKeyPair()
    }

    private fun persistAccessToken(token: String) {
        require(token.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Token serveur invalide." }
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (!keyStore.containsAlias(TOKEN_ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    TOKEN_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generator.generateKey()
        }
        val secret = keyStore.getKey(TOKEN_ALIAS, null) as javax.crypto.SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        val encrypted = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        preferences.edit()
            .putString(TOKEN_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(TOKEN_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    private fun readAccessToken(): String? {
        val ciphertext = preferences.getString(TOKEN_CIPHER, null) ?: return null
        val ivText = preferences.getString(TOKEN_IV, null) ?: return null
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            val secret = keyStore.getKey(TOKEN_ALIAS, null) as? javax.crypto.SecretKey ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secret,
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), StandardCharsets.UTF_8)
                .takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) }
        } catch (_: Exception) {
            clearAccessToken()
            null
        }
    }

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

    private fun base64Url(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val PREFS = "faneva_device_auth_v1"
        private const val SIGNING_ALIAS = "faneva_v2_device_p256_v1"
        private const val TOKEN_ALIAS = "faneva_v2_session_aes_v1"
        private const val TOKEN_CIPHER = "session_ciphertext"
        private const val TOKEN_IV = "session_iv"
    }
}
