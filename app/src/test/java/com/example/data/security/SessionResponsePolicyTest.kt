package com.example.data.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionResponsePolicyTest {
    @Test
    fun authenticatedRequestClearsOnlyWhenServerRejectsAuthentication() {
        assertTrue(SessionResponsePolicy.shouldClearAfterAuthenticatedRequest(401))
        assertFalse(SessionResponsePolicy.shouldClearAfterAuthenticatedRequest(403))
        assertFalse(SessionResponsePolicy.shouldClearAfterAuthenticatedRequest(429))
        assertFalse(SessionResponsePolicy.shouldClearAfterAuthenticatedRequest(500))
    }

    @Test
    fun logoutClearsAfterSuccessOrAlreadyInvalidSession() {
        assertTrue(SessionResponsePolicy.shouldClearAfterLogout(200))
        assertTrue(SessionResponsePolicy.shouldClearAfterLogout(204))
        assertTrue(SessionResponsePolicy.shouldClearAfterLogout(401))
        assertFalse(SessionResponsePolicy.shouldClearAfterLogout(403))
        assertFalse(SessionResponsePolicy.shouldClearAfterLogout(503))
    }
}
