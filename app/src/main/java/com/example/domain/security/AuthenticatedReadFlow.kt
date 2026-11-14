package com.example.domain.security

import com.example.domain.model.UserSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Emits protected snapshots only while the persisted-session snapshot is represented
 * by a locally valid active session. The caller supplies a clock flow so expiry can
 * be re-evaluated without waiting for the source data to change.
 */
fun <T> authenticatedReadFlow(
    source: Flow<T>,
    sessions: Flow<UserSession?>,
    clock: Flow<Long>,
    deniedValue: T
): Flow<T> = combine(source, sessions, clock) { value, session, now ->
    if (
        session != null &&
        session.status == "ACTIVE" &&
        session.expiresAt > now &&
        AuthorizationPolicy.isSessionValid(session, now)
    ) value else deniedValue
}.distinctUntilChanged()
