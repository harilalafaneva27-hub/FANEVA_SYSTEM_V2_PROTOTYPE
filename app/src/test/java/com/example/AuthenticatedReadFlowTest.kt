package com.example

import com.example.domain.model.UserRole
import com.example.domain.model.UserSession
import com.example.domain.security.authenticatedReadFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthenticatedReadFlowTest {
    private fun activeSession(now: Long, expiresAt: Long = now + 60_000L, status: String = "ACTIVE") =
        UserSession(
            id = "account-1",
            name = "Test",
            email = "test",
            role = UserRole.ADMIN,
            defaultStoreId = "A1",
            sessionId = "session-1",
            createdAt = now - 1_000L,
            expiresAt = expiresAt,
            authorizedStoreIds = setOf("A1"),
            status = status
        )

    @Test
    fun activeSessionReceivesProtectedSnapshot() = runTest {
        val now = System.currentTimeMillis()
        val result = authenticatedReadFlow(
            source = flowOf(listOf("protected")),
            sessions = flowOf(activeSession(now)),
            clock = flowOf(now),
            deniedValue = emptyList()
        ).first()
        assertEquals(listOf("protected"), result)
    }

    @Test
    fun signedOutSessionReceivesOnlyDeniedSnapshot() = runTest {
        val now = System.currentTimeMillis()
        val result = authenticatedReadFlow(
            source = flowOf(listOf("protected")),
            sessions = flowOf(null),
            clock = flowOf(now),
            deniedValue = emptyList()
        ).first()
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun revokedSessionReceivesOnlyDeniedSnapshot() = runTest {
        val now = System.currentTimeMillis()
        val result = authenticatedReadFlow(
            source = flowOf(listOf("protected")),
            sessions = flowOf(activeSession(now, status = "REVOKED")),
            clock = flowOf(now),
            deniedValue = emptyList()
        ).first()
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun expiredSessionReceivesOnlyDeniedSnapshot() = runTest {
        val now = System.currentTimeMillis()
        val result = authenticatedReadFlow(
            source = flowOf(listOf("protected")),
            sessions = flowOf(activeSession(now, expiresAt = now - 1L)),
            clock = flowOf(now),
            deniedValue = emptyList()
        ).first()
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun clockRecheckClosesSnapshotAfterExpiry() = runTest {
        val now = System.currentTimeMillis()
        val result = authenticatedReadFlow(
            source = flowOf(listOf("protected")),
            sessions = flowOf(activeSession(now, expiresAt = now + 500L)),
            clock = flowOf(now + 1_000L),
            deniedValue = emptyList()
        ).first()
        assertEquals(emptyList<String>(), result)
    }
}
