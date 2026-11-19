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

    private fun testBearerTokenProvider() = object : BearerSessionTokenProvider {
        override fun read(): String = "A".repeat(43)
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

    private fun repository(client: ProtocolV1SyncHttpClient, deviceId: String = "device-test") =
        SyncRepositoryImpl(
            database = database,
            httpClient = client,
            deviceId = deviceId,
            bearerTokenProvider = testBearerTokenProvider()
        )

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
        })
        val result = repository.triggerSync()
        assertTrue(result is SyncPushResult.Success)
        assertEquals(5L, database.syncCursorDao().getCursor()!!.lastCursor)
        val product = database.productDao().getProductByCode("P-001")!!
        assertEquals("Produit test", product.name)
        assertEquals(2500.0, product.sellingPrice, 0.0)
        assertEquals(7, database.storeStockDao().getStock(product.id, "A1")!!.quantity)
        assertEquals(productId, database.serverIdMappingDao().getMapping("PRODUCT", product.id.toString())!!.serverId)
    }


    @Test
    fun `remote SALE event projects history lines and stock atomically`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 1, stagingApiUrl = "https://worker.example.test"))
        val productId = database.productDao().insertProduct(ProductEntity(
            code = "P-1", name = "Produit 1", category = "Test", purchasePrice = 50.0, sellingPrice = 100.0
        ))
        val productServerId = "11111111-1111-4111-8111-111111111111"
        database.serverIdMappingDao().insertMapping(ServerIdMappingEntity("PRODUCT", productId.toString(), productServerId))
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A1", productId, 5))
        val saleId = "22222222-2222-4222-8222-222222222222"
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                error("No outbox mutations expected")
            override suspend fun pull(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                JSONObject().put("protocolVersion", 1)
                    .put("events", JSONArray().put(JSONObject()
                        .put("cursor", 2).put("eventId", "evt-sale-2").put("storeId", "A1")
                        .put("entityType", "SALE").put("entityId", saleId).put("eventType", "UPSERT")
                        .put("payload", JSONObject()
                            .put("saleId", saleId).put("saleNumber", "S-REMOTE-1").put("actorPrincipalId", "admin-2")
                            .put("paymentMethod", "CASH").put("totalMga", 200).put("paidMga", 200)
                            .put("remainingMga", 0).put("saleType", "CASH")
                            .put("lines", JSONArray().put(JSONObject()
                                .put("productId", productServerId).put("productCode", "P-1")
                                .put("productName", "Produit 1").put("quantity", 2)
                                .put("unitPriceMga", 100).put("lineTotalMga", 200)))))
                    .put("nextCursor", 2).put("highWatermark", 2).put("hasMore", false)
        })
        assertTrue(repository.triggerSync() is SyncPushResult.Success)
        val sale = database.saleDao().getSaleById(saleId)!!
        assertEquals("S-REMOTE-1", sale.saleNumber)
        assertEquals(200.0, sale.totalAmount, 0.0)
        assertEquals(1, database.saleDao().getSaleItems(saleId).size)
        assertEquals(3, database.storeStockDao().getStock(productId, "A1")!!.quantity)
        assertEquals(1, database.stockMovementDao().countAll())
    }

    @Test
    fun `remote TRANSFER events project both assigned store stock effects once`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 1, stagingApiUrl = "https://worker.example.test"))
        val productId = database.productDao().insertProduct(ProductEntity(
            code = "P-2", name = "Produit 2", category = "Test", purchasePrice = 50.0, sellingPrice = 100.0
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
                        .put("payload", transferPayload).put("createdAt", 101)))
                    .put("nextCursor", 3).put("highWatermark", 3).put("hasMore", false)
        })
        assertTrue(repository.triggerSync() is SyncPushResult.Success)
        assertEquals(3, database.storeStockDao().getStock(productId, "A1")!!.quantity)
        assertEquals(3, database.storeStockDao().getStock(productId, "A2")!!.quantity)
        assertEquals("COMPLETED", database.transferRequestDao().getByIdempotencyKey("transfer-key-1")!!.status)
        assertEquals(2, database.stockMovementDao().countAll())
    }

    @Test
    fun `remote DEBT_PAYMENT projects payment ledger balance and receipt`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 1, stagingApiUrl = "https://worker.example.test"))
        val customerId = database.customerDao().insertCustomer(CustomerEntity(
            name = "Client distant", phone = "", address = "", creditLimit = 10000.0, currentDebt = 5000.0
        ))
        val customerServerId = "55555555-5555-4555-8555-555555555555"
        database.serverIdMappingDao().insertMapping(ServerIdMappingEntity("CUSTOMER", customerId.toString(), customerServerId))
        val paymentId = "66666666-6666-4666-8666-666666666666"
        val repository = repository(object : TestProtocolV1SyncClient() {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                error("No outbox mutations expected")
            override suspend fun pull(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String) =
                JSONObject().put("protocolVersion", 1).put("events", JSONArray().put(JSONObject()
                    .put("cursor", 2).put("eventId", "evt-payment-2").put("storeId", "A1")
                    .put("entityType", "DEBT_PAYMENT").put("entityId", paymentId).put("eventType", "UPSERT")
                    .put("payload", JSONObject().put("mutationId", "payment-key-1").put("paymentId", paymentId)
                        .put("customerId", customerServerId).put("amountMga", 2000).put("paymentMethod", "CASH")
                        .put("note", "Paiement reçu").put("actorPrincipalId", "admin-2"))
                    .put("createdAt", 100)))
                    .put("nextCursor", 2).put("highWatermark", 2).put("hasMore", false)
        })
        assertTrue(repository.triggerSync() is SyncPushResult.Success)
        assertEquals(3000.0, database.customerDao().getCustomerById(customerId)!!.currentDebt, 0.0)
        val payment = database.debtPaymentDao().getPaymentByIdempotencyKey("payment-key-1")!!
        assertEquals(2000.0, payment.amountPaid, 0.0)
        assertEquals(paymentId, payment.receiptNumber)
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
}
