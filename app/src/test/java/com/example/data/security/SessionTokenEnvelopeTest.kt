package com.example.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTokenEnvelopeTest {
    private val token = "A".repeat(43)

    @Test
    fun serializesAndParsesTokenWithServerExpiry() {
        val expected = SessionTokenEnvelope(token, 1_800_000_000L)
        assertEquals(expected, SessionTokenEnvelope.parse(expected.serialize()))
    }

    @Test
    fun rejectsMalformedVersionTokenAndExpiry() {
        assertNull(SessionTokenEnvelope.parse("2\\n$token\\n1800000000"))
        assertNull(SessionTokenEnvelope.parse("1\\nshort\\n1800000000"))
        assertNull(SessionTokenEnvelope.parse("1\\n$token\\nnot-a-time"))
        assertNull(SessionTokenEnvelope.parse("1\\n$token\\n0"))
        assertNull(SessionTokenEnvelope.parse("1\\n$token\\n1800000000\\nextra"))
    }

    @Test
    fun refusesExpiredAndNearExpirySessions() {
        val envelope = SessionTokenEnvelope(token, 1_000L)
        assertFalse(envelope.isUsable(nowEpochSeconds = 1_000L))
        assertFalse(envelope.isUsable(nowEpochSeconds = 970L))
        assertTrue(envelope.isUsable(nowEpochSeconds = 969L))
    }

    @Test
    fun neverTreatsServerExpiryAsLocalAuthorizationBeyondItsBoundary() {
        val envelope = SessionTokenEnvelope(token, 5_000L)
        assertTrue(envelope.isUsable(nowEpochSeconds = 4_000L))
        assertFalse(envelope.isUsable(nowEpochSeconds = 5_000L))
    }
}
