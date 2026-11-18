package com.example.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory

@RunWith(AndroidJUnit4::class)
class DeviceIdentityProviderInstrumentedTest {

    @Test
    fun keystoreKeyIsStableNonExportableAndSignsProtocolBytes() {
        val provider = DeviceIdentityProvider("faneva_v2_instrumented_test_key")
        val first = provider.getOrCreate()
        val second = provider.getOrCreate()

        assertEquals(first.deviceId, second.deviceId)
        assertArrayEquals(first.publicKeyEncoded, second.publicKeyEncoded)
        assertTrue(first.deviceId.startsWith("dev_"))

        val message = "FANEVA-V2-REQUEST\n1\nPOST\n/v1/security/verify".toByteArray()
        val signatureBytes = provider.sign(message)
        assertNotNull(signatureBytes)
        assertTrue(signatureBytes.isNotEmpty())

        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(first.publicKeyEncoded))
        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(message)
            verify(signatureBytes)
        }
        assertTrue("Android Keystore signature must verify against the registered SPKI key", verified)

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = keyStore.getKey("faneva_v2_instrumented_test_key", null)
        assertNotNull(privateKey)
        assertNull("Private key material must remain non-exportable", privateKey.encoded)
    }
}
