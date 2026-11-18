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
            listOf(ServerIdMappingEntity("PRODUCT", "17", "11111111-1111-4111-8111-111111111111", 3)),
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
        assertEquals("11111111-1111-4111-8111-111111111111", mutation.payload["productId"])
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
            listOf(ServerIdMappingEntity("CUSTOMER", "8", "22222222-2222-4222-8222-222222222222", 2)),
            nextCursor = 31
        )
        assertEquals("22222222-2222-4222-8222-222222222222",
            database.serverIdMappingDao().getMapping("CUSTOMER", "8")!!.serverId)
        assertEquals(31L, database.syncCursorDao().getCursor()!!.lastCursor)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.commitPull(
                    listOf(ServerIdMappingEntity("PRODUCT", "5", "33333333-3333-4333-8333-333333333333", 1)),
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
                        ServerIdMappingEntity("PRODUCT", "1", "44444444-4444-4444-8444-444444444444", 1),
                        ServerIdMappingEntity("CUSTOMER", "1", "44444444-4444-4444-8444-444444444444", 1)
                    ),
                    nextCursor = 1
                )
            }
        }
        assertEquals(null, database.syncCursorDao().getCursor())
        assertEquals(null, database.serverIdMappingDao().getMapping("PRODUCT", "1"))
    }
    @Test
    fun `stable entity and idempotency UUIDs survive repeated conversion attempts`() {
        val entityId1 = RoomSyncStateStore.stableServerId("PRODUCT", "17")
        val entityId2 = RoomSyncStateStore.stableServerId("PRODUCT", "17")
        val mutationKey1 = RoomSyncStateStore.stableIdempotencyKey("mutation-17")
        val mutationKey2 = RoomSyncStateStore.stableIdempotencyKey("mutation-17")

        assertEquals(entityId1, entityId2)
        assertEquals(mutationKey1, mutationKey2)
        assertEquals(true, entityId1.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")))
        assertEquals(true, mutationKey1.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")))
    }

    @Test
    fun `non UUID server mapping is rejected before cursor commit`() = runBlocking {
        val store = RoomSyncStateStore(database)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.commitPull(
                    listOf(ServerIdMappingEntity("PRODUCT", "17", "not-a-uuid", 1)),
                    nextCursor = 1
                )
            }
        }
        assertEquals(null, database.syncCursorDao().getCursor())
    }

}
