package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.ServerIdMappingEntity
import com.example.data.local.entity.SyncCursorEntity
import com.example.data.repository.ProtocolV1Adapter
import com.example.data.repository.ProtocolV1MappingException
import com.example.data.repository.RoomSyncStateStore
import com.example.data.repository.ServerIdResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProtocolV1AdapterTest {
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

    @Test
    fun `conversion replaces local reference with mapped server UUID`() = runBlocking {
        val store = RoomSyncStateStore(database)
        store.commitPull(
            listOf(ServerIdMappingEntity("PRODUCT", "17", "server-product-uuid", 3)),
            nextCursor = 21
        )
        val adapter = ProtocolV1Adapter(store)
        val mutation = adapter.convert(
            mutationId = "mutation-1",
            entityType = "SALE",
            action = "CREATE",
            idempotencyKey = "idem-1",
            expectedVersion = 0,
            payload = mapOf("quantity" to 2),
            references = mapOf("productId" to ("PRODUCT" to "17"))
        )
        assertEquals("server-product-uuid", mutation.payload["productId"])
        assertEquals(2, mutation.payload["quantity"])
        assertEquals(21L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `conversion fails closed when local reference has no mapping`() = runBlocking {
        val adapter = ProtocolV1Adapter(RoomSyncStateStore(database))
        assertThrows(ProtocolV1MappingException::class.java) {
            runBlocking {
                adapter.convert(
                    mutationId = "mutation-2",
                    entityType = "TRANSFER",
                    action = "CREATE",
                    idempotencyKey = "idem-2",
                    expectedVersion = null,
                    payload = mapOf("quantity" to 1),
                    references = mapOf("productId" to ("PRODUCT" to "999"))
                )
            }
        }
        assertEquals(null, database.syncCursorDao().getCursor())
    }

    @Test
    fun `mapping and cursor are committed together and invalid pull changes neither`() = runBlocking {
        val store = RoomSyncStateStore(database)
        database.syncCursorDao().saveCursor(SyncCursorEntity(lastCursor = 30))
        store.commitPull(
            listOf(ServerIdMappingEntity("CUSTOMER", "8", "server-customer-uuid", 2)),
            nextCursor = 31
        )
        assertEquals("server-customer-uuid",
            database.serverIdMappingDao().getMapping("CUSTOMER", "8")!!.serverId)
        assertEquals(31L, database.syncCursorDao().getCursor()!!.lastCursor)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.commitPull(
                    listOf(ServerIdMappingEntity("PRODUCT", "5", "duplicate-server-id", 1)),
                    nextCursor = 29
                )
            }
        }
        assertEquals(null, database.serverIdMappingDao().getMapping("PRODUCT", "5"))
        assertEquals(31L, database.syncCursorDao().getCursor()!!.lastCursor)
    }

    @Test
    fun `duplicate server IDs in one pull are rejected before transaction`() = runBlocking {
        val store = RoomSyncStateStore(database)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.commitPull(
                    listOf(
                        ServerIdMappingEntity("PRODUCT", "1", "same-server-id", 1),
                        ServerIdMappingEntity("CUSTOMER", "1", "same-server-id", 1)
                    ),
                    nextCursor = 1
                )
            }
        }
        assertEquals(null, database.syncCursorDao().getCursor())
        assertEquals(null, database.serverIdMappingDao().getMapping("PRODUCT", "1"))
    }
}
