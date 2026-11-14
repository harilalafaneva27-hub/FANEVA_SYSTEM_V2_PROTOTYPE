package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.AdminRecoveryCredentialEntity

@Dao
interface AdminRecoveryDao {
    @Query("SELECT * FROM admin_recovery_credentials WHERE id = 1 LIMIT 1")
    suspend fun getCredential(): AdminRecoveryCredentialEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCredential(credential: AdminRecoveryCredentialEntity)

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
