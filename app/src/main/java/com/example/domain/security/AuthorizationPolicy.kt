package com.example.domain.security

import com.example.domain.model.StoreId
import com.example.domain.model.UserRole
import com.example.domain.model.UserSession

enum class ProtectedAction {
    CREATE_SELLER,
    CREATE_PRODUCT,
    EDIT_PRODUCT,
    DELETE_PRODUCT,
    CASH_SALE,
    CREDIT_SALE,
    DEBT_PAYMENT,
    STOCK_TRANSFER,
    VIEW_COST_AND_MARGIN,
    SYSTEM_CONFIGURATION
}

object AuthorizationPolicy {
    const val SESSION_LIFETIME_MS: Long = 8 * 60 * 60 * 1000L

    fun isSessionValid(session: UserSession?, now: Long = System.currentTimeMillis()): Boolean {
        if (session == null || session.id.isBlank() || session.sessionId.isBlank()) return false
        if (session.status != "ACTIVE" || session.createdAt <= 0L || session.expiresAt <= now) return false
        if (session.expiresAt <= session.createdAt ||
            session.expiresAt - session.createdAt > SESSION_LIFETIME_MS) return false
        if (session.authorizedStoreIds.isEmpty()) return false
        return session.authorizedStoreIds.all { StoreId.parseStrict(it) != null } &&
            session.defaultStoreId in session.authorizedStoreIds
    }

    fun canPerform(session: UserSession?, action: ProtectedAction, storeId: String? = null, now: Long = System.currentTimeMillis()): Boolean {
        if (!isSessionValid(session, now)) return false
        val actor = requireNotNull(session)
        val allowedRole = when (action) {
            ProtectedAction.CREATE_SELLER, ProtectedAction.DELETE_PRODUCT, ProtectedAction.DEBT_PAYMENT,
            ProtectedAction.SYSTEM_CONFIGURATION -> setOf(UserRole.ADMIN)
            ProtectedAction.CREATE_PRODUCT, ProtectedAction.EDIT_PRODUCT ->
                setOf(UserRole.ADMIN, UserRole.GERANT)
            ProtectedAction.CASH_SALE, ProtectedAction.CREDIT_SALE ->
                setOf(UserRole.ADMIN, UserRole.GERANT, UserRole.VENDEUR)
            ProtectedAction.STOCK_TRANSFER -> setOf(UserRole.ADMIN, UserRole.GERANT)
            ProtectedAction.VIEW_COST_AND_MARGIN -> setOf(UserRole.ADMIN, UserRole.GERANT)
        }
        if (actor.role !in allowedRole) return false
        if (action == ProtectedAction.VIEW_COST_AND_MARGIN ||
            action == ProtectedAction.CREATE_SELLER ||
            action == ProtectedAction.DEBT_PAYMENT ||
            action == ProtectedAction.SYSTEM_CONFIGURATION) return true
        if (storeId == null) return false
        if (StoreId.parseStrict(storeId) == null) return false
        return storeId in actor.authorizedStoreIds
    }
}
