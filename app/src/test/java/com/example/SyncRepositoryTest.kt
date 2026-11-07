package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.PendingSyncOperationEntity
import com.example.data.local.entity.CustomerEntity
import com.example.data.local.entity.SaleEntity
import com.example.data.local.entity.SyncCursorEntity
import com.example.data.repository.SyncBatchRequest
import com.example.data.repository.SyncBatchResponse
import com.example.data.repository.SyncHttpClient
import com.example.data.repository.SyncPushResult
import com.example.data.repository.SyncRepositoryImpl
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun seedOperation(mutationId: String = "M-1") = runBlocking {
        database.syncCursorDao().saveCursor(
            SyncCursorEntity(lastCursor = 10, stagingApiUrl = "https://worker.example.test/sync")
        )
        database.pendingSyncDao().insertOperation(
            PendingSyncOperationEntity(
                mutationId = mutationId,
                entityType = "CUSTOMER",
                action = "CREATE",
                payloadJson = "{\"name\":\"Client\"}",
                idempotencyKey = "CUST-1"
            )
        )
    }

    @Test
    fun `only acknowledged operation becomes synced and cursor advances`() = runBlocking {
        seedOperation()
        var requestSeen: SyncBatchRequest? = null
        val repository = SyncRepositoryImpl(
            database,
            object : SyncHttpClient {
                override suspend fun push(url: String, request: SyncBatchRequest): SyncBatchResponse {
                    requestSeen = request
                    return SyncBatchResponse(true, listOf("M-1"), emptyMap(), 11, "OK")
                }
            },
            deviceId = "test-installation-device-001"
        )
        val result = repository.triggerSync()
        assertTrue(result is SyncPushResult.Success)
        assertEquals("test-installation-device-001", requestSeen!!.deviceId)
        assertEquals("SYNCED", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
        assertEquals(11L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `missing acknowledgement is failed and cursor does not advance`() = runBlocking {
        seedOperation()
        val repository = SyncRepositoryImpl(
            database,
            object : SyncHttpClient {
                override suspend fun push(url: String, request: SyncBatchRequest) =
                    SyncBatchResponse(true, emptyList(), emptyMap(), 11, "ACK absent")
            },
            deviceId = "test-installation-device-test")
        val result = repository.triggerSync()
        assertTrue(result is SyncPushResult.PartialError)
        assertEquals("FAILED", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
        assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `all acknowledged operations succeed when next cursor is null`() = runBlocking {
        seedOperation()

        val repository = repositoryReturning(
            SyncBatchResponse(true, listOf("M-1"), emptyMap(), null, "OK")
        )

        val result = repository.triggerSync()

        assertTrue(result is SyncPushResult.Success)
        assertEquals(
            "SYNCED",
            database.pendingSyncDao().getPendingOperationsSnapshot().single().status
        )
        assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `regressive server cursor reports protocol error but preserves acknowledged operation`() = runBlocking {
        seedOperation()

        val repository = repositoryReturning(
            SyncBatchResponse(true, listOf("M-1"), emptyMap(), 9, "OK")
        )

        val result = repository.triggerSync()

        assertTrue(result is SyncPushResult.ProtocolError)
        assertEquals(1, (result as SyncPushResult.ProtocolError).syncedCount)
        assertEquals(
            "SYNCED",
            database.pendingSyncDao().getPendingOperationsSnapshot().single().status
        )
        assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `blank server configuration never reports success`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(stagingApiUrl = ""))
        database.pendingSyncDao().insertOperation(
            PendingSyncOperationEntity("M-2", "PRODUCT", "CREATE", "{}", "PRD-1")
        )
        val repository = SyncRepositoryImpl(
            database,
            object : SyncHttpClient {
                override suspend fun push(url: String, request: SyncBatchRequest): SyncBatchResponse {
                    error("Le client ne doit pas être appelé")
                }
            },
            deviceId = "test-installation-device-test")
        assertTrue(repository.triggerSync() is SyncPushResult.ServerNotConfigured)
        assertEquals("PENDING", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
    }


    private suspend fun seedSaleOperation(
        status: String = "PENDING_SYNC",
        saleType: String = "CREDIT",
        includeSale: Boolean = true
    ) {
        database.syncCursorDao().saveCursor(
            SyncCursorEntity(
                lastCursor = 10,
                stagingApiUrl = "https://worker.example.test/sync"
            )
        )

        val customerId = database.customerDao().insertCustomer(
            CustomerEntity(
                name = "Sync Test Customer",
                phone = "111",
                address = "Test",
                creditLimit = 1000.0,
                currentDebt = 25.0
            )
        )

        if (includeSale) {
            database.saleDao().insertSale(
                SaleEntity(
                    id = "SALE-LOCAL-1",
                    saleNumber = "SALE-001",
                    storeId = "A1",
                    customerId = if (saleType == "CREDIT") customerId else null,
                    customerName = if (saleType == "CREDIT") "Sync Test Customer" else null,
                    sellerId = "seller-test",
                    sellerName = "Seller Test",
                    saleType = saleType,
                    totalAmount = 100.0,
                    paidAmount = if (saleType == "CREDIT") 25.0 else 100.0,
                    remainingAmount = if (saleType == "CREDIT") 75.0 else 0.0,
                    paymentMethod = "ESPECES",
                    status = status,
                    idempotencyKey = "SALE-KEY-1"
                )
            )
        }

        database.pendingSyncDao().insertOperation(
            PendingSyncOperationEntity(
                mutationId = "SALE-M1",
                entityType = "SALE",
                action = "CREATE",
                payloadJson = "{}",
                idempotencyKey = "SALE-KEY-1"
            )
        )
    }

    private fun repositoryReturning(response: SyncBatchResponse) =
        SyncRepositoryImpl(
            database,
            object : SyncHttpClient {
                override suspend fun push(
                    url: String,
                    request: SyncBatchRequest
                ): SyncBatchResponse = response
            },
            deviceId = "test-installation-device-test")

    @Test
    fun `credit ACK confirms sale and applies debt once`() = runBlocking {
        seedSaleOperation()

        val repository = repositoryReturning(
            SyncBatchResponse(true, listOf("SALE-M1"), emptyMap(), 11, "OK")
        )

        val result = repository.triggerSync()

        assertTrue(result is SyncPushResult.Success)
        assertEquals("SERVER_CONFIRMED",
            database.saleDao().getSaleByIdempotencyKey("SALE-KEY-1")?.status)
        assertEquals(100.0,
            database.customerDao().getCustomerById(1L)?.currentDebt ?: -1.0,
            0.001)
        assertEquals("SYNCED",
            database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
    }

    @Test
    fun `duplicate credit ACK does not apply debt twice`() = runBlocking {
        seedSaleOperation()

        val repository = repositoryReturning(
            SyncBatchResponse(true, listOf("SALE-M1"), emptyMap(), 11, "OK")
        )

        assertTrue(repository.triggerSync() is SyncPushResult.Success)

        val operation = database.pendingSyncDao()
            .getPendingOperationsSnapshot().single()
        database.pendingSyncDao().updateOperation(operation.copy(status = "PENDING"))

        assertTrue(repository.triggerSync() is SyncPushResult.Success)

        assertEquals(100.0,
            database.customerDao().getCustomerById(1L)?.currentDebt ?: -1.0,
            0.001)
        assertEquals("SERVER_CONFIRMED",
            database.saleDao().getSaleByIdempotencyKey("SALE-KEY-1")?.status)
    }

    @Test
    fun `credit ACK rolls back when customer debt update affects no row`() = runBlocking {
        seedSaleOperation()

        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER ignore_customer_debt_update_on_ack
            BEFORE UPDATE OF currentDebt ON customers
            WHEN OLD.id = 1
            BEGIN
                SELECT RAISE(IGNORE);
            END
            """
        )

        val repository = repositoryReturning(
            SyncBatchResponse(true, listOf("SALE-M1"), emptyMap(), 11, "OK")
        )

        val result = repository.triggerSync()

        assertTrue(result is SyncPushResult.PartialError)
        assertEquals(
            25.0,
            database.customerDao().getCustomerById(1L)?.currentDebt ?: -1.0,
            0.001
        )
        assertEquals(
            "PENDING_SYNC",
            database.saleDao().getSaleByIdempotencyKey("SALE-KEY-1")?.status
        )

        val operation = database.pendingSyncDao()
            .getPendingOperationsSnapshot().single()
        assertEquals("FAILED", operation.status)
        assertTrue(operation.lastError?.contains("mise à jour de la dette client") == true)
        assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `credit ACK rolls back debt and sale when sale confirmation fails`() = runBlocking {
        seedSaleOperation()

        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_sale_confirmation
            BEFORE UPDATE OF status ON sales
            WHEN NEW.status = 'SERVER_CONFIRMED'
            BEGIN
                SELECT RAISE(ABORT, 'forced sale confirmation failure');
            END
            """
        )

        val repository = repositoryReturning(
            SyncBatchResponse(true, listOf("SALE-M1"), emptyMap(), 11, "OK")
        )

        val result = repository.triggerSync()

        assertTrue(result is SyncPushResult.PartialError)
        assertEquals(
            25.0,
            database.customerDao().getCustomerById(1L)?.currentDebt ?: -1.0,
            0.001
        )
        assertEquals(
            "PENDING_SYNC",
            database.saleDao().getSaleByIdempotencyKey("SALE-KEY-1")?.status
        )

        val operation = database.pendingSyncDao()
            .getPendingOperationsSnapshot().single()
        assertEquals("FAILED", operation.status)
        assertTrue(operation.lastError?.contains("forced sale confirmation failure") == true)
        assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `sale rejection requires reconciliation and leaves debt unchanged`() = runBlocking {
        seedSaleOperation()

        val repository = repositoryReturning(
            SyncBatchResponse(
                false,
                emptyList(),
                mapOf("SALE-M1" to "Credit rejected"),
                null,
                "Rejected"
            )
        )

        assertTrue(repository.triggerSync() is SyncPushResult.PartialError)

        assertEquals("RECONCILIATION_REQUIRED",
            database.saleDao().getSaleByIdempotencyKey("SALE-KEY-1")?.status)
        assertEquals(25.0,
            database.customerDao().getCustomerById(1L)?.currentDebt ?: -1.0,
            0.001)

        val operation = database.pendingSyncDao()
            .getPendingOperationsSnapshot().single()
        assertEquals("REJECTED", operation.status)
        assertEquals("Credit rejected", operation.lastError)
        assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)

        // REJECTED is terminal: a second sync must not resend the mutation.
        val secondResult = repository.triggerSync()
        assertTrue(secondResult is SyncPushResult.Success)
        assertEquals(0, (secondResult as SyncPushResult.Success).countSynced)
        assertEquals("RECONCILIATION_REQUIRED",
            database.saleDao().getSaleByIdempotencyKey("SALE-KEY-1")?.status)
        assertEquals("REJECTED",
            database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
    }

    @Test
    fun `cash ACK confirms sale without changing customer debt`() = runBlocking {
        seedSaleOperation(saleType = "CASH")

        val repository = repositoryReturning(
            SyncBatchResponse(true, listOf("SALE-M1"), emptyMap(), 11, "OK")
        )

        assertTrue(repository.triggerSync() is SyncPushResult.Success)
        assertEquals("SERVER_CONFIRMED",
            database.saleDao().getSaleByIdempotencyKey("SALE-KEY-1")?.status)
        assertEquals(25.0,
            database.customerDao().getCustomerById(1L)?.currentDebt ?: -1.0,
            0.001)
        assertEquals("SYNCED",
            database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
    }

    @Test
    fun `rejection conflicting with confirmed sale leaves outbox retryable`() = runBlocking {
        seedSaleOperation(status = "SERVER_CONFIRMED")

        val repository = repositoryReturning(
            SyncBatchResponse(
                false,
                emptyList(),
                mapOf("SALE-M1" to "Contradictory rejection"),
                null,
                "Rejected"
            )
        )

        assertTrue(repository.triggerSync() is SyncPushResult.PartialError)

        assertEquals("SERVER_CONFIRMED",
            database.saleDao().getSaleByIdempotencyKey("SALE-KEY-1")?.status)
        val operation = database.pendingSyncDao()
            .getPendingOperationsSnapshot().single()
        assertEquals("FAILED", operation.status)
        assertTrue(operation.lastError?.contains("incompatible") == true)
    }

    @Test
    fun `customer rejection remains failed`() = runBlocking {
        seedOperation()

        val repository = repositoryReturning(
            SyncBatchResponse(
                false,
                emptyList(),
                mapOf("M-1" to "Customer rejected"),
                null,
                "Rejected"
            )
        )

        assertTrue(repository.triggerSync() is SyncPushResult.PartialError)

        val operation = database.pendingSyncDao()
            .getPendingOperationsSnapshot().single()
        assertEquals("FAILED", operation.status)
        assertEquals("Customer rejected", operation.lastError)
    }



    @Test
    fun `overlapping ACK and rejection leaves sale and debt unchanged`() = runBlocking {
        seedSaleOperation()
        val repository = repositoryReturning(
            SyncBatchResponse(
                false,
                listOf("SALE-M1"),
                mapOf("SALE-M1" to "Rejected"),
                11,
                "Conflicting response"
            )
        )

        val result = repository.triggerSync()
        assertTrue(result is SyncPushResult.PartialError)
        assertEquals(
            "PENDING_SYNC",
            database.saleDao().getSaleByIdempotencyKey("SALE-KEY-1")?.status
        )
        assertEquals(
            25.0,
            database.customerDao().getCustomerById(1L)?.currentDebt ?: -1.0,
            0.001
        )
        val operation = database.pendingSyncDao()
            .getPendingOperationsSnapshot().single()
        assertEquals("FAILED", operation.status)
        assertTrue(operation.lastError?.contains("ACK et rejet") == true)
        assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `credit ACK cursor write failure retry does not apply debt twice`() = runBlocking {
        seedSaleOperation()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_cursor_write
            BEFORE INSERT ON sync_cursor
            WHEN NEW.lastCursor = 11
            BEGIN
                SELECT RAISE(ABORT, 'forced cursor write failure');
            END
            """
        )

        val repository = repositoryReturning(
            SyncBatchResponse(true, listOf("SALE-M1"), emptyMap(), 11, "OK")
        )

        val firstResult = repository.triggerSync()
        assertTrue(firstResult is SyncPushResult.PartialError)
        assertEquals("FAILED", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
        assertEquals("SERVER_CONFIRMED", database.saleDao().getSaleByIdempotencyKey("SALE-KEY-1")?.status)
        assertEquals(100.0, database.customerDao().getCustomerById(1L)?.currentDebt ?: -1.0, 0.001)
        assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_cursor_write")

        val secondResult = repository.triggerSync()
        assertTrue(secondResult is SyncPushResult.Success)
        assertEquals(1, (secondResult as SyncPushResult.Success).countSynced)
        assertEquals(11L, database.syncCursorDao().getCursor()!!.lastCursor)
        assertEquals("SYNCED", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
        assertEquals("SERVER_CONFIRMED", database.saleDao().getSaleByIdempotencyKey("SALE-KEY-1")?.status)
        assertEquals(100.0, database.customerDao().getCustomerById(1L)?.currentDebt ?: -1.0, 0.001)
    }

    @Test
    fun `concurrent sync calls across repository instances do not submit the same operation twice`() = runBlocking {
        seedOperation()
        val firstRequestEntered = CompletableDeferred<Unit>()
        val releaseFirstRequest = CompletableDeferred<Unit>()
        var firstPushCalls = 0
        var secondPushCalls = 0

        val firstRepository = SyncRepositoryImpl(
            database,
            object : SyncHttpClient {
                override suspend fun push(
                    url: String,
                    request: SyncBatchRequest
                ): SyncBatchResponse {
                    firstPushCalls++
                    firstRequestEntered.complete(Unit)
                    releaseFirstRequest.await()
                    return SyncBatchResponse(true, listOf("M-1"), emptyMap(), 11, "OK")
                }
            },
            deviceId = "test-installation-device-test")
        val secondRepository = SyncRepositoryImpl(
            database,
            object : SyncHttpClient {
                override suspend fun push(
                    url: String,
                    request: SyncBatchRequest
                ): SyncBatchResponse {
                    secondPushCalls++
                    error("Ny repository faharoa tsy tokony handefa request.")
                }
            },
            deviceId = "test-installation-device-test")

        val first = async { firstRepository.triggerSync() }
        firstRequestEntered.await()

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            secondRepository.triggerSync()
        }

        releaseFirstRequest.complete(Unit)
        val firstResult = first.await()
        val secondResult = second.await()

        assertTrue(firstResult is SyncPushResult.Success)
        assertTrue(secondResult is SyncPushResult.Success)
        assertEquals(1, (firstResult as SyncPushResult.Success).countSynced)
        assertEquals(0, (secondResult as SyncPushResult.Success).countSynced)
        assertEquals(1, firstPushCalls)
        assertEquals(0, secondPushCalls)
        assertEquals(
            "SYNCED",
            database.pendingSyncDao().getPendingOperationsSnapshot().single().status
        )
        assertEquals(11L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

}
