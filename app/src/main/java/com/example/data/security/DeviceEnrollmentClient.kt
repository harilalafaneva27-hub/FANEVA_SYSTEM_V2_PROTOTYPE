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
private data class EnrollmentRequestBody(
    val deviceId: String,
    val publicKeySpkiB64Url: String
)

@JsonClass(generateAdapter = true)
private data class EnrollmentResponseBody(
    val enrollmentStatus: String? = null,
    val deviceId: String? = null,
    val error: String? = null
)

sealed class DeviceEnrollmentResult {
    data class PendingOwnerVerification(val deviceId: String) : DeviceEnrollmentResult()
    data class Rejected(val httpCode: Int, val reason: String) : DeviceEnrollmentResult()
}

class DeviceEnrollmentClient(
    private val signer: DeviceRequestSigner = DeviceIdentityProvider(),
    private val client: OkHttpClient = OkHttpClient(),
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
) {
    private val requestAdapter = moshi.adapter(EnrollmentRequestBody::class.java)
    private val responseAdapter = moshi.adapter(EnrollmentResponseBody::class.java)
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val random = SecureRandom()

    suspend fun requestEnrollment(baseUrl: String): DeviceEnrollmentResult = withContext(Dispatchers.IO) {
        val endpoint = baseUrl.trimEnd('/').plus("/v1/devices/enroll").toHttpUrl()
        require(endpoint.isHttps) { "L'URL d'enrollment doit utiliser HTTPS." }

        val identity = signer.getOrCreate()
        val publicKey = Base64.encodeToString(
            identity.publicKeyEncoded,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val bodyBytes = requestAdapter.toJson(
            EnrollmentRequestBody(identity.deviceId, publicKey)
        ).toByteArray(StandardCharsets.UTF_8)
        val timestamp = (System.currentTimeMillis() / 1000L).toString().padStart(10, '0')
        val nonce = ByteArray(16).also(random::nextBytes).toHex()
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(bodyBytes).toHex()
        val canonical = listOf(
            "FANEVA-V2-REQUEST", "1", "POST", endpoint.encodedPath,
            timestamp, nonce, identity.deviceId, bodyHash
        ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        val signature = signer.sign(canonical)
        val signatureB64Url = Base64.encodeToString(
            signature,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val request = Request.Builder()
            .url(endpoint)
            .post(bodyBytes.toRequestBody(mediaType))
            .header("Accept", "application/json")
            .header("X-Faneva-Device-Id", identity.deviceId)
            .header("X-Faneva-Timestamp", timestamp)
            .header("X-Faneva-Nonce", nonce)
            .header("X-Faneva-Signature", signatureB64Url)
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            val parsed = runCatching { responseAdapter.fromJson(responseText) }.getOrNull()
            if (response.code == 202 && parsed?.enrollmentStatus == "PENDING_OWNER_VERIFICATION") {
                DeviceEnrollmentResult.PendingOwnerVerification(parsed.deviceId ?: identity.deviceId)
            } else {
                DeviceEnrollmentResult.Rejected(
                    response.code,
                    parsed?.error ?: responseText.take(200).ifBlank { "Réponse enrollment invalide." }
                )
            }
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }
}
