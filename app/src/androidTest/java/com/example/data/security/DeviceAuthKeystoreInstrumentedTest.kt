package com.example.data.security

import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.Signature

@RunWith(AndroidJUnit4::class)
class DeviceAuthKeystoreInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun deviceIdentityIsStableAndBoundToNonExportableP256Key() {
        val first = DeviceAuthClient(context).identity()
        val second = DeviceAuthClient(context).identity()

        assertTrue(first.deviceId.matches(Regex("dev_[a-f0-9]{64}")))
        assertEquals(first.deviceId, second.deviceId)
        assertEquals(first.publicKeySpkiB64Url, second.publicKeySpkiB64Url)

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = keyStore.getKey(SIGNING_ALIAS, null) as? java.security.PrivateKey
        val certificate = keyStore.getCertificate(SIGNING_ALIAS)
        assertNotNull("Android Keystore signing key must exist", privateKey)
        assertNotNull("Android Keystore certificate must exist", certificate)
        assertNull("Private key material must not be exportable", privateKey!!.encoded)
        assertEquals(KeyProperties.KEY_ALGORITHM_EC, privateKey.algorithm)

        val publicKeyBytes = Base64.decode(
            first.publicKeySpkiB64Url,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        assertArrayEquals(certificate!!.publicKey.encoded, publicKeyBytes)
        val ecKey = certificate.publicKey as java.security.interfaces.ECPublicKey
        assertEquals(256, ecKey.params.curve.field.fieldSize)
    }

    @Test
    fun androidKeystoreSignatureVerifiesAgainstPublishedDeviceIdentity() {
        val identity = DeviceAuthClient(context).identity()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = keyStore.getKey(SIGNING_ALIAS, null) as java.security.PrivateKey
        val publicKey = keyStore.getCertificate(SIGNING_ALIAS).publicKey
        val timestamp = "1795450000"
        val nonce = "0123456789abcdef0123456789abcdef"
        val bodyHash = DeviceRequestCanonicalizer.sha256Hex(
            "{}".toByteArray(StandardCharsets.UTF_8)
        )
        val canonical = DeviceRequestCanonicalizer.bytes(
            "POST", "/v1/devices/enroll", timestamp, nonce, identity.deviceId, bodyHash
        )

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(canonical)
        val signatureBytes = signer.sign()

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(canonical)
        assertTrue("Signature from Android Keystore must verify with the registered public key", verifier.verify(signatureBytes))
    }

    companion object {
        private const val SIGNING_ALIAS = "faneva_v2_device_p256_v1"
    }
}
