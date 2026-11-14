package com.example.domain.security

import com.example.domain.model.StoreId
import com.example.domain.model.UserRole
import com.example.domain.model.UserSession

enum class ProtectedAction {
    CREATE_SELLER, CREATE_PRODUCT, CREATE_CUSTOMER, EDIT_PRODUCT, DELETE_PRODUCT,
    CASH_SALE, CREDIT_SALE, DEBT_PAYMENT, STOCK_TRANSFER, VIEW_COST_AND_MARGIN,
    SYSTEM_CONFIGURATION,
    READ_PRODUCTS, READ_STOCK, READ_STOCK_MOVEMENTS, READ_SALES, READ_OWN_SALES,
    READ_CUSTOMERS, READ_POS_CUSTOMERS, READ_CUSTOMER_DEBT, READ_CREDIT_LIMIT, READ_DEBT_PAYMENTS,
    READ_PENDING_OPERATIONS, READ_AI_INSIGHTS
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

    fun canPerform(
        session: UserSession?,
        action: ProtectedAction,
        storeId: String? = null,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (!isSessionValid(session, now)) return false
        val actor = requireNotNull(session)
        val allowedRole = when (action) {
            ProtectedAction.CREATE_SELLER,
            ProtectedAction.DELETE_PRODUCT,
            ProtectedAction.DEBT_PAYMENT,
            ProtectedAction.SYSTEM_CONFIGURATION,
            ProtectedAction.READ_DEBT_PAYMENTS,
            ProtectedAction.READ_PENDING_OPERATIONS -> setOf(UserRole.ADMIN)

            ProtectedAction.CREATE_PRODUCT,
            ProtectedAction.CREATE_CUSTOMER,
            ProtectedAction.EDIT_PRODUCT,
            ProtectedAction.STOCK_TRANSFER,
            ProtectedAction.VIEW_COST_AND_MARGIN,
            ProtectedAction.READ_STOCK_MOVEMENTS,
            ProtectedAction.READ_CUSTOMER_DEBT,
            ProtectedAction.READ_CREDIT_LIMIT,
            ProtectedAction.READ_AI_INSIGHTS -> setOf(UserRole.ADMIN, UserRole.GERANT)

            ProtectedAction.CASH_SALE,
            ProtectedAction.CREDIT_SALE,
            ProtectedAction.READ_PRODUCTS,
            ProtectedAction.READ_STOCK,
            ProtectedAction.READ_POS_CUSTOMERS -> setOf(UserRole.ADMIN, UserRole.GERANT, UserRole.VENDEUR)

            ProtectedAction.READ_SALES,
            ProtectedAction.READ_CUSTOMERS -> setOf(UserRole.ADMIN, UserRole.GERANT)

            ProtectedAction.READ_OWN_SALES -> setOf(UserRole.VENDEUR)
        }
        if (actor.role !in allowedRole) return false
        val globalActions = setOf(
            ProtectedAction.VIEW_COST_AND_MARGIN,
            ProtectedAction.CREATE_SELLER,
            ProtectedAction.DEBT_PAYMENT,
            ProtectedAction.SYSTEM_CONFIGURATION,
            ProtectedAction.READ_PRODUCTS,
            ProtectedAction.READ_CUSTOMERS,
            ProtectedAction.READ_POS_CUSTOMERS,
            ProtectedAction.READ_CUSTOMER_DEBT,
            ProtectedAction.READ_CREDIT_LIMIT,
            ProtectedAction.READ_DEBT_PAYMENTS,
            ProtectedAction.READ_PENDING_OPERATIONS,
            ProtectedAction.READ_AI_INSIGHTS
        )
        if (action in globalActions) return true
        if (storeId == null || StoreId.parseStrict(storeId) == null) return false
        return storeId in actor.authorizedStoreIds
    }
}
