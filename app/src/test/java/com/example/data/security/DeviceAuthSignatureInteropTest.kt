package com.example.data.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class DeviceAuthSignatureInteropTest {
    @Test
    fun p256Sha256SignatureUsesDerAndVerifiesAgainstCanonicalWorkerBytes() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val message = DeviceRequestCanonicalizer.bytes(
            method = "POST",
            pathname = "/v1/auth/login",
            timestamp = "1795430400",
            nonce = "0123456789abcdef0123456789abcdef",
            deviceId = "dev_" + "a".repeat(64),
            bodySha256 = DeviceRequestCanonicalizer.sha256Hex(
                """{"username":"admin"}""".toByteArray(Charsets.UTF_8)
            )
        )

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(message)
        val derSignature = signer.sign()

        // java.security.Signature (also used by Android Keystore) emits ASN.1 DER.
        assertTrue(derSignature.size in 68..72)
        assertTrue(derSignature[0] == 0x30.toByte())
        assertTrue((derSignature[1].toInt() and 0xff) == derSignature.size - 2)

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(pair.public)
        verifier.update(message)
        assertTrue(verifier.verify(derSignature))

        val tamperedVerifier = Signature.getInstance("SHA256withECDSA")
        tamperedVerifier.initVerify(pair.public)
        tamperedVerifier.update("tampered".toByteArray(Charsets.UTF_8))
        assertFalse(tamperedVerifier.verify(derSignature))
    }
}
