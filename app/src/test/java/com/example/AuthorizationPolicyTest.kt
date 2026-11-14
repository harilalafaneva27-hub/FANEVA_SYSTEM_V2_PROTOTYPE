package com.example

import com.example.domain.model.StoreId
import com.example.domain.model.UserRole
import com.example.domain.model.UserSession
import com.example.domain.security.AuthorizationPolicy
import com.example.domain.security.ProtectedAction
import org.junit.Assert.*
import org.junit.Test

class AuthorizationPolicyTest {
    private fun session(
        role: UserRole,
        stores: Set<String> = setOf("A1"),
        expiresAt: Long = 20_000L,
        status: String = "ACTIVE"
    ) = UserSession(
        id = "account-1",
        name = "Test",
        email = "test",
        role = role,
        defaultStoreId = "A1",
        sessionId = "session-1",
        createdAt = 1_000L,
        expiresAt = expiresAt,
        authorizedStoreIds = stores,
        status = status
    )

    @Test fun rejectsMissingExpiredAndRevokedSessions() {
        assertFalse(AuthorizationPolicy.isSessionValid(null, 2_000L))
        assertFalse(AuthorizationPolicy.isSessionValid(session(UserRole.ADMIN, expiresAt = 1_999L), 2_000L))
        assertFalse(AuthorizationPolicy.isSessionValid(session(UserRole.ADMIN, status = "REVOKED"), 2_000L))
    }

    @Test fun rejectsUnknownStoresInsteadOfFallingBackToA1() {
        assertNull(StoreId.parseStrict("A6"))
        assertFalse(AuthorizationPolicy.canPerform(session(UserRole.ADMIN), ProtectedAction.CASH_SALE, "A6", 2_000L))
    }

    @Test fun customerCreationIsAllowedOnlyForAdminAndGerant() {
        assertTrue(AuthorizationPolicy.canPerform(session(UserRole.ADMIN), ProtectedAction.CREATE_CUSTOMER, "A1", 2_000L))
        assertTrue(AuthorizationPolicy.canPerform(session(UserRole.GERANT), ProtectedAction.CREATE_CUSTOMER, "A1", 2_000L))
        assertFalse(AuthorizationPolicy.canPerform(session(UserRole.VENDEUR), ProtectedAction.CREATE_CUSTOMER, "A1", 2_000L))
        assertFalse(AuthorizationPolicy.canPerform(session(UserRole.GERANT), ProtectedAction.CREATE_CUSTOMER, "A2", 2_000L))
    }

    @Test fun sellerCannotPayDebtOrTransferOrSeeMargins() {
        val seller = session(UserRole.VENDEUR)
        assertTrue(AuthorizationPolicy.canPerform(seller, ProtectedAction.CASH_SALE, "A1", 2_000L))
        assertFalse(AuthorizationPolicy.canPerform(seller, ProtectedAction.DEBT_PAYMENT, now = 2_000L))
        assertFalse(AuthorizationPolicy.canPerform(seller, ProtectedAction.STOCK_TRANSFER, "A1", 2_000L))
        assertFalse(AuthorizationPolicy.canPerform(seller, ProtectedAction.VIEW_COST_AND_MARGIN, now = 2_000L))
    }

    @Test fun storeScopeIsEnforcedForEveryStoreScopedAction() {
        val a1Only = session(UserRole.VENDEUR, setOf("A1"))
        assertTrue(AuthorizationPolicy.canPerform(a1Only, ProtectedAction.CASH_SALE, "A1", 2_000L))
        assertFalse(AuthorizationPolicy.canPerform(a1Only, ProtectedAction.CASH_SALE, "A2", 2_000L))
        assertFalse(AuthorizationPolicy.canPerform(a1Only, ProtectedAction.CASH_SALE, "A1 ", 2_000L))
    }

    @Test fun gerantCanManageProductAndTransferButCannotPayDebtOrConfigure() {
        val manager = session(UserRole.GERANT, setOf("A1", "A2"))
        assertTrue(AuthorizationPolicy.canPerform(manager, ProtectedAction.CREATE_PRODUCT, "A2", 2_000L))
        assertTrue(AuthorizationPolicy.canPerform(manager, ProtectedAction.STOCK_TRANSFER, "A1", 2_000L))
        assertTrue(AuthorizationPolicy.canPerform(manager, ProtectedAction.STOCK_TRANSFER, "A2", 2_000L))
        assertFalse(AuthorizationPolicy.canPerform(manager, ProtectedAction.DEBT_PAYMENT, now = 2_000L))
        assertFalse(AuthorizationPolicy.canPerform(manager, ProtectedAction.SYSTEM_CONFIGURATION, now = 2_000L))
    }
}
