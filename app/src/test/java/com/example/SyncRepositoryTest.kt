package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.CustomerEntity
import com.example.data.local.entity.ProductEntity
import com.example.data.local.entity.ServerIdMappingEntity
import com.example.data.local.entity.StoreStockEntity
import com.example.data.local.entity.PendingSyncOperationEntity
import com.example.data.local.entity.SyncCursorEntity
import com.example.data.repository.ProtocolV1SyncHttpClient
import com.example.data.repository.SyncPushResult
import com.example.data.repository.SyncRepositoryImpl
import com.example.data.repository.SyncEventProjector
import com.example.data.local.entity.SyncEventInboxEntity
import com.example.data.security.BearerSessionTokenProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

private abstract class TestProtocolV1SyncClient : ProtocolV1SyncHttpClient {
    override suspend fun pull(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
        val after = body.optLong("afterCursor", 0L)
        return JSONObject().put("protocolVersion", 1).put("events", JSONArray())
            .put("nextCursor", after).put("highWatermark", after).put("hasMore", false)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncRepositoryTest {
    private lateinit var database: FanevaDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FanevaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    private fun testBearerTokenProvider(stores: Set<String> = setOf("A1", "A2", "A3", "A4", "A5")) =
        object : BearerSessionTokenProvider {
            override fun read(): String = "A".repeat(43)
            override fun readStoreIds(): Set<String> = stores
        }

    private fun seedCustomerOperation(mutationId: String = "M-1", stagingUrl: String = "https://worker.example.test") = runBlocking {
        database.syncCursorDao().saveCursor(
            SyncCursorEntity(lastCursor = 10, stagingApiUrl = stagingUrl)
        )
        database.pendingSyncDao().insertOperation(
            PendingSyncOperationEntity(
                mutationId = mutationId,
                entityType = "CUSTOMER",
                action = "CREATE",
                payloadJson = """{"customerId":1,"name":"Client","phone":"","address":"","creditLimit":0}""",
                idempotencyKey = "CUST-1"
            )
        )
    }

    private fun repository(client: ProtocolV1SyncHttpClient, deviceId: String = "device-test",
        stores: Set<String> = setOf("A1", "A2", "A3", "A4", "A5")) =
        SyncRepositoryImpl(database = database, httpClient = client, deviceId = deviceId,
            bearerTokenProvider = testBearerTokenProvider(stores))

    private fun appliedResponse(request: JSONObject): JSONObject {
        val mutation = request.getJSONArray("mutations").getJSONObject(0)
        val result = JSONObject()
            .put("idempotencyKey", mutation.getString("idempotencyKey"))
            .put("status", "APPLIED")
            .put("entityId", mutation.getString("entityId"))
            .put("version", 1)
            .put("serverCursor", 11)
        return JSONObject().put("protocolVersion", 1).put("results", JSONArray().put(result))
    }

    @Test
    fun `protocol v1 APPLIED saves mapping and only marks operation synced after server result`() = runBlocking {
        seedCustomerOperation()
        var endpointSeen = ""
        var requestSeen: JSONObject? = null
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
                endpointSeen = url
                requestSeen = body
                assertEquals("device-test", expectedDeviceId)
                return appliedResponse(body)
            }
        })
        val result = repository.triggerSync()
        assertTrue(result is SyncPushResult.Success)
        assertEquals("https://worker.example.test", endpointSeen)
        assertEquals(1, requestSeen!!.getInt("protocolVersion"))
        val sentMutation = requestSeen!!.getJSONArray("mutations").getJSONObject(0)
        assertFalse("Non-store CUSTOMER mutation must omit storeId, not send null.", sentMutation.has("storeId"))
        val stored = database.pendingSyncDao().getPendingOperationsSnapshot().single()
        assertEquals("SYNCED", stored.status)
        val mapping = database.serverIdMappingDao().getMapping("CUSTOMER", "1")
        assertEquals(sentMutation.getString("entityId"), mapping!!.serverId)
        assertEquals(1L, mapping.serverVersion)
        // Push ACK is not a pull cursor checkpoint.
        assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)
    }



    @Test
    fun `outbox mutations outside encrypted server store grants are deferred without sending`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 1, stagingApiUrl = "https://worker.example.test"))
        database.pendingSyncDao().insertOperation(PendingSyncOperationEntity(
            mutationId = "55555555-5555-4555-8555-555555555555",
            entityType = "SALE", action = "CREATE",
            payloadJson = """{"saleId":"local-sale-a2","saleNumber":"S-A2","storeId":"A2","saleType":"CASH","items":[{"code":"P-A2","qty":1,"price":100}],"totalAmount":100,"paidAmount":100,"remainingAmount":0,"paymentMethod":"CASH","customerId":null}""",
            idempotencyKey = "SALE-A2"
        ))
        var sent = false
        val repo = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
                sent = true
                error("Outbox operation outside granted store must not be sent.")
            }
        }, stores = setOf("A1"))
        val result = repo.triggerSync()
        assertTrue(result is SyncPushResult.Deferred)
        assertFalse(sent)
        assertEquals("PENDING", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
    }

    @Test
    fun `new product queues initial STOCK only for server granted stores`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 1, stagingApiUrl = "https://worker.example.test"))
        val localId = database.productDao().insertProduct(ProductEntity(
            code = "P-STOCK-INIT", name = "Initial stock", category = "Test", purchasePrice = 100L, sellingPrice = 150L
        ))
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A1", localId, 8))
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A2", localId, 3))
        database.pendingSyncDao().insertOperation(PendingSyncOperationEntity(
            mutationId = "44444444-4444-4444-8444-444444444444", entityType = "PRODUCT", action = "CREATE",
            payloadJson = """{"productId":$localId,"code":"P-STOCK-INIT","name":"Initial stock","category":"Test","sellingPrice":150}""",
            idempotencyKey = "PRD-$localId"
        ))
        var pushes = 0
        val repo = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
                pushes++
                val rows = body.getJSONArray("mutations")
                val results = JSONArray()
                for (i in 0 until rows.length()) {
                    val m = rows.getJSONObject(i)
                    results.put(JSONObject().put("idempotencyKey", m.getString("idempotencyKey"))
                        .put("status", "APPLIED").put("entityId", m.getString("entityId")).put("version", 1))
                }
                return JSONObject().put("protocolVersion", 1).put("results", results)
            }
        }, stores = setOf("A1"))
        val result = repo.triggerSync()
        assertTrue(result is SyncPushResult.Success)
        assertEquals(2, pushes)
        val ops = database.pendingSyncDao().getPendingOperationsSnapshot()
        assertEquals(2, ops.size)
        assertTrue(ops.all { it.status == "SYNCED" })
        val stock = ops.single { it.entityType == "STOCK" }
        assertEquals("A1", JSONObject(stock.payloadJson).getString("storeId"))
        assertEquals(8, JSONObject(stock.payloadJson).getInt("quantity"))
        assertEquals(null, database.serverStockVersionDao().getVersion("A2", localId))
        assertEquals(1L, database.serverStockVersionDao().getVersion("A1", localId)!!.serverVersion)
    }

    @Test
    fun `missing result remains failed and does not advance cursor`() = runBlocking {
        seedCustomerOperation()
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                JSONObject().put("protocolVersion", 1).put("results", JSONArray())
        })
        val result = repository.triggerSync()
        assertTrue(result is SyncPushResult.PartialError)
        assertEquals("FAILED", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
        assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `partial push ACK response fails every mutation without applying any ACK`() = runBlocking {
        seedCustomerOperation(mutationId = "M-1")
        database.pendingSyncDao().insertOperation(
            PendingSyncOperationEntity(
                mutationId = "M-2",
                entityType = "CUSTOMER",
                action = "CREATE",
                payloadJson = """{"customerId":2,"name":"Client 2","phone":"","address":"","creditLimit":0}""",
                idempotencyKey = "CUST-2"
            )
        )
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
                val first = body.getJSONArray("mutations").getJSONObject(0)
                return JSONObject().put("protocolVersion", 1).put("results", JSONArray().put(
                    JSONObject().put("idempotencyKey", first.getString("idempotencyKey"))
                        .put("status", "APPLIED").put("entityId", first.getString("entityId"))
                        .put("version", 1).put("serverCursor", 11)
                ))
            }
        })
        val result = repository.triggerSync()
        assertTrue(result is SyncPushResult.PartialError)
        val operations = database.pendingSyncDao().getPendingOperationsSnapshot()
        assertEquals(2, operations.size)
        assertTrue(operations.all { it.status == "FAILED" })
        assertEquals(null, database.serverIdMappingDao().getMapping("CUSTOMER", "1"))
        assertEquals(null, database.serverIdMappingDao().getMapping("CUSTOMER", "2"))
        assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `REJECTED is terminal and is not automatically retried`() = runBlocking {
        seedCustomerOperation()
        var calls = 0
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
                calls++
                val key = body.getJSONArray("mutations").getJSONObject(0).getString("idempotencyKey")
                return JSONObject().put("protocolVersion", 1).put("results", JSONArray().put(
                    JSONObject().put("idempotencyKey", key).put("status", "REJECTED").put("error", "PERMISSION_DENIED")
                ))
            }
        })
        assertTrue(repository.triggerSync() is SyncPushResult.PartialError)
        assertEquals("REJECTED", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
        assertTrue(repository.triggerSync() is SyncPushResult.PartialError)
        assertEquals(1, calls)
    }

    @Test
    fun `CONFLICT is held for reconciliation and not retried`() = runBlocking {
        seedCustomerOperation()
        var calls = 0
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
                calls++
                val key = body.getJSONArray("mutations").getJSONObject(0).getString("idempotencyKey")
                return JSONObject().put("protocolVersion", 1).put("results", JSONArray().put(
                    JSONObject().put("idempotencyKey", key).put("status", "CONFLICT")
                        .put("error", "VERSION_CONFLICT").put("serverVersion", 3)
                ))
            }
        })
        assertTrue(repository.triggerSync() is SyncPushResult.PartialError)
        assertEquals("CONFLICT", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
        assertTrue(repository.triggerSync() is SyncPushResult.PartialError)
        assertEquals(1, calls)
    }

    @Test
    fun `unmapped dependent SALE is deferred without sending or changing outbox status`() = runBlocking {
        database.syncCursorDao().saveCursor(
            SyncCursorEntity(lastCursor = 1, stagingApiUrl = "https://worker.example.test")
        )
        database.pendingSyncDao().insertOperation(
            PendingSyncOperationEntity(
                mutationId = "M-SALE",
                entityType = "SALE",
                action = "CREATE",
                payloadJson = """{"saleId":"local-sale","saleNumber":"S-1","storeId":"A1","saleType":"CASH","items":[{"code":"P-1","qty":1,"price":100}],"totalAmount":100,"paidAmount":100,"remainingAmount":0,"paymentMethod":"CASH","customerId":null}""",
                idempotencyKey = "SALE-1"
            )
        )
        var called = false
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
                called = true
                error("No mutation should be sent without its PRODUCT mapping.")
            }
        })
        val result = repository.triggerSync()
        assertTrue(result is SyncPushResult.Deferred)
        assertFalse(called)
        assertEquals("PENDING", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
    }

    @Test
    fun `multi page pull commits inbox and cursor only after final page`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 10, stagingApiUrl = "https://worker.example.test"))
        val firstLocalId = database.productDao().insertProduct(ProductEntity(
            code = "P-PAGE-11", name = "Page product 11", category = "Test", purchasePrice = 1L, sellingPrice = 2L
        ))
        val secondLocalId = database.productDao().insertProduct(ProductEntity(
            code = "P-PAGE-12", name = "Page product 12", category = "Test", purchasePrice = 1L, sellingPrice = 2L
        ))
        database.serverIdMappingDao().insertMapping(ServerIdMappingEntity(
            "PRODUCT", firstLocalId.toString(), "11111111-1111-4111-8111-111111111111"
        ))
        database.serverIdMappingDao().insertMapping(ServerIdMappingEntity(
            "PRODUCT", secondLocalId.toString(), "22222222-2222-4222-8222-222222222222"
        ))
        var calls = 0
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                error("No outbox mutations expected")
            override suspend fun pull(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
                calls++
                if (calls == 1) {
                    assertEquals(10L, body.getLong("afterCursor"))
                    return JSONObject().put("protocolVersion", 1)
                        .put("events", JSONArray().put(JSONObject()
                            .put("cursor", 11).put("eventId", "evt-11").put("storeId", "A1")
                            .put("entityType", "STOCK").put("entityId", "11111111-1111-4111-8111-111111111111")
                            .put("eventType", "UPSERT").put("payload", JSONObject().put("quantity", 8))
                            .put("createdAt", 100)))
                        .put("nextCursor", 11).put("highWatermark", 12).put("hasMore", true)
                }
                assertEquals(11L, body.getLong("afterCursor"))
                assertEquals(12L, body.getLong("throughCursor"))
                return JSONObject().put("protocolVersion", 1)
                    .put("events", JSONArray().put(JSONObject()
                        .put("cursor", 12).put("eventId", "evt-12").put("storeId", "A1")
                        .put("entityType", "STOCK").put("entityId", "22222222-2222-4222-8222-222222222222")
                        .put("eventType", "UPSERT").put("payload", JSONObject().put("quantity", 4))
                        .put("createdAt", 101)))
                    .put("nextCursor", 12).put("highWatermark", 12).put("hasMore", false)
            }
        })
        val result = repository.triggerSync()
        assertTrue(result is SyncPushResult.Success)
        assertEquals(2, (result as SyncPushResult.Success).pulledEventCount)
        assertEquals(12L, database.syncCursorDao().getCursor()!!.lastCursor)
        assertEquals(2, database.syncEventInboxDao().countAll())
    }

    @Test
    fun `invalid later pull page leaves cursor and inbox unchanged`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 10, stagingApiUrl = "https://worker.example.test"))
        var calls = 0
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                error("No outbox mutations expected")
            override suspend fun pull(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
                calls++
                return if (calls == 1) JSONObject().put("protocolVersion", 1)
                    .put("events", JSONArray().put(JSONObject()
                        .put("cursor", 11).put("eventId", "evt-11").put("storeId", "A1")
                        .put("entityType", "STOCK").put("entityId", "11111111-1111-4111-8111-111111111111")
                        .put("eventType", "UPSERT").put("payload", JSONObject().put("quantity", 8))
                        .put("createdAt", 100)))
                    .put("nextCursor", 11).put("highWatermark", 12).put("hasMore", true)
                else JSONObject().put("protocolVersion", 2).put("events", JSONArray())
                    .put("nextCursor", 12).put("highWatermark", 12).put("hasMore", false)
            }
        })
        assertTrue(repository.triggerSync() is SyncPushResult.PartialError)
        assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)
        assertEquals(0, database.syncEventInboxDao().countAll())
    }

    @Test
    fun `initial bootstrap installs products stock mappings and server cursor atomically`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 0, stagingApiUrl = "https://worker.example.test"))
        val productId = "11111111-1111-4111-8111-111111111111"
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                error("No pending operations expected")
            override suspend fun bootstrap(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                JSONObject().put("protocolVersion", 1).put("cursor", 5).put("storeIds", JSONArray().put("A1"))
                    .put("snapshot", JSONObject()
                        .put("products", JSONArray().put(JSONObject()
                            .put("product_id", productId).put("product_code", "P-001").put("name", "Produit test")
                            .put("category", "Test").put("selling_price_mga", 2500).put("purchase_price_mga", 1800)
                            .put("is_active", 1).put("version", 2).put("updated_at", 100)))
                        .put("customers", JSONArray())
                        .put("stocks", JSONArray().put(JSONObject()
                            .put("store_id", "A1").put("product_id", productId).put("quantity", 7)
                            .put("version", 3).put("updated_at", 100))))
        }, stores = setOf("A1"))
        val result = repository.triggerSync()
        assertTrue(result is SyncPushResult.Success)
        assertEquals(5L, database.syncCursorDao().getCursor()!!.lastCursor)
        val product = database.productDao().getProductByCode("P-001")!!
        assertEquals("Produit test", product.name)
        assertEquals(2500L, product.sellingPrice)
        assertEquals(7, database.storeStockDao().getStock(product.id, "A1")!!.quantity)
        assertEquals(productId, database.serverIdMappingDao().getMapping("PRODUCT", product.id.toString())!!.serverId)
        assertEquals(3L, database.serverStockVersionDao().getVersion("A1", product.id)!!.serverVersion)
    }


    @Test
    fun `remote SALE event replay with fresh event ID applies stock and debt once`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 1, stagingApiUrl = "https://worker.example.test"))
        val productId = database.productDao().insertProduct(ProductEntity(
            code = "P-1", name = "Produit 1", category = "Test", purchasePrice = 50L, sellingPrice = 100L
        ))
        val productServerId = "11111111-1111-4111-8111-111111111111"
        database.serverIdMappingDao().insertMapping(ServerIdMappingEntity("PRODUCT", productId.toString(), productServerId))
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A1", productId, 5))
        val customerId = database.customerDao().insertCustomer(CustomerEntity(
            name = "Client vente", phone = "", address = "", creditLimit = 10000L, currentDebt = 0L
        ))
        val customerServerId = "12121212-1212-4212-8212-121212121212"
        database.serverIdMappingDao().insertMapping(ServerIdMappingEntity("CUSTOMER", customerId.toString(), customerServerId))
        val saleId = "22222222-2222-4222-8222-222222222222"
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                error("No outbox mutations expected")
            override suspend fun pull(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
                val line = JSONObject()
                    .put("productId", productServerId).put("productCode", "P-1")
                    .put("productName", "Produit 1").put("quantity", 2)
                    .put("unitPriceMga", 100).put("lineTotalMga", 200)
                val payload = JSONObject()
                    .put("mutationId", "sale-key-1").put("saleId", saleId)
                    .put("saleNumber", "S-REMOTE-1").put("actorPrincipalId", "admin-2")
                    .put("customerId", customerServerId).put("paymentMethod", "CREDIT")
                    .put("totalMga", 200).put("paidMga", 50).put("remainingMga", 150)
                    .put("saleType", "CREDIT")
                    .put("stockVersions", JSONArray().put(JSONObject()
                        .put("productId", productServerId).put("storeId", "A1").put("quantity", 3).put("version", 4)))
                    .put("lines", JSONArray().put(line))
                val first = JSONObject()
                    .put("cursor", 2).put("eventId", "evt-sale-2").put("storeId", "A1")
                    .put("entityType", "SALE").put("entityId", saleId).put("eventType", "UPSERT")
                    .put("payload", payload).put("createdAt", 100)
                val replay = JSONObject()
                    .put("cursor", 3).put("eventId", "evt-sale-replay-fresh").put("storeId", "A1")
                    .put("entityType", "SALE").put("entityId", saleId).put("eventType", "UPSERT")
                    .put("payload", payload).put("createdAt", 101)
                return JSONObject().put("protocolVersion", 1)
                    .put("events", JSONArray().put(first).put(replay))
                    .put("nextCursor", 3).put("highWatermark", 3).put("hasMore", false)
            }
        })
        val syncResult = repository.triggerSync()
        assertTrue("Expected successful redacted SALE projection, got: $syncResult", syncResult is SyncPushResult.Success)
        val sale = database.saleDao().getSaleById(saleId)!!
        assertEquals("S-REMOTE-1", sale.saleNumber)
        assertEquals(200L, sale.totalAmount)
        assertEquals(1, database.saleDao().getSaleItems(saleId).size)
        assertEquals(3, database.storeStockDao().getStock(productId, "A1")!!.quantity)
        assertEquals(4L, database.serverStockVersionDao().getVersion("A1", productId)!!.serverVersion)
        assertEquals(1, database.stockMovementDao().countAll())
        assertEquals(150L, database.customerDao().getCustomerById(customerId)!!.currentDebt)
        assertEquals(3L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `invalid remote SALE totals roll back projection inbox and cursor`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 1, stagingApiUrl = "https://worker.example.test"))
        val localProductId = database.productDao().insertProduct(ProductEntity(
            code = "P-BAD-SALE", name = "Produit test", category = "Test", purchasePrice = 50L, sellingPrice = 100L
        ))
        val productServerId = "77777777-7777-4777-8777-777777777777"
        database.serverIdMappingDao().insertMapping(ServerIdMappingEntity("PRODUCT", localProductId.toString(), productServerId))
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A1", localProductId, 5))
        val saleId = "88888888-8888-4888-8888-888888888888"
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                error("No outbox mutations expected")
            override suspend fun pull(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                JSONObject().put("protocolVersion", 1).put("events", JSONArray().put(JSONObject()
                    .put("cursor", 2).put("eventId", "evt-bad-sale").put("storeId", "A1")
                    .put("entityType", "SALE").put("entityId", saleId).put("eventType", "UPSERT")
                    .put("payload", JSONObject().put("saleId", saleId).put("saleNumber", "S-BAD")
                        .put("saleType", "CASH").put("paymentMethod", "CASH").put("actorPrincipalId", "admin-2")
                        .put("totalMga", 250).put("paidMga", 250).put("remainingMga", 0)
                        .put("lines", JSONArray().put(JSONObject().put("productId", productServerId)
                            .put("productCode", "P-BAD-SALE").put("productName", "Produit test")
                            .put("quantity", 2).put("unitPriceMga", 100).put("lineTotalMga", 200))))
                    .put("createdAt", 100)))
                    .put("nextCursor", 2).put("highWatermark", 2).put("hasMore", false)
        })
        assertTrue(repository.triggerSync() is SyncPushResult.PartialError)
        assertEquals(5, database.storeStockDao().getStock(localProductId, "A1")!!.quantity)
        assertEquals(0, database.saleDao().countAll())
        assertEquals(0, database.syncEventInboxDao().countAll())
        assertEquals(1L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `remote SALE replay with changed payment payload rolls back the complete pull`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 1, stagingApiUrl = "https://worker.example.test"))
        val productId = database.productDao().insertProduct(ProductEntity(
            code = "P-REPLAY", name = "Produit replay", category = "Test", purchasePrice = 10L, sellingPrice = 100L
        ))
        val productServerId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        database.serverIdMappingDao().insertMapping(ServerIdMappingEntity("PRODUCT", productId.toString(), productServerId))
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A1", productId, 5))
        val customerId = database.customerDao().insertCustomer(CustomerEntity(
            name = "Client replay", phone = "", address = "", creditLimit = 10000L
        ))
        val customerServerId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        database.serverIdMappingDao().insertMapping(ServerIdMappingEntity("CUSTOMER", customerId.toString(), customerServerId))
        val saleId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                error("No outbox mutations expected")
            override suspend fun pull(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
                fun event(eventId: String, method: String, cursor: Int) = JSONObject()
                    .put("cursor", cursor).put("eventId", eventId).put("storeId", "A1")
                    .put("entityType", "SALE").put("entityId", saleId).put("eventType", "UPSERT")
                    .put("payload", JSONObject().put("mutationId", "sale-replay-key").put("saleId", saleId)
                        .put("saleNumber", "S-REPLAY").put("actorPrincipalId", "admin-2")
                        .put("customerId", customerServerId).put("paymentMethod", method)
                        .put("totalMga", 200).put("paidMga", 50).put("remainingMga", 150)
                        .put("saleType", "CREDIT").put("lines", JSONArray().put(JSONObject()
                            .put("productId", productServerId).put("productCode", "P-REPLAY")
                            .put("productName", "Produit replay").put("quantity", 2)
                            .put("unitPriceMga", 100).put("lineTotalMga", 200))))
                    .put("createdAt", 100)
                return JSONObject().put("protocolVersion", 1)
                    .put("events", JSONArray().put(event("evt-sale-original", "CREDIT", 2))
                        .put(event("evt-sale-mutated-replay", "MVOLA", 3)))
                    .put("nextCursor", 3).put("highWatermark", 3).put("hasMore", false)
            }
        })
        assertTrue(repository.triggerSync() is SyncPushResult.PartialError)
        assertEquals(5, database.storeStockDao().getStock(productId, "A1")!!.quantity)
        assertEquals(0, database.saleDao().countAll())
        assertEquals(0, database.stockMovementDao().countAll())
        assertEquals(0, database.syncEventInboxDao().countAll())
        assertEquals(0L, database.customerDao().getCustomerById(customerId)!!.currentDebt)
        assertEquals(1L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `VENDEUR redacted credit SALE projects history without customer or debt data`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 1, stagingApiUrl = "https://worker.example.test"))
        val productId = database.productDao().insertProduct(ProductEntity(
            code = "P-VENDOR", name = "Produit vendeur", category = "Test", purchasePrice = 10L, sellingPrice = 100L
        ))
        val productServerId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        database.serverIdMappingDao().insertMapping(ServerIdMappingEntity("PRODUCT", productId.toString(), productServerId))
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A1", productId, 5))
        val saleId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                error("No outbox mutations expected")
            override suspend fun pull(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                JSONObject().put("protocolVersion", 1).put("events", JSONArray().put(JSONObject()
                    .put("cursor", 2).put("eventId", "evt-vendor-credit-sale").put("storeId", "A1")
                    .put("entityType", "SALE").put("entityId", saleId).put("eventType", "UPSERT")
                    .put("payload", JSONObject().put("mutationId", "vendor-sale-key").put("saleId", saleId)
                        .put("saleNumber", "S-VENDOR").put("actorPrincipalId", "vendor-1")
                        .put("paymentMethod", "CREDIT").put("totalMga", 200).put("paidMga", 50)
                        .put("customerRedacted", true).put("saleType", "CREDIT")
                        .put("lines", JSONArray().put(JSONObject().put("productId", productServerId)
                            .put("productCode", "P-VENDOR").put("productName", "Produit vendeur")
                            .put("quantity", 2).put("unitPriceMga", 100).put("lineTotalMga", 200))))
                    .put("createdAt", 100)))
                    .put("nextCursor", 2).put("highWatermark", 2).put("hasMore", false)
        })
        val syncResult = repository.triggerSync()
        println("VENDOR_REDACTED_SALE_SYNC_RESULT=$syncResult")
        assertTrue("Expected VENDEUR redacted SALE projection success, got: $syncResult", syncResult is SyncPushResult.Success)
        val sale = database.saleDao().getSaleById(saleId)!!
        assertEquals("CREDIT", sale.saleType)
        assertEquals(null, sale.customerId)
        assertEquals(null, sale.customerName)
        assertEquals(0L, sale.remainingAmount)
        assertEquals(3, database.storeStockDao().getStock(productId, "A1")!!.quantity)
        assertEquals(0, database.customerDao().countAll())
        assertEquals(2L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `remote CUSTOMER upsert updates mapped row without creating duplicate`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 1, stagingApiUrl = "https://worker.example.test"))
        val localId = database.customerDao().insertCustomer(CustomerEntity(
            name = "Ancien nom", phone = "", address = "", creditLimit = 10000L, currentDebt = 3500L
        ))
        val serverId = "99999999-9999-4999-8999-999999999999"
        database.serverIdMappingDao().insertMapping(ServerIdMappingEntity("CUSTOMER", localId.toString(), serverId))
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                error("No outbox mutations expected")
            override suspend fun pull(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                JSONObject().put("protocolVersion", 1).put("events", JSONArray().put(JSONObject()
                    .put("cursor", 2).put("eventId", "evt-customer-update").put("storeId", "A1")
                    .put("entityType", "CUSTOMER").put("entityId", serverId).put("eventType", "UPSERT")
                    .put("payload", JSONObject().put("entityId", serverId).put("version", 2)
                        .put("payload", JSONObject().put("displayName", "Anarana vaovao")
                            .put("phone", "0340000000").put("address", "A1")
                            .put("creditLimitMga", 20000)))
                    .put("createdAt", 100)))
                    .put("nextCursor", 2).put("highWatermark", 2).put("hasMore", false)
        })
        assertTrue(repository.triggerSync() is SyncPushResult.Success)
        val customers = database.customerDao().getAllCustomersSnapshot()
        assertEquals(1, customers.size)
        assertEquals(localId, customers.single().id)
        assertEquals("Anarana vaovao", customers.single().name)
        assertEquals(3500L, customers.single().currentDebt)
        assertEquals(20000L, customers.single().creditLimit)
    }

    @Test
    fun `remote TRANSFER events project both assigned store stock effects once`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 1, stagingApiUrl = "https://worker.example.test"))
        val productId = database.productDao().insertProduct(ProductEntity(
            code = "P-2", name = "Produit 2", category = "Test", purchasePrice = 50L, sellingPrice = 100L
        ))
        val productServerId = "33333333-3333-4333-8333-333333333333"
        database.serverIdMappingDao().insertMapping(ServerIdMappingEntity("PRODUCT", productId.toString(), productServerId))
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A1", productId, 5))
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A2", productId, 1))
        val transferId = "44444444-4444-4444-8444-444444444444"
        val transferPayload = JSONObject().put("mutationId", "transfer-key-1").put("transferId", transferId)
            .put("productId", productServerId).put("fromStoreId", "A1").put("toStoreId", "A2")
            .put("quantity", 2).put("reason", "Rééquilibrage").put("actorPrincipalId", "admin-2")
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                error("No outbox mutations expected")
            override suspend fun pull(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                JSONObject().put("protocolVersion", 1).put("events", JSONArray()
                    .put(JSONObject().put("cursor", 2).put("eventId", "evt-transfer-out").put("storeId", "A1")
                        .put("entityType", "TRANSFER").put("entityId", transferId).put("eventType", "UPSERT")
                        .put("payload", transferPayload).put("createdAt", 100))
                    .put(JSONObject().put("cursor", 3).put("eventId", "evt-transfer-in").put("storeId", "A2")
                        .put("entityType", "TRANSFER").put("entityId", transferId).put("eventType", "UPSERT")
                        .put("payload", transferPayload).put("createdAt", 101))
                    // Same logical A1 endpoint replayed with a fresh eventId.
                    .put(JSONObject().put("cursor", 4).put("eventId", "evt-transfer-out-replay").put("storeId", "A1")
                        .put("entityType", "TRANSFER").put("entityId", transferId).put("eventType", "UPSERT")
                        .put("payload", transferPayload).put("createdAt", 102)))
                    .put("nextCursor", 4).put("highWatermark", 4).put("hasMore", false)
        })
        assertTrue(repository.triggerSync() is SyncPushResult.Success)
        assertEquals(3, database.storeStockDao().getStock(productId, "A1")!!.quantity)
        assertEquals(3, database.storeStockDao().getStock(productId, "A2")!!.quantity)
        assertEquals("COMPLETED", database.transferRequestDao().getByIdempotencyKey("transfer-key-1")!!.status)
        assertEquals(2, database.stockMovementDao().countAll())
        assertEquals(4L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `remote DEBT_PAYMENT replay with fresh event ID reduces debt once`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 1, stagingApiUrl = "https://worker.example.test"))
        val customerId = database.customerDao().insertCustomer(CustomerEntity(
            name = "Client distant", phone = "", address = "", creditLimit = 10000L, currentDebt = 5000L
        ))
        val customerServerId = "55555555-5555-4555-8555-555555555555"
        database.serverIdMappingDao().insertMapping(ServerIdMappingEntity("CUSTOMER", customerId.toString(), customerServerId))
        val paymentId = "66666666-6666-4666-8666-666666666666"
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                error("No outbox mutations expected")
            override suspend fun pull(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
                val payload = JSONObject().put("mutationId", "payment-key-1").put("paymentId", paymentId)
                    .put("customerId", customerServerId).put("amountMga", 2000).put("paymentMethod", "CASH")
                    .put("note", "Paiement reçu").put("actorPrincipalId", "admin-2")
                val first = JSONObject().put("cursor", 2).put("eventId", "evt-payment-2").put("storeId", "A1")
                    .put("entityType", "DEBT_PAYMENT").put("entityId", paymentId).put("eventType", "UPSERT")
                    .put("payload", payload).put("createdAt", 100)
                val replay = JSONObject().put("cursor", 3).put("eventId", "evt-payment-replay-fresh").put("storeId", "A1")
                    .put("entityType", "DEBT_PAYMENT").put("entityId", paymentId).put("eventType", "UPSERT")
                    .put("payload", payload).put("createdAt", 101)
                return JSONObject().put("protocolVersion", 1).put("events", JSONArray().put(first).put(replay))
                    .put("nextCursor", 3).put("highWatermark", 3).put("hasMore", false)
            }
        })
        assertTrue(repository.triggerSync() is SyncPushResult.Success)
        assertEquals(3000L, database.customerDao().getCustomerById(customerId)!!.currentDebt)
        val payment = database.debtPaymentDao().getPaymentByIdempotencyKey("payment-key-1")!!
        assertEquals(2000L, payment.amountPaid)
        assertEquals(paymentId, payment.receiptNumber)
        assertEquals(1, database.debtPaymentDao().countAll())
        assertEquals(3L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `blank server configuration never reports success or calls transport`() = runBlocking {
        seedCustomerOperation(stagingUrl = "")
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
                error("Le client ne doit pas être appelé")
            }
        })
        assertTrue(repository.triggerSync() is SyncPushResult.ServerNotConfigured)
        assertEquals("PENDING", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
    }
    @Test
    fun `self-originated event echo is ignored by Room outbox mutation UUID`() = runBlocking {
        val mutationId = "123e4567-e89b-42d3-a456-426614174000"
        database.pendingSyncDao().insertOperation(
            PendingSyncOperationEntity(
                mutationId = mutationId,
                entityType = "PRODUCT",
                action = "CREATE",
                payloadJson = "{}",
                idempotencyKey = "PRD-LOCAL-1",
                status = "SYNCED"
            )
        )
        val event = SyncEventInboxEntity(
            cursor = 11L,
            eventId = "evt-self-echo",
            storeId = "A1",
            entityType = "PRODUCT",
            entityId = "223e4567-e89b-42d3-a456-426614174000",
            eventType = "UPSERT",
            payloadJson = JSONObject()
                .put("mutationId", mutationId)
                .put("idempotencyKey", "PRD-LOCAL-1")
                .put("payload", JSONObject())
                .toString(),
            createdAt = 1_800_000_000L
        )
        SyncEventProjector(database).project(listOf(event))
        assertEquals(0, database.productDao().countAll())
    }

}
