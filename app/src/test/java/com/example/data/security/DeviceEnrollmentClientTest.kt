package com.example.data.security

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceEnrollmentClientTest {

    @Test
    fun sendsProofOfPossessionForExactEnrollmentBody() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val publicKeyBytes = pair.public.encoded
        val deviceId = "dev_" + MessageDigest.getInstance("SHA-256")
            .digest(publicKeyBytes).joinToString("") { "%02x".format(it) }
        val signer = object : DeviceRequestSigner {
            override fun getOrCreate() = DeviceIdentity(deviceId, publicKeyBytes)
            override fun sign(canonicalRequest: ByteArray): ByteArray =
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(pair.private)
                    update(canonicalRequest)
                    sign()
                }
        }

        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(202)
                .message("Accepted")
                .body("""{"enrollmentStatus":"PENDING_OWNER_VERIFICATION","deviceId":"$deviceId"}"""
                    .toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        val result = kotlinx.coroutines.runBlocking {
            DeviceEnrollmentClient(signer, client)
                .requestEnrollment("https://worker.example.test")
        }
        assertEquals(DeviceEnrollmentResult.PendingOwnerVerification(deviceId), result)

        val request = captured!!
        assertEquals("/v1/devices/enroll", request.url.encodedPath)
        assertEquals(deviceId, request.header("X-Faneva-Device-Id"))
        val timestamp = request.header("X-Faneva-Timestamp")!!
        val nonce = request.header("X-Faneva-Nonce")!!
        assertTrue(timestamp.matches(Regex("\\d{10}")))
        assertTrue(nonce.matches(Regex("[a-f0-9]{32}")))

        val bodyBuffer = Buffer()
        request.body!!.writeTo(bodyBuffer)
        val body = bodyBuffer.readByteArray()
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { "%02x".format(it) }
        val canonical = listOf(
            "FANEVA-V2-REQUEST", "1", "POST", "/v1/devices/enroll",
            timestamp, nonce, deviceId, bodyHash
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
        val signature = Base64.decode(
            request.header("X-Faneva-Signature")!!,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(canonical)
            verify(signature)
        }
        assertTrue("Enrollment proof must verify against the exact request body", verified)
    }
}
