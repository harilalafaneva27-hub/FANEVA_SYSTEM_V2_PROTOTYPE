package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.PendingSyncOperationEntity
import com.example.data.local.entity.SyncCursorEntity
import com.example.domain.validation.OutboxMgaPayloadValidator
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    data class ProtocolError(val syncedCount: Int, val error: String) : SyncPushResult()
    data class LocalPersistenceError(
        val transactionError: String,
        val recoveryError: String
    ) : SyncPushResult()
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
    private val database: FanevaDatabase,
    private val httpClient: SyncHttpClient = OkHttpSyncHttpClient(),
    private val deviceId: String
) : SyncRepository {
    private val pendingSyncDao = database.pendingSyncDao()
    private val syncCursorDao = database.syncCursorDao()
    override val pendingOperations: Flow<List<PendingSyncOperationEntity>> =
        pendingSyncDao.getAllPendingOperations()

    override val pendingCount: Flow<Int> = pendingSyncDao.getPendingCount()

    override val syncCursor: Flow<SyncCursorEntity?> = syncCursorDao.getCursorFlow()

    override val isOnlineFlow: Flow<Boolean> =
        syncCursorDao.getCursorFlow().map { it?.isSimulatedOnline ?: true }

    companion object {
        // One lock shared by every repository instance in this application process.
        private val processSyncMutex = Mutex()
    }

    // Multi-row outbox transitions must either all persist or all roll back.
    private suspend fun updateOutboxAtomically(
        operations: List<PendingSyncOperationEntity>
    ) {
        database.withTransaction {
            operations.forEach { operation ->
                pendingSyncDao.updateOperation(operation)
            }
        }
    }

    override suspend fun triggerSync(): SyncPushResult = processSyncMutex.withLock {
        performSync()
    }

    private suspend fun performSync(): SyncPushResult {
        val cursor = syncCursorDao.getCursor() ?: SyncCursorEntity()
        val initialSnapshot = pendingSyncDao.getPendingOperationsSnapshot()
        val activeStatuses = setOf("PENDING", "SYNCING", "FAILED")
        val activeCount = initialSnapshot.count { it.status in activeStatuses }

        if (activeCount == 0) {
            return SyncPushResult.Success(countSynced = 0, updatedCursor = cursor.lastCursor)
        }
        if (!cursor.isSimulatedOnline) {
            return SyncPushResult.Offline(
                pendingCount = activeCount,
                reason = "Mode hors-ligne actif. Aucune synchronisation serveur n'a été annoncée."
            )
        }
        val url = cursor.stagingApiUrl.trim()
        if (url.isBlank()) {
            return SyncPushResult.ServerNotConfigured(
                pendingCount = activeCount,
                reason = "Serveur non configuré : les opérations restent dans l'outbox."
            )
        }

        pendingSyncDao.recoverAbandonedSyncingOperations()
        val pending = pendingSyncDao.getPendingOperationsSnapshot()
            .filter { it.status == "PENDING" || it.status == "FAILED" }
        if (pending.isEmpty()) {
            return SyncPushResult.Success(countSynced = 0, updatedCursor = cursor.lastCursor)
        }

        val invalidPayloads = pending.mapNotNull { operation ->
            OutboxMgaPayloadValidator.validate(
                operation.entityType,
                operation.payloadJson
            )?.let { reason -> operation to reason }
        }

        if (invalidPayloads.isNotEmpty()) {
            updateOutboxAtomically(
                invalidPayloads.map { (operation, reason) ->
                    operation.copy(
                        status = "FAILED",
                        lastError = reason
                    )
                }
            )
            return SyncPushResult.PartialError(
                syncedCount = 0,
                failedCount = invalidPayloads.size,
                error = "Batch bloqué : ${invalidPayloads.size} payload(s) MGA invalide(s). Aucune opération envoyée."
            )
        }

        updateOutboxAtomically(
            pending.map { it.copy(status = "SYNCING") }
        )
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
            if (error is CancellationException) throw error
            updateOutboxAtomically(
                pending.map {
                    it.copy(
                        status = "FAILED",
                        retryCount = it.retryCount + 1,
                        lastError = error.message ?: "Erreur réseau inconnue"
                    )
                }
            )
            return SyncPushResult.PartialError(0, pending.size, error.message ?: "Erreur réseau")
        }

        if (!response.isSuccess) {
            // A global failure invalidates every per-mutation ACK/REJECT in this response.
            // Restore the exact pre-request outbox state; do not change retry metadata,
            // business entities, or the synchronization cursor.
            updateOutboxAtomically(pending)
            return SyncPushResult.PartialError(
                syncedCount = 0,
                failedCount = pending.size,
                error = response.serverMessage
                    ?: "Échec global du batch serveur. Aucune mutation locale appliquée."
            )
        }

        val ackIds = response.acknowledgedMutationIds
        if (ackIds.size != ackIds.distinct().size) {
            val message =
                "Réponse serveur incohérente : duplicate ACK identifiers."
            updateOutboxAtomically(
                pending.map { operation ->
                    operation.copy(
                        status = "FAILED",
                        retryCount = operation.retryCount + 1,
                        lastError = message
                    )
                }
            )
            return SyncPushResult.PartialError(0, pending.size, message)
        }

        val pendingIds = pending.map { it.mutationId }.toSet()
        val acknowledged = ackIds.toSet()
        val rejected = response.rejectedMutationIds.keys
        val invalidAcks = (acknowledged + rejected).minus(pendingIds)
        if (invalidAcks.isNotEmpty()) {
            updateOutboxAtomically(
                pending.map {
                    it.copy(
                        status = "FAILED",
                        retryCount = it.retryCount + 1,
                        lastError = "Réponse serveur incohérente : ACK inconnu."
                    )
                }
            )
            return SyncPushResult.PartialError(0, pending.size, "Réponse serveur incohérente : ACK inconnu.")
        }

        val conflictingIds = acknowledged.intersect(rejected)
        if (conflictingIds.isNotEmpty()) {
            val message =
                "Réponse serveur incohérente : mutation présente dans ACK et rejet."
            updateOutboxAtomically(
                pending.map { operation ->
                    operation.copy(
                        status = "FAILED",
                        retryCount = operation.retryCount + 1,
                        lastError = message
                    )
                }
            )
            return SyncPushResult.PartialError(0, pending.size, message)
        }

        val nextCursor = response.nextCursor
        if (nextCursor == null || nextCursor < cursor.lastCursor) {
            // Invalid cursor metadata must be rejected before applying any ACK or REJECT.
            // Restore the exact pre-request outbox state so no operation remains SYNCING.
            updateOutboxAtomically(pending)
            return SyncPushResult.ProtocolError(
                syncedCount = 0,
                error = if (nextCursor == null) {
                    "Réponse serveur invalide : nextCursor obligatoire absent."
                } else {
                    "Curseur serveur régressif : $nextCursor < ${cursor.lastCursor}. Aucune mutation locale appliquée."
                }
            )
        }

        val now = System.currentTimeMillis()
        var synced = 0
        var failed = 0

        try {
            database.withTransaction {
                pending.forEach { operation ->
                    when {
                        operation.mutationId in acknowledged -> {
                            if (operation.entityType == "SALE") {
                                val sale = database.saleDao()
                                    .getSaleByIdempotencyKey(operation.idempotencyKey)
                                    ?: throw IllegalStateException(
                                        "Vente locale introuvable pour ACK: ${operation.idempotencyKey}"
                                    )

                                when (sale.status) {
                                    "PENDING_SYNC" -> {
                                        if (sale.saleType == "CREDIT") {
                                            val customerId = sale.customerId
                                                ?: throw IllegalStateException(
                                                    "Client absent pour une vente CREDIT acquittée."
                                                )
                                            val customer = database.customerDao()
                                                .getCustomerById(customerId)
                                                ?: throw IllegalStateException(
                                                    "Client introuvable pour une vente CREDIT acquittée."
                                                )
                                            require(
                                                customer.currentDebt.isFinite() &&
                                                    customer.currentDebt >= 0.0 &&
                                                    sale.remainingAmount.isFinite() &&
                                                    sale.remainingAmount >= 0.0
                                            ) {
                                                "Montant de dette invalide lors de la confirmation serveur."
                                            }

                                            val confirmedDebt =
                                                customer.currentDebt + sale.remainingAmount
                                            require(confirmedDebt.isFinite()) {
                                                "Dépassement numérique de la dette client."
                                            }

                                            val updatedRows = database.customerDao().updateDebt(
                                                customerId,
                                                confirmedDebt
                                            )
                                            check(updatedRows == 1) {
                                                "La mise à jour de la dette client a échoué lors de la confirmation serveur."
                                            }
                                        }

                                        database.saleDao().updateSale(
                                            sale.copy(status = "SERVER_CONFIRMED")
                                        )
                                    }

                                    "SERVER_CONFIRMED" -> Unit

                                    else -> throw IllegalStateException(
                                        "Statut de vente incompatible avec ACK: ${sale.status}"
                                    )
                                }
                            }

                            pendingSyncDao.updateOperation(
                                operation.copy(
                                    status = "SYNCED",
                                    syncedAt = now,
                                    lastError = null
                                )
                            )
                            synced++
                        }

                        operation.mutationId in rejected -> {
                            val rejectionReason =
                                response.rejectedMutationIds[operation.mutationId]
                                    ?: "Opération rejetée par le serveur."

                            if (operation.entityType == "SALE") {
                                val sale = database.saleDao()
                                    .getSaleByIdempotencyKey(operation.idempotencyKey)
                                    ?: throw IllegalStateException(
                                        "Vente locale introuvable pour rejet: ${operation.idempotencyKey}"
                                    )

                                when (sale.status) {
                                    "PENDING_SYNC" -> {
                                        database.saleDao().updateSale(
                                            sale.copy(status = "RECONCILIATION_REQUIRED")
                                        )
                                    }

                                    "RECONCILIATION_REQUIRED" -> Unit

                                    else -> throw IllegalStateException(
                                        "Rejet incompatible avec le statut local de la vente: ${sale.status}"
                                    )
                                }
                            }

                            // A definitive server rejection is resolved for batch/cursor purposes.
                            // Keep the reason, and preserve SALE reconciliation state above.
                            pendingSyncDao.updateOperation(
                                operation.copy(
                                    status = "REJECTED",
                                    retryCount = operation.retryCount + 1,
                                    lastError = rejectionReason
                                )
                            )
                        }

                        else -> {
                            // Missing ACK and REJECT remains unresolved and retryable.
                            pendingSyncDao.updateOperation(
                                operation.copy(
                                    status = "FAILED",
                                    retryCount = operation.retryCount + 1,
                                    lastError = "Aucun ACK ni rejet définitif reçu pour cette opération."
                                )
                            )
                            failed++
                        }
                    }
                }

                // Advance only when every mutation has a definitive ACK or REJECT.
                // Cursor persistence is part of the same transaction as all local mutations.
                if (failed == 0) {
                    syncCursorDao.saveCursor(
                        cursor.copy(
                            lastCursor = nextCursor,
                            lastSyncTimestamp = now
                        )
                    )
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error

            val transactionError =
                "Échec transactionnel du traitement du batch : " +
                    (error.message ?: "erreur locale inconnue")

            // The Room transaction has rolled back all business, outbox and cursor changes.
            // Attempt recovery only after that rollback has completed.
            try {
                updateOutboxAtomically(
                    pending.map { operation ->
                        operation.copy(
                            status = "FAILED",
                            retryCount = operation.retryCount + 1,
                            lastError = transactionError
                        )
                    }
                )
            } catch (recoveryError: Exception) {
                if (recoveryError is CancellationException) throw recoveryError

                return SyncPushResult.LocalPersistenceError(
                    transactionError = transactionError,
                    recoveryError = recoveryError.message
                        ?: "erreur locale inconnue pendant la reprise"
                )
            }

            return SyncPushResult.PartialError(
                syncedCount = 0,
                failedCount = pending.size,
                error = transactionError
            )
        }

        return if (failed == 0) {
            SyncPushResult.Success(
                countSynced = synced,
                updatedCursor = nextCursor
            )
        } else {
            SyncPushResult.PartialError(
                syncedCount = synced,
                failedCount = failed,
                error = response.serverMessage
                    ?: "Batch partiellement résolu : $failed opération(s) restent à synchroniser."
            )
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
