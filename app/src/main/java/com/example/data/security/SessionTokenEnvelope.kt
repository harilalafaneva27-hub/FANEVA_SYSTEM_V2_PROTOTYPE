package com.example.data.security

/**
 * Versioned plaintext envelope that is encrypted as one AES-GCM value.
 * The server remains authoritative; this expiry is only a local fail-closed guard.
 */
internal data class SessionTokenEnvelope(
    val accessToken: String,
    val expiresAtEpochSeconds: Long
) {
    fun serialize(): String = "$VERSION\n$accessToken\n$expiresAtEpochSeconds"

    fun isUsable(nowEpochSeconds: Long, expirySkewSeconds: Long = DEFAULT_EXPIRY_SKEW_SECONDS): Boolean =
        accessToken.matches(TOKEN_PATTERN) &&
            expiresAtEpochSeconds > nowEpochSeconds &&
            expiresAtEpochSeconds - nowEpochSeconds > expirySkewSeconds

    companion object {
        private const val VERSION = "1"
        private const val DEFAULT_EXPIRY_SKEW_SECONDS = 30L
        private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{43}")

        fun parse(serialized: String): SessionTokenEnvelope? {
            val fields = serialized.split('\n')
            if (fields.size != 3 || fields[0] != VERSION) return null
            val expiry = fields[2].toLongOrNull() ?: return null
            val envelope = SessionTokenEnvelope(fields[1], expiry)
            return envelope.takeIf { it.accessToken.matches(TOKEN_PATTERN) && expiry > 0 }
        }
    }
}
