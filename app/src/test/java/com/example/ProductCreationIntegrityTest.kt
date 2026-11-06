package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.ProductEntity
import com.example.data.local.entity.StoreStockEntity
import com.example.data.repository.FanevaRepository
import com.example.domain.model.StoreId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProductCreationIntegrityTest {

    private lateinit var database: FanevaDatabase
    private lateinit var repository: FanevaRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            FanevaDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = FanevaRepository(database)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun duplicateCode_isRejectedWithoutReplacingExistingProductOrStock() = runBlocking {
        val existingId = database.productDao().insertProduct(
            ProductEntity(
                code = "SKU-DUPLICATE-001",
                name = "Produit original",
                category = "TEST",
                purchasePrice = 100.0,
                sellingPrice = 150.0
            )
        )
        database.storeStockDao().insertOrUpdateStock(
            StoreStockEntity(
                storeId = "A1",
                productId = existingId,
                quantity = 17
            )
        )

        val result = runCatching {
            repository.addNewProduct(
                code = " SKU-DUPLICATE-001 ",
                name = "Produit de remplacement",
                category = "TEST",
                purchasePrice = 200.0,
                sellingPrice = 300.0,
                minAlert = 5,
                unit = "Pcs",
                initialStoreStocks = mapOf("A1" to 99)
            )
        }

        assertTrue("Duplicate SKU must be rejected", result.isFailure)
        assertTrue(
            "Duplicate SKU should fail validation",
            result.exceptionOrNull() is IllegalArgumentException
        )
        val original = database.productDao().getProductById(existingId)
        assertNotNull(original)
        assertEquals("Produit original", original!!.name)
        assertEquals(1, database.productDao().countAll())
        assertEquals(
            17,
            database.storeStockDao().getStock(existingId, "A1")!!.quantity
        )
        assertEquals(0, database.pendingSyncDao().countAll())
    }

    @Test
    fun blankCode_isRejectedWithoutCreatingProductOrOutboxOperation() = runBlocking {
        val result = runCatching {
            repository.addNewProduct(
                code = "   ",
                name = "Produit test",
                category = "TEST",
                purchasePrice = 100.0,
                sellingPrice = 150.0,
                minAlert = 5,
                unit = "Pcs",
                initialStoreStocks = mapOf("A1" to 10)
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, database.productDao().countAll())
        assertEquals(0, database.pendingSyncDao().countAll())
    }

    @Test
    fun validProduct_isTrimmedAndCreatesAllStoreStocksAndOneOutboxOperation() = runBlocking {
        val productId = repository.addNewProduct(
            code = " SKU-NEW-001 ",
            name = "Produit nouveau",
            category = "TEST",
            purchasePrice = 100.0,
            sellingPrice = 150.0,
            minAlert = 5,
            unit = "Pcs",
            initialStoreStocks = mapOf("A1" to 12)
        )

        val product = database.productDao().getProductById(productId)
        assertNotNull(product)
        assertEquals("SKU-NEW-001", product!!.code)
        assertEquals(1, database.productDao().countAll())

        StoreId.entries.forEach { store ->
            val stock = database.storeStockDao().getStock(productId, store.code)
            assertNotNull("Missing stock row for ${store.code}", stock)
            assertEquals(
                if (store.code == "A1") 12 else 0,
                stock!!.quantity
            )
        }

        assertEquals(1, database.pendingSyncDao().countAll())
        val operation = database.pendingSyncDao()
            .getPendingOperationsSnapshot().single()
        assertEquals("PRODUCT", operation.entityType)
        assertEquals("CREATE", operation.action)
        assertEquals("PENDING", operation.status)
    }
}
