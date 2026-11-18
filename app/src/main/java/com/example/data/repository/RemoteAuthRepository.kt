package com.example.data.repository

import com.example.data.security.AuthSessionClient
import com.example.data.security.EncryptedSessionTokenStore
import com.example.data.security.OwnerBootstrapRequest
import com.example.data.security.RemotePrincipal
import com.example.data.security.RemoteSession
import com.example.data.security.DeviceEnrollmentClient
import com.example.data.security.DeviceEnrollmentResult

sealed interface RemoteAuthResult {
    data class Authenticated(val principal: RemotePrincipal, val expiresAt: Long) : RemoteAuthResult
    data object SignedOut : RemoteAuthResult
    data class Rejected(val reason: String) : RemoteAuthResult
}

/**
 * Server-authoritative authentication repository. It never creates local users,
 * assigns roles, or falls back to the legacy Room password database.
 */
class RemoteAuthRepository(
    private val client: AuthSessionClient,
    private val tokenStore: EncryptedSessionTokenStore,
    private val enrollmentClient: DeviceEnrollmentClient = DeviceEnrollmentClient()
) {
    suspend fun bootstrap(baseUrl: String, input: OwnerBootstrapRequest): RemoteAuthResult {
        val enrollment = runCatching { enrollmentClient.requestEnrollment(baseUrl) }
            .getOrElse { return RemoteAuthResult.Rejected(it.message ?: "DEVICE_ENROLLMENT_FAILED") }
        when (enrollment) {
            is DeviceEnrollmentResult.PendingOwnerVerification -> Unit
            is DeviceEnrollmentResult.AlreadyRegistered ->
                return RemoteAuthResult.Rejected("DEVICE_ALREADY_REGISTERED_STATUS_${enrollment.status}")
            is DeviceEnrollmentResult.Rejected ->
                return RemoteAuthResult.Rejected("DEVICE_ENROLLMENT_HTTP_${enrollment.httpCode}: ${enrollment.reason}")
        }
        return runCatching { client.bootstrap(baseUrl, input) }.fold(
            onSuccess = { response -> acceptSession(response.principal, response.session, response.error) },
            onFailure = { RemoteAuthResult.Rejected(it.message ?: "BOOTSTRAP_FAILED") }
        )
    }

    suspend fun login(baseUrl: String, username: String, password: CharArray): RemoteAuthResult {
        try {
            val enrollment = runCatching { enrollmentClient.requestEnrollment(baseUrl) }
                .getOrElse { return RemoteAuthResult.Rejected(it.message ?: "DEVICE_ENROLLMENT_FAILED") }
            when (enrollment) {
                is DeviceEnrollmentResult.PendingOwnerVerification ->
                    return RemoteAuthResult.Rejected("DEVICE_PENDING_OWNER_APPROVAL")
                is DeviceEnrollmentResult.AlreadyRegistered -> if (enrollment.status != "ACTIVE") {
                    return RemoteAuthResult.Rejected("DEVICE_NOT_ACTIVE")
                }
                is DeviceEnrollmentResult.Rejected ->
                    return RemoteAuthResult.Rejected("DEVICE_ENROLLMENT_HTTP_${enrollment.httpCode}: ${enrollment.reason}")
            }
            return runCatching { client.login(baseUrl, username, password) }.fold(
                onSuccess = { response -> acceptSession(response.principal, response.session, response.error) },
                onFailure = { RemoteAuthResult.Rejected(it.message ?: "LOGIN_FAILED") }
            )
        } finally {
            password.fill('\\u0000')
        }
    }

    suspend fun restore(baseUrl: String): RemoteAuthResult {
        val token = tokenStore.read() ?: return RemoteAuthResult.SignedOut
        return runCatching { client.validateSession(baseUrl, token) }.fold(
            onSuccess = { response ->
                val principal = response.principal
                if (response.authenticated == true && principal != null && isValidPrincipal(principal)) {
                    RemoteAuthResult.Authenticated(principal, 0L)
                } else {
                    tokenStore.clear()
                    RemoteAuthResult.SignedOut
                }
            },
            onFailure = { error ->
                if (error.message?.contains("AUTH_HTTP_401") == true || error.message?.contains("AUTH_HTTP_403") == true) {
                    tokenStore.clear()
                    RemoteAuthResult.SignedOut
                } else {
                    RemoteAuthResult.Rejected(error.message ?: "SESSION_VALIDATION_FAILED")
                }
            }
        )
    }

    suspend fun logout(baseUrl: String): RemoteAuthResult {
        val token = tokenStore.read() ?: return RemoteAuthResult.SignedOut
        return runCatching { client.logout(baseUrl, token) }.fold(
            onSuccess = { response ->
                if (response.loggedOut) {
                    tokenStore.clear()
                    RemoteAuthResult.SignedOut
                } else RemoteAuthResult.Rejected(response.error ?: "LOGOUT_REJECTED")
            },
            onFailure = { RemoteAuthResult.Rejected(it.message ?: "LOGOUT_FAILED") }
        )
    }

    private fun acceptSession(
        principal: RemotePrincipal?,
        session: RemoteSession?,
        error: String?
    ): RemoteAuthResult {
        if (principal == null || !isValidPrincipal(principal) || session == null || session.tokenType != "Bearer") {
            return RemoteAuthResult.Rejected(error ?: "AUTH_RESPONSE_INVALID")
        }
        persist(session)
        return RemoteAuthResult.Authenticated(principal, session.expiresAt)
    }

    private fun isValidPrincipal(principal: RemotePrincipal): Boolean =
        principal.role in setOf("ADMIN", "GERANT", "VENDEUR") &&
            principal.storeIds.isNotEmpty() &&
            principal.storeIds.all { it in setOf("A1", "A2", "A3", "A4", "A5") }

    fun clearLocalSession() = tokenStore.clear()

    private fun persist(session: RemoteSession) {
        require(session.expiresAt > System.currentTimeMillis() / 1000L) { "SESSION_ALREADY_EXPIRED" }
        tokenStore.save(session.accessToken, session.expiresAt)
    }
}
