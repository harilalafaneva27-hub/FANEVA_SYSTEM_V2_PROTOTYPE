package com.example.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec

/**
 * Installation identity backed by Android Keystore.
 *
 * The private key is non-exportable and is not included in Android app backups.
 * The resulting ID is a fingerprint of the public key, not an authentication token.
 * A server must enroll the public key and verify signed requests before trusting it.
 */
data class DeviceIdentity(
    val deviceId: String,
    val publicKeyEncoded: ByteArray
)

class DeviceIdentityProvider(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) {
    fun getOrCreate(): DeviceIdentity {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getCertificate(keyAlias)?.publicKey
        val publicKey = existing ?: generateKeyPair().public
        val fingerprint = sha256Hex(publicKey.encoded)
        return DeviceIdentity(
            deviceId = "dev_${fingerprint}",
            publicKeyEncoded = publicKey.encoded.copyOf()
        )
    }

    private fun generateKeyPair() {
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
        generator.generateKeyPair()
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
