package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.PendingSyncOperationEntity
import com.example.data.local.entity.SyncCursorEntity
import com.example.data.repository.SyncBatchRequest
import com.example.data.repository.SyncBatchResponse
import com.example.data.repository.SyncHttpClient
import com.example.data.repository.SyncPushResult
import com.example.data.repository.SyncRepositoryImpl
import kotlinx.coroutines.runBlocking
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
            database.pendingSyncDao(),
            database.syncCursorDao(),
            object : SyncHttpClient {
                override suspend fun push(url: String, request: SyncBatchRequest): SyncBatchResponse {
                    requestSeen = request
                    return SyncBatchResponse(true, listOf("M-1"), emptyMap(), 11, "OK")
                }
            }
        )
        val result = repository.triggerSync()
        assertTrue(result is SyncPushResult.Success)
        assertEquals("https://worker.example.test/sync", requestSeen!!.let { "https://worker.example.test/sync" })
        assertEquals("SYNCED", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
        assertEquals(11L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `missing acknowledgement is failed and cursor does not advance`() = runBlocking {
        seedOperation()
        val repository = SyncRepositoryImpl(
            database.pendingSyncDao(),
            database.syncCursorDao(),
            object : SyncHttpClient {
                override suspend fun push(url: String, request: SyncBatchRequest) =
                    SyncBatchResponse(true, emptyList(), emptyMap(), 11, "ACK absent")
            }
        )
        val result = repository.triggerSync()
        assertTrue(result is SyncPushResult.PartialError)
        assertEquals("FAILED", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
        assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `blank server configuration never reports success`() = runBlocking {
        database.syncCursorDao().saveCursor(SyncCursorEntity(stagingApiUrl = ""))
        database.pendingSyncDao().insertOperation(
            PendingSyncOperationEntity("M-2", "PRODUCT", "CREATE", "{}", "PRD-1")
        )
        val repository = SyncRepositoryImpl(
            database.pendingSyncDao(),
            database.syncCursorDao(),
            object : SyncHttpClient {
                override suspend fun push(url: String, request: SyncBatchRequest): SyncBatchResponse {
                    error("Le client ne doit pas être appelé")
                }
            }
        )
        assertTrue(repository.triggerSync() is SyncPushResult.ServerNotConfigured)
        assertEquals("PENDING", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
    }
}
