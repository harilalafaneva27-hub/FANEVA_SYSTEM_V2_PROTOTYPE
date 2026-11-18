package com.example.data.security

import android.util.Base64
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

@JsonClass(generateAdapter = true)
data class OwnerBootstrapRequest(
    val bootstrapCode: String,
    val username: String,
    val password: String,
    val displayName: String
)

@JsonClass(generateAdapter = true)
data class PasswordLoginRequest(val username: String, val password: String)

@JsonClass(generateAdapter = true)
data class RemotePrincipal(
    val principalId: String,
    val username: String,
    val role: String,
    val displayName: String
)

@JsonClass(generateAdapter = true)
data class RemoteSession(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: Long
)

@JsonClass(generateAdapter = true)
data class AuthSessionResponse(
    val principal: RemotePrincipal? = null,
    val session: RemoteSession? = null,
    val authenticated: Boolean? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class AuthLogoutResponse(val loggedOut: Boolean = false, val error: String? = null)

/**
 * Calls the server-owned authentication endpoints. Device signatures prove only
 * possession of the Android Keystore key; the Bearer token is the user session.
 * This client deliberately has no local/demo authentication fallback.
 */
class AuthSessionClient(
    private val signer: DeviceRequestSigner = DeviceIdentityProvider(),
    private val client: OkHttpClient = OkHttpClient(),
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
) {
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val random = SecureRandom()
    private val responseAdapter = moshi.adapter(AuthSessionResponse::class.java)
    private val logoutAdapter = moshi.adapter(AuthLogoutResponse::class.java)
    private val bootstrapAdapter = moshi.adapter(OwnerBootstrapRequest::class.java)
    private val loginAdapter = moshi.adapter(PasswordLoginRequest::class.java)

    suspend fun bootstrap(baseUrl: String, input: OwnerBootstrapRequest): AuthSessionResponse =
        post(baseUrl, "/v1/auth/bootstrap", bootstrapAdapter.toJson(input), null)

    suspend fun login(baseUrl: String, username: String, password: CharArray): AuthSessionResponse =
        try {
            post(baseUrl, "/v1/auth/login", loginAdapter.toJson(
                PasswordLoginRequest(username, String(password))
            ), null)
        } finally {
            password.fill('\u0000')
        }

    suspend fun validateSession(baseUrl: String, bearerToken: String): AuthSessionResponse =
        request(baseUrl, "/v1/auth/session", "GET", ByteArray(0), bearerToken)

    suspend fun logout(baseUrl: String, bearerToken: String): AuthLogoutResponse =
        withContext(Dispatchers.IO) {
            requestRaw(baseUrl, "/v1/auth/logout", "POST", ByteArray(0), bearerToken).use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException("AUTH_HTTP_${response.code}")
                logoutAdapter.fromJson(text) ?: throw IllegalStateException("AUTH_RESPONSE_INVALID")
            }
        }

    private suspend fun post(
        baseUrl: String, path: String, json: String, bearerToken: String?
    ): AuthSessionResponse = request(
        baseUrl, path, "POST", json.toByteArray(StandardCharsets.UTF_8), bearerToken
    )

    private suspend fun request(
        baseUrl: String, path: String, method: String, body: ByteArray, bearerToken: String?
    ): AuthSessionResponse = withContext(Dispatchers.IO) {
        requestRaw(baseUrl, path, method, body, bearerToken).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("AUTH_HTTP_${response.code}")
            responseAdapter.fromJson(text) ?: throw IllegalStateException("AUTH_RESPONSE_INVALID")
        }
    }

    private fun requestRaw(
        baseUrl: String, path: String, method: String, body: ByteArray, bearerToken: String?
    ): okhttp3.Response {
        val endpoint = baseUrl.trimEnd('/').plus(path).toHttpUrl()
        require(endpoint.isHttps) { "L'URL d'authentification doit utiliser HTTPS." }
        val identity = signer.getOrCreate()
        val timestamp = (System.currentTimeMillis() / 1000L).toString().padStart(10, '0')
        val nonce = ByteArray(16).also(random::nextBytes).toHex()
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body).toHex()
        val canonical = listOf(
            "FANEVA-V2-REQUEST", "1", method, endpoint.encodedPath,
            timestamp, nonce, identity.deviceId, bodyHash
        ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        val signature = Base64.encodeToString(
            signer.sign(canonical),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val builder = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("X-Faneva-Device-Id", identity.deviceId)
            .header("X-Faneva-Timestamp", timestamp)
            .header("X-Faneva-Nonce", nonce)
            .header("X-Faneva-Signature", signature)
        if (bearerToken != null) {
            require(bearerToken.matches(Regex("[A-Za-z0-9_-]{43}"))) { "SESSION_TOKEN_INVALID" }
            builder.header("Authorization", "Bearer $bearerToken")
        }
        if (method == "GET") builder.get()
        else builder.post(body.toRequestBody(mediaType))
        return client.newCall(builder.build()).execute()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }
}
