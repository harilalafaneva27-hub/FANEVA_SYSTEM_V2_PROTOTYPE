package com.example.data.repository

import com.example.data.local.dao.AuthDao
import com.example.data.local.dao.AdminRecoveryDao
import com.example.data.local.dao.SecuritySessionDao
import com.example.data.local.entity.AuthAccountEntity
import com.example.data.local.entity.AdminRecoveryCredentialEntity
import com.example.data.local.entity.AuthAccountStoreAccessEntity
import com.example.data.local.entity.AuthSessionEntity
import com.example.data.local.entity.SellerAccountSummary
import com.example.data.security.AdminRecoveryCodeGenerator
import com.example.data.security.PasswordHasher
import com.example.domain.model.UserRole
import com.example.domain.model.UserSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

sealed interface AuthResult {
    data class Success(val session: UserSession) : AuthResult
    data class Failure(val message: String) : AuthResult
}

sealed interface AdminSetupResult {
    data class Created(val oneTimeRecoveryCode: String) : AdminSetupResult
    data object AlreadyInitialized : AdminSetupResult
    data class Failure(val message: String) : AdminSetupResult
}

class AuthRepository(
    private val authDao: AuthDao,
    private val recoveryDao: AdminRecoveryDao,
    private val sessionDao: SecuritySessionDao
) {
    val sellers: Flow<List<SellerAccountSummary>> = authDao.observeActiveSellers().map { accounts ->
        accounts.filter { it.accountStatus == "ACTIVE" }
            .map { SellerAccountSummary(it.username, it.displayName, it.createdAt) }
    }

    suspend fun isAdminSetupRequired(): Boolean = recoveryDao.countActiveAdmins() == 0

    suspend fun initializeFirstAdmin(
        username: String,
        displayName: String,
        password: CharArray
    ): AdminSetupResult {
        val normalized = username.trim()
        if (!normalized.matches(Regex("[A-Za-z0-9._-]{3,32}")) ||
            normalized == "__admin__" || displayName.trim().isBlank() || password.size < 12
        ) return AdminSetupResult.Failure(
            "Identifiant invalide, nom requis ou mot de passe inférieur à 12 caractères"
        )

        val code = AdminRecoveryCodeGenerator.generate()
        val passwordVerifier = PasswordHasher.createVerifier(password)
        val account = AuthAccountEntity(
            id = UUID.randomUUID().toString(),
            username = normalized,
            displayName = displayName.trim(),
            role = UserRole.ADMIN.code,
            passwordSalt = passwordVerifier.salt,
            passwordHash = passwordVerifier.hash,
            accountStatus = "ACTIVE"
        )
        val now = System.currentTimeMillis()
        val credential = AdminRecoveryCredentialEntity(
            salt = code.salt,
            codeHash = code.hash,
            createdAt = now
        )
        val initialAccess = com.example.domain.model.StoreId.entries.map { store ->
            AuthAccountStoreAccessEntity(account.id, store.code, now, account.id)
        }
        return try {
            if (recoveryDao.createInitialAdminIfAbsent(account, credential, initialAccess)) {
                AdminSetupResult.Created(code.plaintext)
            } else {
                AdminSetupResult.AlreadyInitialized
            }
        } catch (_: Exception) {
            AdminSetupResult.Failure("Configuration ADMIN impossible; aucune confirmation de création n'est disponible")
        }
    }

    suspend fun authenticateAdmin(username: String, password: CharArray): AuthResult =
        authenticate(username.trim(), password, UserRole.ADMIN)

    suspend fun authenticateSeller(username: String, password: CharArray): AuthResult {
        val normalized = username.trim()
        if (normalized.isBlank()) return AuthResult.Failure("Identifiant vendeur requis")
        return authenticate(normalized, password, UserRole.VENDEUR)
    }

    suspend fun recoverAdminPassword(code: CharArray, newPassword: CharArray): Result<Unit> {
        if (newPassword.size < 12) return Result.failure(
            IllegalArgumentException("Le nouveau mot de passe doit contenir au moins 12 caractères")
        )
        val credential = recoveryDao.getCredential()
            ?: return Result.failure(IllegalStateException("Code de récupération indisponible"))
        if (credential.consumedAt != null || credential.failedAttempts >= 5) {
            return Result.failure(IllegalStateException("Code de récupération expiré ou bloqué"))
        }
        if (!AdminRecoveryCodeGenerator.verify(code, credential.salt, credential.codeHash)) {
            recoveryDao.recordFailedAttempt()
            return Result.failure(IllegalArgumentException("Code de récupération invalide"))
        }
        val admin = recoveryDao.findActiveAdmin()
            ?: return Result.failure(IllegalStateException("Compte ADMIN actif introuvable"))
        val verifier = PasswordHasher.createVerifier(newPassword)
        val completed = recoveryDao.completeRecovery(
            accountId = admin.id,
            salt = verifier.salt,
            hash = verifier.hash,
            consumedAt = System.currentTimeMillis()
        )
        if (completed) {
            sessionDao.revokeAllForUser(admin.id)
            return Result.success(Unit)
        }
        return Result.failure(IllegalStateException("Code déjà consommé ou récupération concurrente"))
    }

    suspend fun createSeller(
        actor: UserSession,
        username: String,
        displayName: String,
        password: CharArray
    ): Result<Unit> {
        if (!com.example.domain.security.AuthorizationPolicy.canPerform(
                actor, com.example.domain.security.ProtectedAction.CREATE_SELLER
            )) return Result.failure(SecurityException("Création vendeur réservée à une session ADMIN valide."))
        val normalized = username.trim()
        if (!normalized.matches(Regex("[A-Za-z0-9._-]{3,32}"))) {
            return Result.failure(IllegalArgumentException("Identifiant invalide (3 à 32 caractères alphanumériques)"))
        }
        if (displayName.trim().isBlank()) {
            return Result.failure(IllegalArgumentException("Nom du vendeur requis"))
        }
        if (password.size < 12) {
            return Result.failure(IllegalArgumentException("Le mot de passe doit contenir au moins 12 caractères"))
        }
        if (authDao.countByUsername(normalized) > 0 || normalized == "__admin__") {
            return Result.failure(IllegalArgumentException("Cet identifiant existe déjà"))
        }
        val verifier = PasswordHasher.createVerifier(password)
        val account = AuthAccountEntity(
            id = UUID.randomUUID().toString(),
            username = normalized,
            displayName = displayName.trim(),
            role = UserRole.VENDEUR.code,
            passwordSalt = verifier.salt,
            passwordHash = verifier.hash
        )
        return runCatching {
            val now = System.currentTimeMillis()
            authDao.createAccountWithStoreAccess(
                account,
                listOf(AuthAccountStoreAccessEntity(account.id, "A1", now, "ADMIN"))
            )
        }
    }

    suspend fun assignStoresToSeller(
        actor: UserSession,
        sellerUsername: String,
        storeIds: Set<String>
    ): Result<Unit> {
        if (!com.example.domain.security.AuthorizationPolicy.canPerform(
                actor, com.example.domain.security.ProtectedAction.CREATE_SELLER
            )) {
            return Result.failure(SecurityException("Attribution réservée à une session ADMIN valide."))
        }
        if (storeIds.isEmpty() || storeIds.any { com.example.domain.model.StoreId.parseStrict(it) == null }) {
            return Result.failure(IllegalArgumentException("Sélection de magasins invalide."))
        }
        val seller = authDao.findActiveByUsername(sellerUsername.trim())
            ?: return Result.failure(IllegalArgumentException("Compte vendeur actif introuvable."))
        if (seller.role != UserRole.VENDEUR.code) {
            return Result.failure(IllegalArgumentException("Seul un compte VENDEUR peut être attribué par cette opération."))
        }
        val now = System.currentTimeMillis()
        return runCatching {
            sessionDao.replaceStoreAccess(
                seller.id,
                storeIds.map { store ->
                    AuthAccountStoreAccessEntity(seller.id, store, now, actor.id)
                }
            )
            sessionDao.revokeAllForUser(seller.id)
        }
    }

    private suspend fun authenticate(
        username: String,
        password: CharArray,
        expectedRole: UserRole
    ): AuthResult {
        val account = authDao.findActiveByUsername(username)
        val valid = account != null && account.role == expectedRole.code &&
            account.accountStatus == "ACTIVE" && account.isActive &&
            PasswordHasher.verify(password, account.passwordSalt, account.passwordHash)
        if (!valid) return AuthResult.Failure("Identifiant ou mot de passe incorrect")

        val authenticated = requireNotNull(account)
        val stores = sessionDao.getAuthorizedStores(authenticated.id).map { it.storeId }.toSet()
        if (stores.isEmpty() || stores.any { com.example.domain.model.StoreId.parseStrict(it) == null }) {
            return AuthResult.Failure("Aucun magasin autorisé n'est attribué à ce compte. Contactez l'ADMIN.")
        }
        val now = System.currentTimeMillis()
        val session = UserSession(
            id = authenticated.id,
            name = authenticated.displayName,
            email = authenticated.username,
            role = expectedRole,
            defaultStoreId = stores.sorted().firstOrNull() ?: return AuthResult.Failure("Aucun magasin attribué"),
            sessionId = UUID.randomUUID().toString(),
            createdAt = now,
            expiresAt = now + com.example.domain.security.AuthorizationPolicy.SESSION_LIFETIME_MS,
            authorizedStoreIds = stores,
            status = "ACTIVE"
        )
        return try {
            sessionDao.revokeAllActiveSessions()
            sessionDao.insertSession(
                AuthSessionEntity(
                    sessionId = session.sessionId,
                    userId = session.id,
                    role = session.role.code,
                    createdAt = session.createdAt,
                    expiresAt = session.expiresAt,
                    status = session.status,
                    lastSeenAt = now
                )
            )
            AuthResult.Success(session)
        } catch (_: Exception) {
            AuthResult.Failure("Impossible de créer une session sécurisée persistante.")
        }
    }

    suspend fun revokeSession(sessionId: String): Boolean =
        sessionDao.revokeSession(sessionId) == 1

    suspend fun getPersistedSession(): UserSession? {
        val now = System.currentTimeMillis()
        sessionDao.expireOldSessions(now)
        val persisted = sessionDao.getCurrentActiveSession(now) ?: return null
        val account = authDao.findActiveById(persisted.userId) ?: run {
            sessionDao.revokeSession(persisted.sessionId)
            return null
        }
        if (account.role != persisted.role || account.accountStatus != "ACTIVE") {
            sessionDao.revokeSession(persisted.sessionId)
            return null
        }
        val stores = sessionDao.getAuthorizedStores(account.id).map { it.storeId }.toSet()
        val session = UserSession(
            id = account.id,
            name = account.displayName,
            email = account.username,
            role = UserRole.entries.firstOrNull { it.code == account.role } ?: run {
                sessionDao.revokeSession(persisted.sessionId)
                return null
            },
            defaultStoreId = stores.sorted().firstOrNull() ?: run {
                sessionDao.revokeSession(persisted.sessionId)
                return null
            },
            sessionId = persisted.sessionId,
            createdAt = persisted.createdAt,
            expiresAt = persisted.expiresAt,
            authorizedStoreIds = stores,
            status = persisted.status
        )
        return session.takeIf { com.example.domain.security.AuthorizationPolicy.isSessionValid(it, now) }
    }
}
