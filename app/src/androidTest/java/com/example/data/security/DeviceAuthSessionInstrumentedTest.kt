package com.example.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Request

@RunWith(AndroidJUnit4::class)
class DeviceAuthSessionInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun loginTokenSurvivesClientRecreationAndIsSentForSessionValidation() = runBlocking {
        val token = "A".repeat(43)
        val expiresAt = System.currentTimeMillis() / 1000L + 3600L
        val observed = AtomicReference<Request?>()
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            observed.set(request)
            val body = if (calls.getAndIncrement() == 0) {
                """{"session":{"accessToken":"$token","expiresAt":$expiresAt}}"""
            } else {
                """{"ok":true}"""
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()

        val firstClient = DeviceAuthClient(context, client)
        firstClient.clearAccessToken()
        val password = "StrongTestPass123".toCharArray()

        val login = firstClient.login(
            "https://auth-test.invalid",
            "admin.test",
            password
        )

        assertEquals(200, login.statusCode)
        assertTrue("Password buffer must be erased after login", password.all { it == '\u0000' })
        assertTrue("Successful login must persist a usable session", firstClient.hasServerSession())

        val recreatedClient = DeviceAuthClient(context, client)
        assertTrue("Encrypted token must survive client recreation", recreatedClient.hasServerSession())

        val validation = recreatedClient.validateSession("https://auth-test.invalid")
        assertEquals(200, validation.statusCode)

        val request = observed.get()!!
        assertEquals("/v1/auth/session", request.url.encodedPath)
        assertEquals("Bearer $token", request.header("Authorization"))
        assertTrue(request.header("X-Faneva-Device-Id")!!.matches(Regex("dev_[a-f0-9]{64}")))
        assertTrue(request.header("X-Faneva-Timestamp").orEmpty().isNotBlank())
        assertTrue(request.header("X-Faneva-Nonce").orEmpty().matches(Regex("[a-f0-9]{32}")))
        assertTrue(request.header("X-Faneva-Signature").orEmpty().isNotBlank())

        recreatedClient.clearAccessToken()
        assertFalse(recreatedClient.hasServerSession())
    }

    @Test
    fun unauthorizedSessionValidationClearsPersistedToken() = runBlocking {
        val token = "B".repeat(43)
        val expiresAt = System.currentTimeMillis() / 1000L + 3600L
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val (status, body, message) = if (calls.getAndIncrement() == 0) {
                Triple(200, """{"session":{"accessToken":"$token","expiresAt":$expiresAt}}""", "OK")
            } else {
                Triple(401, """{"error":"SESSION_REJECTED"}""", "Unauthorized")
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(message)
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()

        val auth = DeviceAuthClient(context, client)
        auth.clearAccessToken()
        val login = auth.login(
            "https://auth-test.invalid",
            "admin.test",
            "AnotherStrongPass456".toCharArray()
        )
        assertEquals(200, login.statusCode)
        assertTrue(auth.hasServerSession())

        val recreated = DeviceAuthClient(context, client)
        assertEquals(401, recreated.validateSession("https://auth-test.invalid").statusCode)
        assertFalse("HTTP 401 must invalidate the locally cached token", recreated.hasServerSession())
    }
}
