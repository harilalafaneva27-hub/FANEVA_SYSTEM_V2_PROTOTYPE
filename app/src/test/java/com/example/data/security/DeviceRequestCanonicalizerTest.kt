package com.example.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceRequestCanonicalizerTest {
    @Test
    fun canonicalRequestMatchesWorkerFieldOrderAndNewlines() {
        val bytes = DeviceRequestCanonicalizer.bytes(
            method = "post",
            pathname = "/v1/auth/login",
            timestamp = "1795430400",
            nonce = "0123456789abcdef0123456789abcdef",
            deviceId = "dev_" + "a".repeat(64),
            bodySha256 = "B".repeat(64)
        )
        val expected = listOf(
            "FANEVA-V2-REQUEST",
            "1",
            "POST",
            "/v1/auth/login",
            "1795430400",
            "0123456789abcdef0123456789abcdef",
            "dev_" + "a".repeat(64),
            "b".repeat(64)
        ).joinToString("\\n")
        assertEquals(expected, bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun sha256UsesLowercaseHex() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            DeviceRequestCanonicalizer.sha256Hex(ByteArray(0))
        )
    }

    @Test
    fun rejectsQueryStringBecauseWorkerSignsPathnameOnly() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceRequestCanonicalizer.bytes(
                "GET", "/v1/auth/session?x=1", "1795430400",
                "0123456789abcdef0123456789abcdef",
                "dev_" + "a".repeat(64), "b".repeat(64)
            )
        }
    }
}
