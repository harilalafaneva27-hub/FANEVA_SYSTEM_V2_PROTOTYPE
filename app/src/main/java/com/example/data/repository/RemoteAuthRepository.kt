package com.example.data.repository

import com.example.data.security.AuthSessionClient
import com.example.data.security.EncryptedSessionTokenStore
import com.example.data.security.OwnerBootstrapRequest
import com.example.data.security.RemotePrincipal
import com.example.data.security.RemoteSession

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
    private val tokenStore: EncryptedSessionTokenStore
) {
    suspend fun bootstrap(baseUrl: String, input: OwnerBootstrapRequest): RemoteAuthResult =
        runCatching { client.bootstrap(baseUrl, input) }.fold(
            onSuccess = { response ->
                val principal = response.principal
                val session = response.session
                if (principal == null || session == null || session.tokenType != "Bearer") {
                    RemoteAuthResult.Rejected(response.error ?: "BOOTSTRAP_RESPONSE_INVALID")
                } else {
                    persist(session)
                    RemoteAuthResult.Authenticated(principal, session.expiresAt)
                }
            },
            onFailure = { RemoteAuthResult.Rejected(it.message ?: "BOOTSTRAP_FAILED") }
        )

    suspend fun login(baseUrl: String, username: String, password: CharArray): RemoteAuthResult =
        runCatching { client.login(baseUrl, username, password) }.fold(
            onSuccess = { response -> acceptSession(response.principal, response.session, response.error) },
            onFailure = { RemoteAuthResult.Rejected(it.message ?: "LOGIN_FAILED") }
        )

    suspend fun restore(baseUrl: String): RemoteAuthResult {
        val token = tokenStore.read() ?: return RemoteAuthResult.SignedOut
        return runCatching { client.validateSession(baseUrl, token) }.fold(
            onSuccess = { response ->
                val principal = response.principal
                if (response.authenticated == true && principal != null) {
                    RemoteAuthResult.Authenticated(principal, 0L)
                } else {
                    tokenStore.clear()
                    RemoteAuthResult.SignedOut
                }
            },
            onFailure = { error ->
                if (error.message?.contains("AUTH_HTTP_401") == true) {
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
        if (principal == null || session == null || session.tokenType != "Bearer") {
            return RemoteAuthResult.Rejected(error ?: "AUTH_RESPONSE_INVALID")
        }
        persist(session)
        return RemoteAuthResult.Authenticated(principal, session.expiresAt)
    }

    private fun persist(session: RemoteSession) {
        require(session.expiresAt > System.currentTimeMillis() / 1000L) { "SESSION_ALREADY_EXPIRED" }
        tokenStore.save(session.accessToken, session.expiresAt)
    }
}
