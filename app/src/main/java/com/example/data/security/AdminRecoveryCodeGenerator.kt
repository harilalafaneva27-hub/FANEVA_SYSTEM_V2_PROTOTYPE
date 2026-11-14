package com.example.data.security

import java.security.SecureRandom

/**
 * Creates a high-entropy owner recovery code.
 * Callers may display [GeneratedCode.plaintext] once during initial setup;
 * only [GeneratedCode.salt] and [GeneratedCode.hash] may be persisted.
 */
object AdminRecoveryCodeGenerator {
    private val secureRandom = SecureRandom()
    private const val CODE_BYTES = 32
    private val HEX = "0123456789ABCDEF".toCharArray()

    data class GeneratedCode(
        val plaintext: String,
        val salt: String,
        val hash: String
    )

    fun generate(): GeneratedCode {
        val bytes = ByteArray(CODE_BYTES).also(secureRandom::nextBytes)
        val plaintext = buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
        val verifier = PasswordHasher.createVerifier(plaintext.toCharArray())
        return GeneratedCode(
            plaintext = plaintext,
            salt = verifier.salt,
            hash = verifier.hash
        )
    }

    fun verify(code: CharArray, salt: String, expectedHash: String): Boolean {
        if (code.isEmpty() || salt.isBlank() || expectedHash.isBlank()) return false
        return runCatching { PasswordHasher.verify(code, salt, expectedHash) }
            .getOrDefault(false)
    }
}
