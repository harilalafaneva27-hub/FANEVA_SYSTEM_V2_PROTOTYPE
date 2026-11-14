package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.AuthAccountEntity
import com.example.data.local.entity.AuthAccountStoreAccessEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AuthDao {
    @Query("SELECT * FROM auth_accounts WHERE username = :username AND isActive = 1 AND accountStatus = 'ACTIVE' LIMIT 1")
    suspend fun findActiveByUsername(username: String): AuthAccountEntity?

    @Query("SELECT * FROM auth_accounts WHERE role = 'VENDEUR' AND isActive = 1 ORDER BY username ASC")
    fun observeActiveSellers(): Flow<List<AuthAccountEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: AuthAccountEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStoreAccess(access: List<AuthAccountStoreAccessEntity>)

    @androidx.room.Transaction
    suspend fun createAccountWithStoreAccess(
        account: AuthAccountEntity,
        access: List<AuthAccountStoreAccessEntity>
    ) {
        insert(account)
        if (access.isNotEmpty()) insertStoreAccess(access)
    }

    @Query("SELECT COUNT(*) FROM auth_accounts WHERE username = :username")
    suspend fun countByUsername(username: String): Int

    @Query("SELECT COUNT(*) FROM auth_accounts WHERE role = 'ADMIN' AND isActive = 1 AND accountStatus = 'ACTIVE'")
    suspend fun countActiveAdmins(): Int

    @Query("SELECT COUNT(*) FROM auth_accounts")
    suspend fun countAllAccounts(): Int
}
