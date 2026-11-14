package com.example.data.repository

import com.example.data.local.dao.AuthDao
import com.example.data.local.dao.AdminRecoveryDao
import com.example.data.local.entity.AuthAccountEntity
import com.example.data.local.entity.AdminRecoveryCredentialEntity
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
    private val recoveryDao: AdminRecoveryDao
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
        val credential = AdminRecoveryCredentialEntity(
            salt = code.salt,
            codeHash = code.hash,
            createdAt = System.currentTimeMillis()
        )
        return try {
            if (recoveryDao.createInitialAdminIfAbsent(account, credential)) {
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
        return if (completed) Result.success(Unit) else Result.failure(
            IllegalStateException("Code déjà consommé ou récupération concurrente")
        )
    }

    suspend fun createSeller(username: String, displayName: String, password: CharArray): Result<Unit> {
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
        return runCatching {
            authDao.insert(
                AuthAccountEntity(
                    id = UUID.randomUUID().toString(),
                    username = normalized,
                    displayName = displayName.trim(),
                    role = UserRole.VENDEUR.code,
                    passwordSalt = verifier.salt,
                    passwordHash = verifier.hash
                )
            )
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
        return AuthResult.Success(
            UserSession(
                id = account!!.id,
                name = account.displayName,
                email = account.username,
                role = expectedRole,
                defaultStoreId = "A1",
                sessionId = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + com.example.domain.security.AuthorizationPolicy.SESSION_LIFETIME_MS,
                authorizedStoreIds = if (expectedRole == UserRole.ADMIN) {
                    com.example.domain.model.StoreId.entries.map { it.code }.toSet()
                } else {
                    setOf("A1")
                },
                status = "ACTIVE"
            )
        )
    }
}
