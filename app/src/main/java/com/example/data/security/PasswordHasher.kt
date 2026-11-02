package com.example.data.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private val secureRandom = SecureRandom()

    data class Verifier(val salt: String, val hash: String)

    fun createVerifier(password: CharArray): Verifier {
        require(password.size >= 6) { "Le mot de passe doit contenir au moins 6 caractères" }
        val salt = ByteArray(SALT_LENGTH_BYTES).also(secureRandom::nextBytes)
        return Verifier(encode(salt), encode(derive(password, salt)))
    }

    fun verify(password: CharArray, saltEncoded: String, expectedHashEncoded: String): Boolean {
        val salt = decode(saltEncoded)
        val expected = decode(expectedHashEncoded)
        val actual = derive(password, salt)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)
}
