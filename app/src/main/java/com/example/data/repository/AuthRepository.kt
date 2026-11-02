package com.example.data.repository

import com.example.data.local.dao.AuthDao
import com.example.data.local.entity.AuthAccountEntity
import com.example.data.local.entity.SellerAccountSummary
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

class AuthRepository(private val authDao: AuthDao) {
    companion object {
        private const val ADMIN_USERNAME = "__admin__"
        private const val INITIAL_ADMIN_CODE = "290493"
    }

    val sellers: Flow<List<SellerAccountSummary>> = authDao.observeActiveSellers().map { accounts ->
        accounts.map { SellerAccountSummary(it.username, it.displayName, it.createdAt) }
    }

    suspend fun ensureInitialAdmin() {
        if (authDao.countByUsername(ADMIN_USERNAME) == 0) {
            val verifier = PasswordHasher.createVerifier(INITIAL_ADMIN_CODE.toCharArray())
            authDao.insert(
                AuthAccountEntity(
                    id = "admin-initial",
                    username = ADMIN_USERNAME,
                    displayName = "Administrateur système",
                    role = UserRole.ADMIN.code,
                    passwordSalt = verifier.salt,
                    passwordHash = verifier.hash
                )
            )
        }
    }

    suspend fun authenticateAdmin(code: CharArray): AuthResult {
        return authenticate(ADMIN_USERNAME, code, UserRole.ADMIN)
    }

    suspend fun authenticateSeller(username: String, password: CharArray): AuthResult {
        val normalized = username.trim()
        if (normalized.isBlank()) return AuthResult.Failure("Identifiant vendeur requis")
        return authenticate(normalized, password, UserRole.VENDEUR)
    }

    suspend fun createSeller(username: String, displayName: String, password: CharArray): Result<Unit> {
        val normalized = username.trim()
        if (!normalized.matches(Regex("[A-Za-z0-9._-]{3,32}"))) {
            return Result.failure(IllegalArgumentException("Identifiant invalide (3 à 32 caractères alphanumériques)"))
        }
        if (displayName.trim().isBlank()) {
            return Result.failure(IllegalArgumentException("Nom du vendeur requis"))
        }
        if (password.size < 6) {
            return Result.failure(IllegalArgumentException("Le mot de passe doit contenir au moins 6 caractères"))
        }
        if (authDao.countByUsername(normalized) > 0 || normalized == ADMIN_USERNAME) {
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

    private suspend fun authenticate(username: String, password: CharArray, expectedRole: UserRole): AuthResult {
        val account = authDao.findActiveByUsername(username)
        val valid = account != null && account.role == expectedRole.code &&
            PasswordHasher.verify(password, account.passwordSalt, account.passwordHash)
        if (!valid) return AuthResult.Failure("Identifiant ou mot de passe incorrect")
        return AuthResult.Success(
            UserSession(
                id = account!!.id,
                name = account.displayName,
                email = account.username,
                role = expectedRole,
                defaultStoreId = "A1"
            )
        )
    }
}
