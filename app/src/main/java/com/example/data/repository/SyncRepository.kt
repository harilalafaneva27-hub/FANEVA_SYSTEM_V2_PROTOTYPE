package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.PendingSyncOperationEntity
import com.example.data.local.entity.ServerIdMappingEntity
import com.example.data.local.entity.SyncCursorEntity
import com.example.data.local.entity.SyncEventInboxEntity
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
        joinToString("") { byte -> "%02x".format(byte) }
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
    data class Success(val countSynced: Int, val updatedCursor: Long, val pulledEventCount: Int = 0) : SyncPushResult()
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
        var cursor = syncCursorDao.getCursor() ?: SyncCursorEntity()
        pendingSyncDao.recoverAbandonedSyncingOperations()
        val initialSnapshot = pendingSyncDao.getPendingOperationsSnapshot()
        val initialPending = initialSnapshot
            .filter { it.status == "PENDING" || it.status == "FAILED" }
        val initialHeld = initialSnapshot.count { it.status == "REJECTED" || it.status == "CONFLICT" }
        if (!cursor.isSimulatedOnline) {
            return SyncPushResult.Offline(initialPending.size, "Mode hors-ligne actif. Tsy nisy sync server natao.")
        }
        val url = cursor.stagingApiUrl.trim()
        if (url.isBlank()) {
            return SyncPushResult.ServerNotConfigured(initialPending.size, "Serveur tsy mbola voa-configurer; tsy nisy sync natao.")
        }
        val bearerToken = bearerTokenProvider?.read()
            ?: return SyncPushResult.Offline(initialPending.size, "Session Bearer tsy misy na lany daty. Midira indray.")

        // A new device must install an authoritative snapshot before it can resolve
        // server IDs for local outbox dependencies.
        if (cursor.lastCursor == 0L) {
            try {
                cursor = performBootstrap(url, bearerToken, cursor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return SyncPushResult.PartialError(0, initialPending.size, "Bootstrap tsy vita: ${error.message}")
            }
        }

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

        var pulledCount = 0
        try {
            pulledCount = performPull(url, bearerToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ProtocolV1HttpException) {
            val ahead = error.statusCode == 409 && runCatching {
                JSONObject(error.responseBody).optString("error") == "CURSOR_AHEAD_OF_SERVER"
            }.getOrDefault(false)
            val stillSyncing = pendingSyncDao.getPendingOperationsSnapshot().any { it.status == "SYNCING" }
            if (!ahead || stillSyncing) {
                return SyncPushResult.PartialError(totalSynced, totalFailed + 1, "Pull tsy vita: ${error.message}")
            }
            try {
                cursor = performBootstrap(url, bearerToken, syncCursorDao.getCursor() ?: cursor)
            } catch (bootstrapError: Exception) {
                return SyncPushResult.PartialError(totalSynced, totalFailed + 1, "Cursor recovery/bootstrap tsy vita: ${bootstrapError.message}")
            }
        } catch (error: Exception) {
            return SyncPushResult.PartialError(totalSynced, totalFailed + 1, "Pull tsy vita; cursor tsy niova: ${error.message}")
        }

        val finalSnapshot = pendingSyncDao.getPendingOperationsSnapshot()
        val remaining = finalSnapshot.count { it.status == "PENDING" || it.status == "FAILED" }
        val held = finalSnapshot.count { it.status == "REJECTED" || it.status == "CONFLICT" }
        val finalCursor = syncCursorDao.getCursor()?.lastCursor ?: cursor.lastCursor
        return when {
            totalFailed > 0 || held > 0 -> SyncPushResult.PartialError(
                totalSynced, totalFailed + held, "Misy mutation REJECTED/CONFLICT; mila reconciliation."
            )
            remaining > 0 -> SyncPushResult.Deferred(
                totalSynced, remaining,
                lastDeferredReason.ifBlank { "Miandry mapping server ho an'ny entity miankina." }
            )
            else -> SyncPushResult.Success(totalSynced, finalCursor, pulledCount)
        }
    }

    private suspend fun performBootstrap(url: String, bearerToken: String, previous: SyncCursorEntity): SyncCursorEntity {
        val response = httpClient.bootstrap(url, JSONObject(), bearerToken, deviceId)
        require(response.optInt("protocolVersion", -1) == 1) { "Bootstrap protocolVersion tsy mifanaraka." }
        val snapshot = response.optJSONObject("snapshot") ?: error("Bootstrap snapshot tsy hita.")
        val serverCursor = response.optLong("cursor", -1L)
        require(serverCursor >= 0L) { "Bootstrap cursor tsy manan-kery." }
        val storesJson = response.optJSONArray("storeIds") ?: error("Bootstrap storeIds tsy hita.")
        val stores = (0 until storesJson.length()).map { storesJson.getString(it) }
        require(stores.isNotEmpty() && stores.all { it in setOf("A1","A2","A3","A4","A5") }) {
            "Bootstrap store scope tsy manan-kery."
        }
        val products = snapshot.optJSONArray("products") ?: error("Bootstrap products tsy array.")
        val customers = snapshot.optJSONArray("customers") ?: error("Bootstrap customers tsy array.")
        val stocks = snapshot.optJSONArray("stocks") ?: error("Bootstrap stocks tsy array.")
        val now = System.currentTimeMillis()
        database.withTransaction {
            val mappingDao = database.serverIdMappingDao()
            val productDao = database.productDao()
            val customerDao = database.customerDao()
            val customerSnapshot = customerDao.getAllCustomersSnapshot()

            for (i in 0 until products.length()) {
                val row = products.getJSONObject(i)
                val serverId = row.getString("product_id")
                require(isUuid(serverId)) { "Bootstrap product ID tsy UUID." }
                val code = row.getString("product_code")
                require(code.isNotBlank() && code.length <= 64) { "Bootstrap product code invalid." }
                val mapped = mappingDao.getByServerId("PRODUCT", serverId)
                val existing = mapped?.let { productDao.getProductById(it.localId.toLongOrNull() ?: error("Product local mapping invalid.")) }
                    ?: productDao.getProductByCode(code)
                val selling = requiredMGA(row, "selling_price_mga")
                val purchase = if (row.has("purchase_price_mga")) requiredMGA(row, "purchase_price_mga")
                    else existing?.purchasePrice ?: 0L
                val local = (existing ?: com.example.data.local.entity.ProductEntity(
                    code = code, name = row.getString("name"), category = row.optString("category"),
                    purchasePrice = purchase, sellingPrice = selling
                )).copy(
                    code = code, name = row.getString("name"), category = row.optString("category"),
                    purchasePrice = purchase, sellingPrice = selling.toDouble(),
                    isActive = row.optInt("is_active", 1) == 1
                )
                val localId = if (existing == null) productDao.insertProduct(local) else {
                    productDao.updateProduct(local)
                    local.id
                }
                saveMapping("PRODUCT", localId.toString(), serverId, row.getLong("version"), now)
            }

            for (i in 0 until customers.length()) {
                val row = customers.getJSONObject(i)
                val serverId = row.getString("customer_id")
                require(isUuid(serverId)) { "Bootstrap customer ID tsy UUID." }
                val name = row.getString("display_name")
                require(name.isNotBlank() && name.length <= 160) { "Bootstrap customer name invalid." }
                val mapped = mappingDao.getByServerId("CUSTOMER", serverId)
                val existing = mapped?.let { customerDao.getCustomerById(it.localId.toLongOrNull() ?: error("Customer local mapping invalid.")) }
                    ?: customerSnapshot.filter { it.name == name && it.phone == row.optString("phone") && it.address == row.optString("address") }
                        .singleOrNull()
                val limit = if (row.has("credit_limit_mga")) requiredMGA(row, "credit_limit_mga")
                    else existing?.creditLimit ?: 0L
                val debt = if (row.has("current_debt_mga")) requiredMGA(row, "current_debt_mga")
                    else existing?.currentDebt ?: 0L
                val local = (existing ?: com.example.data.local.entity.CustomerEntity(
                    name = name, phone = row.optString("phone"), address = row.optString("address"),
                    creditLimit = limit, currentDebt = debt
                )).copy(name = name, phone = row.optString("phone"), address = row.optString("address"),
                    creditLimit = limit, currentDebt = debt)
                val localId = customerDao.insertCustomer(local)
                saveMapping("CUSTOMER", localId.toString(), serverId, row.optLong("version", 1L), now)
            }

            // Snapshot is authoritative for assigned-store stock. Replace only these stores.
            database.storeStockDao().deleteStocksForStores(stores)
            for (i in 0 until stocks.length()) {
                val row = stocks.getJSONObject(i)
                val store = row.getString("store_id")
                require(store in stores) { "Bootstrap stock outside assigned stores." }
                val productServerId = row.getString("product_id")
                val mapping = mappingDao.getByServerId("PRODUCT", productServerId)
                    ?: error("Bootstrap stock product mapping missing: $productServerId")
                val localProductId = mapping.localId.toLongOrNull() ?: error("Stock local product ID invalid.")
                val quantity = row.getInt("quantity")
                require(quantity >= 0) { "Bootstrap stock quantity negative." }
                database.storeStockDao().insertOrUpdateStock(
                    com.example.data.local.entity.StoreStockEntity(store, localProductId, quantity, now)
                )
            }
            val current = syncCursorDao.getCursor() ?: previous
            if (serverCursor < current.lastCursor) database.syncEventInboxDao().deleteEventsAfter(serverCursor)
            val next = current.copy(lastCursor = serverCursor, lastSyncTimestamp = now)
            syncCursorDao.saveCursor(next)
        }
        return syncCursorDao.getCursor() ?: previous.copy(lastCursor = serverCursor, lastSyncTimestamp = now)
    }

    private suspend fun performPull(url: String, bearerToken: String): Int {
        val startingCursor = syncCursorDao.getCursor() ?: SyncCursorEntity()
        var after = startingCursor.lastCursor
        var through: Long? = null
        val allEvents = mutableListOf<SyncEventInboxEntity>()
        var pages = 0
        while (true) {
            require(pages++ < 500) { "Pull nihoatra ny pejy 500; cursor tsy niova." }
            val request = JSONObject().put("afterCursor", after).put("limit", 200)
            if (through != null) request.put("throughCursor", through)
            val response = httpClient.pull(url, request, bearerToken, deviceId)
            require(response.optInt("protocolVersion", -1) == 1) { "Pull protocolVersion tsy mifanaraka." }
            val high = response.optLong("highWatermark", -1L)
            val next = response.optLong("nextCursor", -1L)
            require(high >= 0L && next >= after && next <= high) { "Pull cursor range invalid." }
            if (through == null) {
                require(high >= after) { "Server cursor latsaky ny local cursor." }
                through = high
            } else {
                require(high == through) { "Pull high-watermark niova teo anelanelan'ny pejy." }
            }
            val events = response.optJSONArray("events") ?: error("Pull events tsy array.")
            val hasMore = response.optBoolean("hasMore", false)
            require(events.length() <= 200) { "Pull page mihoatra ny limit." }
            var previousEventCursor = after
            for (i in 0 until events.length()) {
                val row = events.getJSONObject(i)
                val eventCursor = row.getLong("cursor")
                require(eventCursor > previousEventCursor && eventCursor <= next) { "Pull event cursor tsy monotonic." }
                previousEventCursor = eventCursor
                val eventId = row.getString("eventId")
                require(eventId.isNotBlank() && eventId.length <= 128) { "Pull eventId invalid." }
                val store = row.getString("storeId")
                require(store in setOf("A1","A2","A3","A4","A5")) { "Pull storeId invalid." }
                val payload = row.optJSONObject("payload") ?: error("Pull event payload tsy object.")
                allEvents += SyncEventInboxEntity(
                    cursor = eventCursor, eventId = eventId, storeId = store,
                    entityType = row.getString("entityType"), entityId = row.getString("entityId"),
                    eventType = row.getString("eventType"), payloadJson = payload.toString(),
                    createdAt = row.getLong("createdAt") * 1000L
                )
                require(allEvents.size <= 100_000) { "Pull event batch mihoatra ny 100000." }
            }
            if (hasMore) {
                require(next > after && next <= high) { "Pull pagination cursor tsy mandroso." }
                after = next
            } else {
                require(next == high) { "Pull farany tsy nahatratra high-watermark." }
                break
            }
        }
        val finalCursor = through ?: after
        val now = System.currentTimeMillis()
        database.withTransaction {
            if (allEvents.isNotEmpty()) {
                SyncEventProjector(database).project(allEvents)
                database.syncEventInboxDao().insertAll(allEvents)
            }
            val current = syncCursorDao.getCursor() ?: startingCursor
            require(current.lastCursor == startingCursor.lastCursor) { "Sync cursor niova nandritra ny pull." }
            require(finalCursor >= current.lastCursor) { "Pull cursor regression." }
            syncCursorDao.saveCursor(current.copy(lastCursor = finalCursor, lastSyncTimestamp = now))
        }
        return allEvents.size
    }

    private suspend fun saveMapping(type: String, localId: String, serverId: String, version: Long, now: Long) {
        require(isUuid(serverId) && version >= 0L) { "Server mapping invalid." }
        val dao = database.serverIdMappingDao()
        val owner = dao.getAnyByServerId(serverId)
        require(owner == null || (owner.entityType == type && owner.localId == localId)) {
            "Server ID efa mifamatotra amin'ny local entity hafa."
        }
        val existing = dao.getMapping(type, localId)
        require(existing == null || existing.serverId == serverId) { "Local ID efa manana server mapping hafa." }
        dao.upsertMapping(ServerIdMappingEntity(type, localId, serverId, version, now))
    }

    private fun requiredMGA(row: JSONObject, field: String): Long {
        val value = row.opt(field)
        require(value is Number) { "Bootstrap $field tsy MGA integer." }
        val longValue = java.math.BigDecimal(value.toString()).longValueExact()
        require(longValue >= 0L && longValue <= 9_007_199_254_740_991L) { "Bootstrap $field ivelan'ny fetra MGA." }
        return longValue
    }

    private fun isUuid(value: String): Boolean = runCatching {
        java.util.UUID.fromString(value).toString() == value.lowercase()
    }.getOrDefault(false)

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
