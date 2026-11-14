package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores only the salted verifier for the one-time owner recovery code.
 * The plaintext code must never be persisted in Room or logs.
 */
@Entity(tableName = "admin_recovery_credentials")
data class AdminRecoveryCredentialEntity(
    @PrimaryKey val id: Int = 1,
    val salt: String,
    val codeHash: String,
    val createdAt: Long,
    val failedAttempts: Int = 0,
    val consumedAt: Long? = null
)
