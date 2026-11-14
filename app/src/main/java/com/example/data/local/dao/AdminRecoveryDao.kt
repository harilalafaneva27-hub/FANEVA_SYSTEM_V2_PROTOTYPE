package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.AdminRecoveryCredentialEntity
import com.example.data.local.entity.AuthAccountEntity

@Dao
interface AdminRecoveryDao {
    @Query("SELECT * FROM admin_recovery_credentials WHERE id = 1 LIMIT 1")
    suspend fun getCredential(): AdminRecoveryCredentialEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCredential(credential: AdminRecoveryCredentialEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAdmin(account: AuthAccountEntity)

    @Query("SELECT COUNT(*) FROM auth_accounts WHERE role = 'ADMIN' AND isActive = 1 AND accountStatus = 'ACTIVE'")
    suspend fun countActiveAdmins(): Int

    @androidx.room.Transaction
    suspend fun createInitialAdminIfAbsent(
        account: AuthAccountEntity,
        credential: AdminRecoveryCredentialEntity
    ): Boolean {
        if (countActiveAdmins() > 0) return false
        insertAdmin(account)
        insertCredential(credential)
        return true
    }

    @Query("SELECT * FROM auth_accounts WHERE role = 'ADMIN' AND isActive = 1 AND accountStatus = 'ACTIVE' ORDER BY createdAt ASC LIMIT 1")
    suspend fun findActiveAdmin(): AuthAccountEntity?

    @Query("UPDATE auth_accounts SET passwordSalt = :salt, passwordHash = :hash, mustChangePassword = 0 WHERE id = :accountId AND role = 'ADMIN' AND isActive = 1 AND accountStatus = 'ACTIVE'")
    suspend fun updateAdminPassword(accountId: String, salt: String, hash: String): Int

    @Query("""
        UPDATE admin_recovery_credentials
        SET failedAttempts = failedAttempts + 1
        WHERE id = 1 AND consumedAt IS NULL AND failedAttempts < 5
    """)
    suspend fun recordFailedAttempt(): Int

    /** Atomic compare-and-set: a recovery verifier can be consumed only once. */
    @Query("""
        UPDATE admin_recovery_credentials
        SET consumedAt = :consumedAt
        WHERE id = 1 AND consumedAt IS NULL AND failedAttempts < 5
    """)
    suspend fun consumeIfUnused(consumedAt: Long): Int
}
