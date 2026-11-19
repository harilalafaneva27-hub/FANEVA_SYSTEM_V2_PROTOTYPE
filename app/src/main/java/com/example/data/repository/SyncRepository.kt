package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.PendingSyncOperationEntity
import com.example.data.local.entity.ServerIdMappingEntity
import com.example.data.local.entity.SyncCursorEntity
import com.example.data.security.DeviceIdentityProvider
import com.example.data.security.DeviceRequestSigner
import com.example.data.security.BearerSessionTokenProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

class ProtocolV1HttpException(
    val statusCode: Int,
    val responseBody: String
) : java.io.IOException("HTTP $statusCode: ${responseBody.take(200)}")

interface ProtocolV1SyncHttpClient {
    suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject
    suspend fun pull(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject =
        throw UnsupportedOperationException("Protocol-v1 pull tsy mbola tohanan'ity client ity.")
    suspend fun bootstrap(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject =
        throw UnsupportedOperationException("Protocol-v1 bootstrap tsy mbola tohanan'ity client ity.")
}

class OkHttpProtocolV1SyncHttpClient(
    private val identityProvider: DeviceRequestSigner = DeviceIdentityProvider(),
    private val client: OkHttpClient = OkHttpClient()
) : ProtocolV1SyncHttpClient {
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val secureRandom = SecureRandom()

    override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
        signedPost(url, "push", body, bearerToken, expectedDeviceId)

    override suspend fun pull(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
        signedPost(url, "pull", body, bearerToken, expectedDeviceId)

    override suspend fun bootstrap(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
        signedPost(url, "bootstrap", body, bearerToken, expectedDeviceId)

    private suspend fun signedPost(
        rawUrl: String,
        route: String,
        body: JSONObject,
        bearerToken: String,
        expectedDeviceId: String
    ): JSONObject = withContext(Dispatchers.IO) {
        require(route in setOf("push", "pull", "bootstrap")) { "Route sync tsy ekena." }
        val endpoint = syncEndpoint(rawUrl, route)
        require(endpoint.isHttps) { "L'URL du serveur doit utiliser HTTPS." }
        val identity = identityProvider.getOrCreate()
        require(expectedDeviceId == identity.deviceId) {
            "Ny deviceId dia tsy mifanaraka amin'ny Android Keystore."
        }

        val bodyBytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(bodyBytes).toHex()
        val timestamp = (System.currentTimeMillis() / 1000L).toString().padStart(10, '0')
        val nonce = ByteArray(16).also(secureRandom::nextBytes).toHex()
        val canonical = listOf(
            "FANEVA-V2-REQUEST", "1", "POST", endpoint.encodedPath,
            timestamp, nonce, identity.deviceId, bodyHash
        ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        val signature = identityProvider.sign(canonical)
        val signatureB64Url = android.util.Base64.encodeToString(
            signature,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        val request = Request.Builder()
            .url(endpoint)
            .post(bodyBytes.toRequestBody(mediaType))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $bearerToken")
            .header("X-Faneva-Device-Id", identity.deviceId)
            .header("X-Faneva-Timestamp", timestamp)
            .header("X-Faneva-Nonce", nonce)
            .header("X-Faneva-Signature", signatureB64Url)
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ProtocolV1HttpException(response.code, responseBody)
            require(responseBody.isNotBlank()) { "Valin'ny serveur foana." }
            JSONObject(responseBody)
        }
    }

    private fun syncEndpoint(raw: String, route: String): okhttp3.HttpUrl {
        val base = raw.trim().toHttpUrl()
        val path = base.encodedPath.trimEnd('/')
        val prefix = when {
            path.endsWith("/v1/sync/push") -> path.removeSuffix("/sync/push")
            path.endsWith("/v1/sync/pull") -> path.removeSuffix("/sync/pull")
            path.endsWith("/v1/sync/bootstrap") -> path.removeSuffix("/sync/bootstrap")
            path.endsWith("/v1") -> path
            else -> "$path/v1"
        }
        val endpointPath = "${prefix.trimEnd('/')}/sync/$route".ifBlank { "/v1/sync/$route" }
        return base.newBuilder().encodedPath(endpointPath).query(null).fragment(null).build()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(it) }
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

sealed class SyncPushResult {
    data class Success(val countSynced: Int, val updatedCursor: Long) : SyncPushResult()
    data class Offline(val pendingCount: Int, val reason: String) : SyncPushResult()
    data class ServerNotConfigured(val pendingCount: Int, val reason: String) : SyncPushResult()
    data class Deferred(val syncedCount: Int, val pendingCount: Int, val reason: String) : SyncPushResult()
    data class PartialError(val syncedCount: Int, val failedCount: Int, val error: String) : SyncPushResult()
}

class SyncRepositoryImpl(
    private val database: FanevaDatabase,
    private val httpClient: ProtocolV1SyncHttpClient = OkHttpProtocolV1SyncHttpClient(),
    private val deviceId: String,
    private val bearerTokenProvider: BearerSessionTokenProvider? = null
) : SyncRepository {
    private val pendingSyncDao = database.pendingSyncDao()
    private val syncCursorDao = database.syncCursorDao()
    private val converter = OutboxProtocolV1Converter(RoomOutboxProtocolV1Resolver(database))

    override val pendingOperations: Flow<List<PendingSyncOperationEntity>> =
        pendingSyncDao.getAllPendingOperations()

    override val pendingCount: Flow<Int> = pendingSyncDao.getPendingCount()

    override val syncCursor: Flow<SyncCursorEntity?> = syncCursorDao.getCursorFlow()

    override val isOnlineFlow: Flow<Boolean> =
        syncCursorDao.getCursorFlow().map { it?.isSimulatedOnline ?: true }

    override suspend fun triggerSync(): SyncPushResult {
        val cursor = syncCursorDao.getCursor() ?: SyncCursorEntity()
        pendingSyncDao.recoverAbandonedSyncingOperations()
        val initialSnapshot = pendingSyncDao.getPendingOperationsSnapshot()
        val initialPending = initialSnapshot
            .filter { it.status == "PENDING" || it.status == "FAILED" }
        val initialHeld = initialSnapshot.count { it.status == "REJECTED" || it.status == "CONFLICT" }
        if (initialPending.isEmpty()) {
            return if (initialHeld > 0) SyncPushResult.PartialError(0, initialHeld, "Misy mutation REJECTED/CONFLICT miandry reconciliation.")
            else SyncPushResult.Success(0, cursor.lastCursor)
        }
        if (!cursor.isSimulatedOnline) {
            return SyncPushResult.Offline(initialPending.size, "Mode hors-ligne actif. Aucune synchronisation serveur n'a été annoncée.")
        }
        val url = cursor.stagingApiUrl.trim()
        if (url.isBlank()) {
            return SyncPushResult.ServerNotConfigured(initialPending.size, "Serveur non configuré : les opérations restent dans l'outbox.")
        }
        val bearerToken = bearerTokenProvider?.read()
            ?: return SyncPushResult.Offline(initialPending.size, "Session Bearer absente ou expirée. Reconnectez-vous avant la synchronisation.")

        var totalSynced = 0
        var totalFailed = 0
        var deferredCount = 0
        var lastDeferredReason = ""
        var rounds = 0

        // CUSTOMER/PRODUCT are pushed first so later SALE/TRANSFER/DEBT_PAYMENT
        // conversions can resolve their persistent server IDs in the next round.
        while (rounds < 3) {
            rounds++
            val pending = pendingSyncDao.getPendingOperationsSnapshot()
                .filter { it.status == "PENDING" || it.status == "FAILED" }
                .sortedWith(compareBy<PendingSyncOperationEntity>({ dependencyRank(it.entityType) }, { it.createdAt }))
            if (pending.isEmpty()) break

            val converted = mutableListOf<Pair<PendingSyncOperationEntity, ProtocolV1OutboxMutation>>()
            val blocked = mutableListOf<Pair<PendingSyncOperationEntity, String>>()
            for (operation in pending) {
                if (converted.size >= 50) break
                try {
                    converted += operation to converter.convert(operation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    blocked += operation to (error.message ?: "Protocol-v1 conversion failed")
                }
            }
            deferredCount = blocked.size + (pending.size - converted.size - blocked.size).coerceAtLeast(0)
            lastDeferredReason = blocked.firstOrNull()?.second.orEmpty()
            if (converted.isEmpty()) break

            database.withTransaction {
                converted.forEach { (operation, _) ->
                    pendingSyncDao.updateOperation(operation.copy(status = "SYNCING", lastError = null))
                }
            }

            val mutations = JSONArray()
            converted.forEach { (_, mutation) -> mutations.put(mutation.toJson()) }
            val requestBody = JSONObject()
                .put("protocolVersion", 1)
                .put("mutations", mutations)

            val response = try {
                httpClient.push(url, requestBody, bearerToken, deviceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                database.withTransaction {
                    converted.forEach { (operation, _) ->
                        pendingSyncDao.updateOperation(operation.copy(
                            status = "FAILED",
                            retryCount = operation.retryCount + 1,
                            lastError = error.message ?: "Erreur réseau inconnue"
                        ))
                    }
                }
                return SyncPushResult.PartialError(
                    totalSynced, totalFailed + converted.size,
                    error.message ?: "Erreur réseau"
                )
            }

            val parsedResults = try {
                require(response.optInt("protocolVersion", -1) == 1) { "Protocol version serveur tsy mifanaraka." }
                val resultArray = response.optJSONArray("results")
                    ?: throw IllegalStateException("Valin'ny sync tsy manana results array.")
                val byKey = converted.associateBy { it.second.idempotencyKey }
                val resultMap = linkedMapOf<String, ProtocolV1MutationResult>()
                for (index in 0 until resultArray.length()) {
                    val rawResult = resultArray.optJSONObject(index)
                        ?: throw IllegalStateException("Result row $index tsy JSON object.")
                    val parsed = ProtocolV1ResultParser.parse(rawResult)
                    val key = when (parsed) {
                        is ProtocolV1MutationResult.Applied -> parsed.idempotencyKey
                        is ProtocolV1MutationResult.Rejected -> parsed.idempotencyKey
                        is ProtocolV1MutationResult.Conflict -> parsed.idempotencyKey
                    } ?: throw IllegalStateException("Result tsy manana idempotencyKey.")
                    require(key in byKey) { "Server namaly idempotencyKey tsy nalefa." }
                    require(!resultMap.containsKey(key)) { "Server namerina valiny duplicate ho an'ny idempotencyKey." }
                    resultMap[key] = parsed
                }
                resultMap
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                database.withTransaction {
                    converted.forEach { (operation, _) ->
                        pendingSyncDao.updateOperation(operation.copy(
                            status = "FAILED",
                            retryCount = operation.retryCount + 1,
                            lastError = "Valin'ny server tsy manan-kery: ${error.message}"
                        ))
                    }
                }
                return SyncPushResult.PartialError(
                    totalSynced, totalFailed + converted.size,
                    "Valin'ny server tsy manan-kery: ${error.message}"
                )
            }

            val now = System.currentTimeMillis()
            var roundSynced = 0
            var roundFailed = 0
            try {
                database.withTransaction {
                    converted.forEach { (operation, mutation) ->
                        when (val result = parsedResults[mutation.idempotencyKey]) {
                            is ProtocolV1MutationResult.Applied -> {
                                require(result.entityId == mutation.entityId) {
                                    "Server entityId tsy mifanaraka amin'ny mutation."
                                }
                                val owner = database.serverIdMappingDao().getAnyByServerId(result.entityId)
                                require(owner == null ||
                                    (owner.entityType == mutation.entityType && owner.localId == mutation.localEntityId)) {
                                    "Server ID efa mifamatotra amin'ny local entity hafa."
                                }
                                val existing = database.serverIdMappingDao().getMapping(mutation.entityType, mutation.localEntityId)
                                require(existing == null || existing.serverId == result.entityId) {
                                    "Local entity efa manana server ID hafa."
                                }
                                database.serverIdMappingDao().upsertMapping(
                                    ServerIdMappingEntity(
                                        entityType = mutation.entityType,
                                        localId = mutation.localEntityId,
                                        serverId = result.entityId,
                                        serverVersion = result.version,
                                        updatedAt = now
                                    )
                                )
                                pendingSyncDao.updateOperation(operation.copy(
                                    status = "SYNCED", syncedAt = now, lastError = null
                                ))
                                roundSynced++
                            }
                            is ProtocolV1MutationResult.Rejected -> {
                                pendingSyncDao.updateOperation(operation.copy(
                                    status = "REJECTED",
                                    lastError = result.error
                                ))
                                roundFailed++
                            }
                            is ProtocolV1MutationResult.Conflict -> {
                                pendingSyncDao.updateOperation(operation.copy(
                                    status = "CONFLICT",
                                    lastError = result.error + (result.serverVersion?.let { " (serverVersion=$it)" } ?: "")
                                ))
                                roundFailed++
                            }
                            null -> {
                                pendingSyncDao.updateOperation(operation.copy(
                                    status = "FAILED",
                                    retryCount = operation.retryCount + 1,
                                    lastError = "Tsy nahazo ACK ho an'ity mutation ity."
                                ))
                                roundFailed++
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                database.withTransaction {
                    converted.forEach { (operation, _) ->
                        pendingSyncDao.updateOperation(operation.copy(
                            status = "FAILED",
                            retryCount = operation.retryCount + 1,
                            lastError = "Tsy voatahiry ny valin'ny sync: ${error.message}"
                        ))
                    }
                }
                return SyncPushResult.PartialError(
                    totalSynced, totalFailed + converted.size,
                    "Tsy voatahiry ny valin'ny sync: ${error.message}"
                )
            }
            totalSynced += roundSynced
            totalFailed += roundFailed
            if (roundFailed > 0) break
            if (deferredCount == 0) break
        }

        val finalSnapshot = pendingSyncDao.getPendingOperationsSnapshot()
        val remaining = finalSnapshot.count { it.status == "PENDING" || it.status == "FAILED" }
        val held = finalSnapshot.count { it.status == "REJECTED" || it.status == "CONFLICT" }
        return when {
            totalFailed > 0 || held > 0 -> SyncPushResult.PartialError(
                totalSynced, totalFailed + held, "Misy mutation REJECTED/CONFLICT na tsy nahazo ACK; mila reconciliation."
            )
            remaining > 0 -> SyncPushResult.Deferred(
                totalSynced, remaining,
                lastDeferredReason.ifBlank { "Miandry mapping server ho an'ny entity miankina." }
            )
            else -> SyncPushResult.Success(totalSynced, cursor.lastCursor)
        }
    }

    private fun dependencyRank(entityType: String): Int = when (entityType) {
        "CUSTOMER" -> 0
        "PRODUCT" -> 1
        else -> 2
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
