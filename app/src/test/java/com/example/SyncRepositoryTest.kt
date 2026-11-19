package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
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
        val repository = repository(object : ProtocolV1SyncHttpClient {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
                endpointSeen = url
                requestSeen = body
                assertEquals("device-test", expectedDeviceId)
                return appliedResponse(body)
            }
        })
        val result = repository.triggerSync()
        assertTrue(result is SyncPushResult.Success)
        assertEquals("https://worker.example.test/v1/sync/push", endpointSeen)
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
        val repository = repository(object : ProtocolV1SyncHttpClient {
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
        val repository = repository(object : ProtocolV1SyncHttpClient {
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
        val repository = repository(object : ProtocolV1SyncHttpClient {
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
            SyncCursorEntity(lastCursor = 0, stagingApiUrl = "https://worker.example.test")
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
        val repository = repository(object : ProtocolV1SyncHttpClient {
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
    fun `blank server configuration never reports success or calls transport`() = runBlocking {
        seedCustomerOperation(stagingUrl = "")
        val repository = repository(object : ProtocolV1SyncHttpClient {
            override suspend fun push(url: String, body: JSONObject, bearerToken: String, expectedDeviceId: String): JSONObject {
                error("Le client ne doit pas être appelé")
            }
        })
        assertTrue(repository.triggerSync() is SyncPushResult.ServerNotConfigured)
        assertEquals("PENDING", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
    }
}
