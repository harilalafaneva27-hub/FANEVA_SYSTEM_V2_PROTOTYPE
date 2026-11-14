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
import com.example.data.repository.AuthRepository
import com.example.data.repository.AuthResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncRepositoryTest {
    private lateinit var database: FanevaDatabase
    private lateinit var authRepository: AuthRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FanevaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        authRepository = AuthRepository(database.authDao(), database.adminRecoveryDao(), database.securitySessionDao())
        runBlocking {
            authRepository.initializeFirstAdmin("sync-admin", "Sync Admin", "SyncAdmin-StrongPass-2026!".toCharArray())
            val login = authRepository.authenticateAdmin("sync-admin", "SyncAdmin-StrongPass-2026!".toCharArray())
            check(login is AuthResult.Success) { "Sync tests require a persisted ADMIN session" }
        }
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
            authRepository,
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
            database,
            authRepository,
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
            database,
            authRepository,
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
    @Test
    fun `sync requires a persisted active session`() = runBlocking {
        seedOperation()
        val session = authRepository.getPersistedSession()!!
        authRepository.revokeSession(session.sessionId)
        val repository = SyncRepositoryImpl(
            database, authRepository, database.pendingSyncDao(), database.syncCursorDao(),
            object : SyncHttpClient {
                override suspend fun push(url: String, request: SyncBatchRequest): SyncBatchResponse {
                    error("HTTP must not be called without a persisted session")
                }
            }
        )
        try {
            repository.triggerSync()
            throw AssertionError("Expected session denial")
        } catch (_: IllegalStateException) {
            assertEquals("PENDING", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
        }
    }

    @Test
    fun `cancellation restores syncing operations to pending`() = runBlocking {
        seedOperation()
        val repository = SyncRepositoryImpl(
            database, authRepository, database.pendingSyncDao(), database.syncCursorDao(),
            object : SyncHttpClient {
                override suspend fun push(url: String, request: SyncBatchRequest): SyncBatchResponse {
                    throw CancellationException("test cancellation")
                }
            }
        )
        try {
            repository.triggerSync()
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals("PENDING", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
            assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)
        }
    }

    @Test
    fun `purge remains disabled until retention policy is approved`() = runBlocking {
        seedOperation()
        val operation = database.pendingSyncDao().getPendingOperationsSnapshot().single()
        database.pendingSyncDao().updateOperation(operation.copy(status = "SYNCED", syncedAt = System.currentTimeMillis()))
        val repository = SyncRepositoryImpl(
            database, authRepository, database.pendingSyncDao(), database.syncCursorDao(),
            object : SyncHttpClient {
                override suspend fun push(url: String, request: SyncBatchRequest) =
                    SyncBatchResponse(true)
            }
        )
        try {
            repository.purgeSyncedOperations()
            throw AssertionError("Purge must remain disabled")
        } catch (_: IllegalStateException) {
            assertEquals(1, database.pendingSyncDao().getPendingOperationsSnapshot().size)
        }
    }

    @Test
    fun `concurrent sync calls on one repository instance do not push the same operation twice`() = runBlocking {
        seedOperation()
        val pushCount = AtomicInteger(0)
        val repository = SyncRepositoryImpl(
            database, authRepository, database.pendingSyncDao(), database.syncCursorDao(),
            object : SyncHttpClient {
                override suspend fun push(url: String, request: SyncBatchRequest): SyncBatchResponse {
                    pushCount.incrementAndGet()
                    delay(100)
                    return SyncBatchResponse(true, request.operations.map { it.mutationId }, emptyMap(), 11, "OK")
                }
            }
        )
        val first = async { repository.triggerSync() }
        val second = async { repository.triggerSync() }
        first.await()
        second.await()
        assertEquals(1, pushCount.get())
        assertEquals("SYNCED", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
        assertEquals(11L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `cursor never regresses when server returns an older cursor`() = runBlocking {
        seedOperation()
        val repository = SyncRepositoryImpl(
            database, authRepository, database.pendingSyncDao(), database.syncCursorDao(),
            object : SyncHttpClient {
                override suspend fun push(url: String, request: SyncBatchRequest) =
                    SyncBatchResponse(true, request.operations.map { it.mutationId }, emptyMap(), 9, "OK")
            }
        )
        val result = repository.triggerSync()
        assertTrue(result is SyncPushResult.Success)
        assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `failed server batch cannot acknowledge operations or advance cursor`() = runBlocking {
        seedOperation()
        val repository = SyncRepositoryImpl(
            database, authRepository, database.pendingSyncDao(), database.syncCursorDao(),
            object : SyncHttpClient {
                override suspend fun push(url: String, request: SyncBatchRequest) =
                    SyncBatchResponse(false, request.operations.map { it.mutationId }, emptyMap(), 11, "Batch rejected")
            }
        )
        val result = repository.triggerSync()
        assertTrue(result is SyncPushResult.PartialError)
        assertEquals("FAILED", database.pendingSyncDao().getPendingOperationsSnapshot().single().status)
        assertEquals(10L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

}
