package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "auth_accounts",
    indices = [Index(value = ["username"], unique = true)]
)
data class AuthAccountEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String,
    val role: String,
    val passwordSalt: String,
    val passwordHash: String,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    @androidx.room.ColumnInfo(defaultValue = "'ACTIVE'")
    val accountStatus: String = "ACTIVE",
    val activationTokenHash: String? = null,
    val activationExpiresAt: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "0")
    val activationFailedAttempts: Int = 0,
    val activatedAt: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "0")
    val mustChangePassword: Boolean = false
)

data class SellerAccountSummary(
    val username: String,
    val displayName: String,
    val createdAt: Long
)
