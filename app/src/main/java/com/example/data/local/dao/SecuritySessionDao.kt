package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.entity.AuthAccountStoreAccessEntity
import com.example.data.local.entity.AuthSessionEntity

@Dao
interface SecuritySessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: AuthSessionEntity)

    @Query("SELECT * FROM auth_sessions WHERE status = 'ACTIVE' AND expiresAt > :now ORDER BY createdAt DESC LIMIT 1")
    suspend fun getCurrentActiveSession(now: Long = System.currentTimeMillis()): AuthSessionEntity?

    @Query("UPDATE auth_sessions SET status = 'REVOKED' WHERE sessionId = :sessionId AND status = 'ACTIVE'")
    suspend fun revokeSession(sessionId: String): Int

    @Query("UPDATE auth_sessions SET status = 'EXPIRED' WHERE status = 'ACTIVE' AND expiresAt <= :now")
    suspend fun expireOldSessions(now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE auth_sessions SET status = 'REVOKED' WHERE userId = :userId AND status = 'ACTIVE'")
    suspend fun revokeAllForUser(userId: String): Int

    @Query("UPDATE auth_sessions SET status = 'REVOKED' WHERE status = 'ACTIVE'")
    suspend fun revokeAllActiveSessions(): Int

    @Query("SELECT * FROM auth_account_store_access WHERE accountId = :accountId ORDER BY storeId")
    suspend fun getAuthorizedStores(accountId: String): List<AuthAccountStoreAccessEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStoreAccess(access: List<AuthAccountStoreAccessEntity>)

    @Query("DELETE FROM auth_account_store_access WHERE accountId = :accountId")
    suspend fun clearStoreAccess(accountId: String): Int

    @Transaction
    suspend fun replaceStoreAccess(
        accountId: String,
        access: List<AuthAccountStoreAccessEntity>
    ) {
        clearStoreAccess(accountId)
        if (access.isNotEmpty()) insertStoreAccess(access)
    }
}
