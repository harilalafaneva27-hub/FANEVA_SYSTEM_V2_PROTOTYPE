package com.example.data.repository

import android.util.Base64
import com.example.data.security.DeviceIdentity
import com.example.data.security.DeviceRequestSigner
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OkHttpSyncHttpClientSigningTest {

    @Test
    fun sendsCanonicalKeystoreCompatibleSignatureForExactBodyBytes() = runBlocking {
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
        val fakeHttp = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            val responseJson = """{"protocolVersion":1,"results":[]}"""
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseJson.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        val request = JSONObject()
            .put("protocolVersion", 1)
            .put("mutations", JSONArray())
        val result = OkHttpProtocolV1SyncHttpClient(signer, fakeHttp)
            .push("https://worker.example.test/v1/sync/push?ignored=query", request, "A".repeat(43), deviceId)

        assertEquals(1, result.getInt("protocolVersion"))
        val sent = captured
        assertNotNull(sent)
        assertEquals(deviceId, sent!!.header("X-Faneva-Device-Id"))
        assertEquals("Bearer ${"A".repeat(43)}", sent!!.header("Authorization"))
        val timestamp = sent.header("X-Faneva-Timestamp")!!
        val nonce = sent.header("X-Faneva-Nonce")!!
        assertTrue(timestamp.matches(Regex("\\d{10}")))
        assertTrue(nonce.matches(Regex("[a-f0-9]{32}")))

        val bodyBuffer = Buffer()
        sent.body!!.writeTo(bodyBuffer)
        val exactBody = bodyBuffer.readByteArray()
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(exactBody)
            .joinToString("") { "%02x".format(it) }
        val canonical = listOf(
            "FANEVA-V2-REQUEST", "1", "POST", "/v1/sync/push",
            timestamp, nonce, deviceId, bodyHash
        ).joinToString("\n").toByteArray(Charsets.UTF_8)

        val signatureBytes = Base64.decode(
            sent.header("X-Faneva-Signature")!!,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(canonical)
            verify(signatureBytes)
        }
        assertTrue("HTTP request signature must verify over the exact canonical body", verified)
    }
}
