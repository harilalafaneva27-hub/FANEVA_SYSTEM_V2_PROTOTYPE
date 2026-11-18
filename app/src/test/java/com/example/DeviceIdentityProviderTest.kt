package com.example

import com.example.data.security.DeviceIdentityProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIdentityProviderTest {
    @Test
    fun `fingerprint is deterministic SHA-256 lowercase hex`() {
        val fingerprint = DeviceIdentityProvider.sha256Hex("abc".toByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            fingerprint
        )
    }
}
