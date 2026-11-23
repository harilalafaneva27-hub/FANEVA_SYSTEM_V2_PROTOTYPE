package com.example.data.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** Must remain byte-for-byte compatible with backend/cloudflare-worker/src/crypto.ts. */
internal object DeviceRequestCanonicalizer {
    fun bytes(
        method: String,
        pathname: String,
        timestamp: String,
        nonce: String,
        deviceId: String,
        bodySha256: String
    ): ByteArray {
        require(method.isNotBlank())
        require(pathname.startsWith("/") && !pathname.contains("?") && !pathname.contains("#"))
        require(timestamp.matches(Regex("\\d{10}")))
        require(nonce.matches(Regex("[A-Fa-f0-9]{32,128}")))
        require(deviceId.matches(Regex("dev_[a-f0-9]{64}")))
        require(bodySha256.matches(Regex("[A-Fa-f0-9]{64}")))
        val canonical = listOf(
            "FANEVA-V2-REQUEST",
            "1",
            method.uppercase(Locale.ROOT),
            pathname,
            timestamp,
            nonce,
            deviceId,
            bodySha256.lowercase(Locale.ROOT)
        ).joinToString("\n")
        return canonical.toByteArray(StandardCharsets.UTF_8)
    }

    fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
}
