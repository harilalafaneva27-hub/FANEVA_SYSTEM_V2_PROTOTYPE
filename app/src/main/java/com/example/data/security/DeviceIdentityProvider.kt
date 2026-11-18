package com.example.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Installation identity backed by Android Keystore.
 *
 * The private key is non-exportable. The public-key fingerprint is an identifier,
 * not an enrollment grant: the server must independently register and activate it.
 */
data class DeviceIdentity(
    val deviceId: String,
    val publicKeyEncoded: ByteArray
)

class DeviceIdentityProvider(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) {
    @Synchronized
    fun getOrCreate(): DeviceIdentity {
        val keyStore = loadKeyStore()
        val existing = keyStore.getCertificate(keyAlias)?.publicKey
        val publicKey = existing ?: generateKeyPair().public
        val encoded = publicKey.encoded.copyOf()
        return DeviceIdentity(
            deviceId = "dev_${sha256Hex(encoded)}",
            publicKeyEncoded = encoded
        )
    }

    /** Signs the exact canonical request bytes with the non-exportable installation key. */
    @Synchronized
    fun sign(canonicalRequest: ByteArray): ByteArray {
        val privateKey = loadKeyStore().getKey(keyAlias, null)
            ?: throw IllegalStateException("Android Keystore device key is missing; re-enrollment is required.")
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey as java.security.PrivateKey)
            update(canonicalRequest)
            sign() // ASN.1 DER; Worker converts strictly to P1363 for WebCrypto.
        }
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEY_STORE
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(spec)
        return generator.generateKeyPair()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "faneva_v2_device_identity_v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"

        internal fun sha256Hex(value: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value)
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
