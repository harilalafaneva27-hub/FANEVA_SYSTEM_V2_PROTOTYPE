package com.example.data.repository

import com.example.data.local.dao.PendingSyncDao
import com.example.data.local.dao.SyncCursorDao
import com.example.data.local.entity.PendingSyncOperationEntity
import com.example.data.local.entity.SyncCursorEntity
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@JsonClass(generateAdapter = true)
data class SyncBatchRequest(
    val protocolVersion: Int = 2,
    val deviceId: String,
    val fromCursor: Long,
    val operations: List<SyncOperationPayload>
)

@JsonClass(generateAdapter = true)
data class SyncOperationPayload(
    val mutationId: String,
    val entityType: String,
    val action: String,
    val idempotencyKey: String,
    val payloadJson: String,
    val clientTimestamp: Long
)

@JsonClass(generateAdapter = true)
data class SyncBatchResponse(
    val isSuccess: Boolean,
    val acknowledgedMutationIds: List<String> = emptyList(),
    val rejectedMutationIds: Map<String, String> = emptyMap(),
    val nextCursor: Long? = null,
    val serverMessage: String? = null
)

sealed class SyncPushResult {
    data class Success(val countSynced: Int, val updatedCursor: Long) : SyncPushResult()
    data class Offline(val pendingCount: Int, val reason: String) : SyncPushResult()
    data class ServerNotConfigured(val pendingCount: Int, val reason: String) : SyncPushResult()
    data class PartialError(val syncedCount: Int, val failedCount: Int, val error: String) : SyncPushResult()
}

interface SyncHttpClient {
    suspend fun push(url: String, request: SyncBatchRequest): SyncBatchResponse
}

class OkHttpSyncHttpClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
) : SyncHttpClient {
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val requestAdapter = moshi.adapter(SyncBatchRequest::class.java)
    private val responseAdapter = moshi.adapter(SyncBatchResponse::class.java)

    override suspend fun push(url: String, request: SyncBatchRequest): SyncBatchResponse =
        withContext(Dispatchers.IO) {
            require(url.startsWith("https://")) { "L'URL du serveur doit utiliser HTTPS." }
            val body = requestAdapter.toJson(request).toRequestBody(mediaType)
            val httpRequest = Request.Builder()
                .url(url)
                .post(body)
                .header("Accept", "application/json")
                .build()
            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Réponse HTTP ${response.code}: ${responseBody.take(200)}")
                }
                require(responseBody.isNotBlank()) { "Réponse serveur vide." }
                responseAdapter.fromJson(responseBody)
                    ?: throw IllegalStateException("Réponse serveur JSON invalide.")
            }
        }
}

interface SyncRepository {
    val pendingOperations: Flow<List<PendingSyncOperationEntity>>
    val pendingCount: Flow<Int>
    val syncCursor: Flow<SyncCursorEntity?>
    val isOnlineFlow: Flow<Boolean>

    suspend fun triggerSync(): SyncPushResult
    suspend fun purgeSyncedOperations()
    suspend fun setSimulatedNetwork(isOnline: Boolean)
    suspend fun setStagingUrl(url: String)
}

class SyncRepositoryImpl(
    private val pendingSyncDao: PendingSyncDao,
    private val syncCursorDao: SyncCursorDao,
    private val httpClient: SyncHttpClient = OkHttpSyncHttpClient(),
    private val deviceId: String = "android-client"
) : SyncRepository {
    override val pendingOperations: Flow<List<PendingSyncOperationEntity>> =
        pendingSyncDao.getAllPendingOperations()

    override val pendingCount: Flow<Int> = pendingSyncDao.getPendingCount()

    override val syncCursor: Flow<SyncCursorEntity?> = syncCursorDao.getCursorFlow()

    override val isOnlineFlow: Flow<Boolean> =
        syncCursorDao.getCursorFlow().map { it?.isSimulatedOnline ?: true }

    override suspend fun triggerSync(): SyncPushResult {
        val cursor = syncCursorDao.getCursor() ?: SyncCursorEntity()
        pendingSyncDao.recoverAbandonedSyncingOperations()
        val pending = pendingSyncDao.getPendingOperationsSnapshot()
            .filter { it.status == "PENDING" || it.status == "FAILED" }
        if (pending.isEmpty()) {
            return SyncPushResult.Success(countSynced = 0, updatedCursor = cursor.lastCursor)
        }
        if (!cursor.isSimulatedOnline) {
            return SyncPushResult.Offline(
                pendingCount = pending.size,
                reason = "Mode hors-ligne actif. Aucune synchronisation serveur n'a été annoncée."
            )
        }
        val url = cursor.stagingApiUrl.trim()
        if (url.isBlank()) {
            return SyncPushResult.ServerNotConfigured(
                pendingCount = pending.size,
                reason = "Serveur non configuré : les opérations restent dans l'outbox."
            )
        }

        pending.forEach { pendingSyncDao.updateOperation(it.copy(status = "SYNCING")) }
        val request = SyncBatchRequest(
            deviceId = deviceId,
            fromCursor = cursor.lastCursor,
            operations = pending.map {
                SyncOperationPayload(
                    mutationId = it.mutationId,
                    entityType = it.entityType,
                    action = it.action,
                    idempotencyKey = it.idempotencyKey,
                    payloadJson = it.payloadJson,
                    clientTimestamp = it.createdAt
                )
            }
        )

        val response = try {
            httpClient.push(url, request)
        } catch (error: Throwable) {
            pending.forEach {
                pendingSyncDao.updateOperation(
                    it.copy(
                        status = "FAILED",
                        retryCount = it.retryCount + 1,
                        lastError = error.message ?: "Erreur réseau inconnue"
                    )
                )
            }
            return SyncPushResult.PartialError(0, pending.size, error.message ?: "Erreur réseau")
        }

        val pendingIds = pending.map { it.mutationId }.toSet()
        val acknowledged = response.acknowledgedMutationIds.toSet()
        val rejected = response.rejectedMutationIds.keys
        val invalidAcks = (acknowledged + rejected).minus(pendingIds)
        if (invalidAcks.isNotEmpty()) {
            pending.forEach {
                pendingSyncDao.updateOperation(
                    it.copy(
                        status = "FAILED",
                        retryCount = it.retryCount + 1,
                        lastError = "Réponse serveur incohérente : ACK inconnu."
                    )
                )
            }
            return SyncPushResult.PartialError(0, pending.size, "Réponse serveur incohérente : ACK inconnu.")
        }

        val now = System.currentTimeMillis()
        var synced = 0
        var failed = 0
        pending.forEach { operation ->
            when {
                operation.mutationId in acknowledged -> {
                    pendingSyncDao.updateOperation(operation.copy(status = "SYNCED", syncedAt = now, lastError = null))
                    synced++
                }
                operation.mutationId in rejected -> {
                    pendingSyncDao.updateOperation(
                        operation.copy(
                            status = "FAILED",
                            retryCount = operation.retryCount + 1,
                            lastError = response.rejectedMutationIds[operation.mutationId]
                        )
                    )
                    failed++
                }
                else -> {
                    pendingSyncDao.updateOperation(
                        operation.copy(
                            status = "FAILED",
                            retryCount = operation.retryCount + 1,
                            lastError = "Aucun ACK reçu pour cette opération."
                        )
                    )
                    failed++
                }
            }
        }

        val allAcknowledged = failed == 0 && synced == pending.size && response.isSuccess
        val updatedCursor = if (allAcknowledged && response.nextCursor != null && response.nextCursor >= cursor.lastCursor) {
            syncCursorDao.saveCursor(cursor.copy(lastCursor = response.nextCursor, lastSyncTimestamp = now))
            response.nextCursor
        } else {
            cursor.lastCursor
        }
        return if (failed == 0 && response.isSuccess) {
            SyncPushResult.Success(synced, updatedCursor)
        } else {
            SyncPushResult.PartialError(synced, failed, response.serverMessage ?: "Certaines opérations n'ont pas été acquittées.")
        }
    }

    override suspend fun purgeSyncedOperations() = pendingSyncDao.purgeSyncedOperations()

    override suspend fun setSimulatedNetwork(isOnline: Boolean) {
        val cursor = syncCursorDao.getCursor() ?: SyncCursorEntity()
        syncCursorDao.saveCursor(cursor.copy(isSimulatedOnline = isOnline))
    }

    override suspend fun setStagingUrl(url: String) {
        val cursor = syncCursorDao.getCursor() ?: SyncCursorEntity()
        syncCursorDao.saveCursor(cursor.copy(stagingApiUrl = url.trim()))
    }
}
