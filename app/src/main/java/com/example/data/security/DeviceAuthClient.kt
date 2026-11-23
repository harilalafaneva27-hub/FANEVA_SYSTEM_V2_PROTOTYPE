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
    // Never forward signed device headers or bearer credentials across redirects.
    private val safeHttp = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    data class DeviceIdentity(
        val deviceId: String,
        val publicKeySpkiB64Url: String
    )

    fun identity(): DeviceIdentity {
        val pair = loadOrCreateSigningKeyPair()
        val publicKey = pair.public.encoded
        val fingerprint = DeviceRequestCanonicalizer.sha256Hex(publicKey)
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
    ): HttpResult = try {
        require(bootstrapCode.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Code initial invalide." }
        require(password.size in 12..128) { "Le mot de passe serveur doit contenir 12 à 128 caractères." }
        require(username.matches(Regex("[A-Za-z0-9._-]{3,64}"))) { "Identifiant serveur invalide." }
        require(displayName.trim().length in 2..100) { "Nom d'affichage invalide." }
        val payload = credentialPayload(
            username = username,
            password = password,
            bootstrapCode = bootstrapCode,
            displayName = displayName.trim()
        )
        val result = signedRequest(baseUrl, "/v1/auth/bootstrap", "POST", payload, null)
        if (result.statusCode in 200..299) {
            persistSessionFromResponse(result)
        }
        result
    } finally {
        password.fill(0.toChar())
    }

    suspend fun login(baseUrl: String, username: String, password: CharArray): HttpResult = try {
        require(username.matches(Regex("[A-Za-z0-9._-]{3,64}"))) { "Identifiant serveur invalide." }
        require(password.size in 12..128) { "Le mot de passe serveur doit contenir 12 à 128 caractères." }
        val payload = credentialPayload(username = username, password = password)
        val result = signedRequest(baseUrl, "/v1/auth/login", "POST", payload, null)
        if (result.statusCode in 200..299) {
            persistSessionFromResponse(result)
        }
        result
    } finally {
        password.fill(0.toChar())
    }

    suspend fun approvePendingDevice(baseUrl: String, targetDeviceId: String): HttpResult {
        require(targetDeviceId.matches(Regex("dev_[a-f0-9]{64}"))) { "Identifiant appareil invalide." }
        require(targetDeviceId != identity().deviceId) { "Un appareil ne peut pas s'approuver lui-même." }
        val token = readAccessToken() ?: throw IllegalStateException("Session serveur ADMIN absente.")
        val payload = JSONObject().put("deviceId", targetDeviceId).toString()
        val result = signedRequest(
            baseUrl, "/v1/devices/approve", "POST",
            payload.toByteArray(StandardCharsets.UTF_8), token
        )
        clearTokenWhenSessionRejected(result)
        return result
    }

    suspend fun validateSession(baseUrl: String): HttpResult {
        val token = readAccessToken() ?: throw IllegalStateException("Session serveur absente.")
        val result = signedRequest(baseUrl, "/v1/auth/session", "GET", ByteArray(0), token)
        clearTokenWhenSessionRejected(result)
        return result
    }

    suspend fun logout(baseUrl: String): HttpResult {
        val token = readAccessToken() ?: throw IllegalStateException("Session serveur absente.")
        val result = signedRequest(baseUrl, "/v1/auth/logout", "POST", ByteArray(0), token)
        if (SessionResponsePolicy.shouldClearAfterLogout(result.statusCode)) clearAccessToken()
        return result
    }

    /** Local cache presence only; server authorization always requires validateSession(). */
    fun hasServerSession(): Boolean = readAccessToken() != null

    private fun clearTokenWhenSessionRejected(result: HttpResult) {
        // 401 means the device/session proof is no longer accepted. Preserve the token
        // on 403 (for example, a valid GERANT session attempting an ADMIN-only action).
        if (SessionResponsePolicy.shouldClearAfterAuthenticatedRequest(result.statusCode)) clearAccessToken()
    }

    private fun persistSessionFromResponse(result: HttpResult) {
        try {
            val session = result.body.getJSONObject("session")
            persistAccessToken(
                session.getString("accessToken"),
                session.getLong("expiresAt")
            )
        } catch (error: Exception) {
            clearAccessToken()
            throw IllegalStateException("Réponse de session serveur invalide.", error)
        }
    }

    fun clearAccessToken() {
        // Synchronous removal is intentional: logout must not report completion while
        // a stale bearer token is still present in the app-private preferences file.
        preferences.edit().remove(TOKEN_CIPHER).remove(TOKEN_IV).commit()
    }

    /**
     * Encodes credentials directly from the caller's CharArray. Avoids creating an
     * immutable password String; signedRequest wipes the resulting byte array after IO.
     */
    private fun credentialPayload(
        username: String,
        password: CharArray,
        bootstrapCode: String? = null,
        displayName: String? = null
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        output.write('{'.code)
        var first = true
        fun field(name: String, value: ByteArray) {
            if (!first) output.write(','.code)
            first = false
            output.write(JSONObject.quote(name).toByteArray(StandardCharsets.UTF_8))
            output.write(':'.code)
            output.write(value)
        }
        if (bootstrapCode != null) {
            field("bootstrapCode", JSONObject.quote(bootstrapCode).toByteArray(StandardCharsets.UTF_8))
        }
        field("username", JSONObject.quote(username).toByteArray(StandardCharsets.UTF_8))
        field("password", quotedJsonString(password))
        if (displayName != null) {
            field("displayName", JSONObject.quote(displayName).toByteArray(StandardCharsets.UTF_8))
        }
        output.write('}'.code)
        return output.toByteArray()
    }

    private fun quotedJsonString(value: CharArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        output.write('"'.code)
        var plainStart = 0
        fun writePlain(end: Int) {
            if (end <= plainStart) return
            val encoded = StandardCharsets.UTF_8.newEncoder().encode(
                java.nio.CharBuffer.wrap(value, plainStart, end - plainStart)
            )
            while (encoded.hasRemaining()) output.write(encoded.get().toInt() and 0xff)
        }
        for (index in value.indices) {
            val character = value[index]
            val escaped = when (character) {
                '"' -> "\\\""
                '\\' -> "\\\\"
                '\b' -> "\\b"
                '\u000c' -> "\\f"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> if (character.code < 0x20) "\\u%04x".format(character.code) else null
            }
            if (escaped != null) {
                writePlain(index)
                output.write(escaped.toByteArray(StandardCharsets.US_ASCII))
                plainStart = index + 1
            }
        }
        writePlain(value.size)
        output.write('"'.code)
        return output.toByteArray()
    }

    private suspend fun signedRequest(
        baseUrl: String,
        path: String,
        method: String,
        body: ByteArray,
        bearerToken: String?
    ): HttpResult = try {
        withContext(Dispatchers.IO) {
        val parsedBase = java.net.URI(baseUrl)
        require(parsedBase.scheme.equals("https", ignoreCase = true) &&
            parsedBase.host != null && parsedBase.rawUserInfo == null &&
            parsedBase.rawQuery == null && parsedBase.rawFragment == null &&
            (parsedBase.rawPath.isNullOrEmpty() || parsedBase.rawPath == "/")) {
            "L'URL serveur doit être une origine HTTPS sans chemin, identifiant ni paramètres."
        }
        require(path.startsWith("/") && !path.contains("?") && !path.contains("#")) { "Chemin API invalide." }
        val normalizedBase = "https://" + parsedBase.rawAuthority
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val nonce = ByteArray(16).also(java.security.SecureRandom()::nextBytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        val device = identity()
        val bodyHash = DeviceRequestCanonicalizer.sha256Hex(body)
        val canonical = DeviceRequestCanonicalizer.bytes(
            method, path, timestamp, nonce, device.deviceId, bodyHash
        )
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

        safeHttp.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }
                .getOrElse { JSONObject().put("error", "SERVER_RESPONSE_INVALID") }
            HttpResult(response.code, json)
        }
        }
    } finally {
        // The request has completed (or failed); erase the credential-bearing body buffer.
        body.fill(0)
    }

    @Synchronized
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

    @Synchronized
    private fun persistAccessToken(token: String, expiresAtEpochSeconds: Long) {
        require(token.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Token serveur invalide." }
        val envelope = SessionTokenEnvelope(token, expiresAtEpochSeconds)
        require(envelope.isUsable(System.currentTimeMillis() / 1000L, expirySkewSeconds = 0)) {
            "Expiration de session serveur invalide."
        }
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
        cipher.updateAAD(TOKEN_AAD)
        val plaintext = envelope.serialize().toByteArray(StandardCharsets.UTF_8)
        val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        val ivText = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertextText = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        encrypted.fill(0)
        val saved = preferences.edit()
            .putString(TOKEN_IV, ivText)
            .putString(TOKEN_CIPHER, ciphertextText)
            .commit()
        if (!saved) {
            clearAccessToken()
            throw IllegalStateException("Tsy voatahiry soa aman-tsara ny session serveur.")
        }
    }

    private fun readAccessToken(): String? {
        val ciphertext = preferences.getString(TOKEN_CIPHER, null)
        val ivText = preferences.getString(TOKEN_IV, null)
        if (ciphertext == null || ivText == null) {
            if (ciphertext != null || ivText != null) clearAccessToken()
            return null
        }
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            val secret = keyStore.getKey(TOKEN_ALIAS, null) as? javax.crypto.SecretKey
                ?: run { clearAccessToken(); return null }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secret,
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            cipher.updateAAD(TOKEN_AAD)
            val plaintext = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            val envelope = try {
                SessionTokenEnvelope.parse(String(plaintext, StandardCharsets.UTF_8))
            } finally {
                plaintext.fill(0)
            }
            if (envelope == null || !envelope.isUsable(System.currentTimeMillis() / 1000L)) {
                clearAccessToken()
                null
            } else {
                envelope.accessToken
            }
        } catch (_: Exception) {
            clearAccessToken()
            null
        }
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
        private val TOKEN_AAD = "FANEVA-V2-SESSION-TOKEN-V2".toByteArray(StandardCharsets.US_ASCII)
    }
}
