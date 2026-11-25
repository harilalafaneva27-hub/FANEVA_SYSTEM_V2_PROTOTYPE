package com.example.data.repository

import com.example.data.local.dao.AuthDao
import com.example.data.local.entity.SellerAccountSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Read-only compatibility view for legacy local account records.
 * This repository deliberately exposes no login, account creation, or ADMIN bootstrap API.
 * Authentication and account provisioning are server-authoritative through DeviceAuthClient.
 */
class AuthRepository(private val authDao: AuthDao) {
    val sellers: Flow<List<SellerAccountSummary>> = authDao.observeActiveSellers().map { accounts ->
        accounts.map { SellerAccountSummary(it.username, it.displayName, it.createdAt) }
    }
}
