package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "auth_sessions", indices = [Index("userId"), Index("expiresAt")])
data class AuthSessionEntity(
    @PrimaryKey val sessionId: String,
    val userId: String,
    val role: String,
    val createdAt: Long,
    val expiresAt: Long,
    val status: String = "ACTIVE",
    val lastSeenAt: Long = createdAt
)

@Entity(
    tableName = "auth_account_store_access",
    primaryKeys = ["accountId", "storeId"],
    indices = [Index("storeId")]
)
data class AuthAccountStoreAccessEntity(
    val accountId: String,
    val storeId: String,
    val grantedAt: Long,
    val grantedBy: String
)
