package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.CustomerEntity
import com.example.data.local.entity.ProductEntity
import com.example.data.local.entity.TransferRequestEntity
import com.example.data.local.entity.UserPreferenceEntity
import com.example.data.local.entity.StoreStockEntity
import com.example.data.repository.FanevaRepository
import com.example.domain.model.CartItem
import com.example.domain.model.PaymentMethod
import com.example.domain.model.SaleType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var database: FanevaDatabase
    private lateinit var repository: FanevaRepository
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FanevaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FanevaRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `verify app name resource matches FANEVA SYSTEM V2`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("FANEVA SYSTEM V2", appName)
    }

    @Test
    fun `reliability rule - prevent negative stock on sale attempt`() = runBlocking {
        // Seed 1 product with stock = 5 in A1
        val productId = database.productDao().insertProduct(
            ProductEntity(
                code = "PRD-TEST-01",
                name = "Fer 10mm",
                category = "Matériaux",
                purchasePrice = 10000.0,
                sellingPrice = 15000.0,
                minStockAlert = 2,
                unit = "Barre"
            )
        )
        database.storeStockDao().insertOrUpdateStock(
            StoreStockEntity(storeId = "A1", productId = productId, quantity = 5)
        )

        // Attempt to sell 10 items (more than the available 5)
        val cartItems = listOf(
            CartItem(
                productId = productId,
                productCode = "PRD-TEST-01",
                productName = "Fer 10mm",
                unit = "Barre",
                unitPrice = 15000.0,
                purchasePrice = 10000.0,
                quantity = 10,
                availableInActiveStore = 5
            )
        )

        val result = repository.processSale(
            storeId = "A1",
            cartItems = cartItems,
            saleType = SaleType.CASH,
            paymentMethod = PaymentMethod.ESPECES,
            customerId = null,
            customerName = null,
            sellerId = "VENDEUR-1",
            sellerName = "Soa K.",
            paidAmount = 150000.0
        )

        // The sale MUST be rejected
        assertFalse("Sale exceeding stock must fail", result.isSuccess)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("Stock insuffisant"))

        // Stock in A1 must remain unchanged at 5 (no negative stock!)
        val finalStock = database.storeStockDao().getStock(productId, "A1")?.quantity
        assertEquals(5, finalStock)
    }

    @Test
    fun `sale execution decrements stock accurately and generates outbox mutation`() = runBlocking {
        val productId = database.productDao().insertProduct(
            ProductEntity(
                code = "PRD-TEST-02",
                name = "Ciment CPJ",
                category = "Matériaux",
                purchasePrice = 30000.0,
                sellingPrice = 38000.0,
                minStockAlert = 5,
                unit = "Sac"
            )
        )
        database.storeStockDao().insertOrUpdateStock(
            StoreStockEntity(storeId = "A1", productId = productId, quantity = 20)
        )

        val cartItems = listOf(
            CartItem(
                productId = productId,
                productCode = "PRD-TEST-02",
                productName = "Ciment CPJ",
                unit = "Sac",
                unitPrice = 38000.0,
                purchasePrice = 30000.0,
                quantity = 4,
                availableInActiveStore = 20
            )
        )

        val result = repository.processSale(
            storeId = "A1",
            cartItems = cartItems,
            saleType = SaleType.CASH,
            paymentMethod = PaymentMethod.ESPECES,
            customerId = null,
            customerName = null,
            sellerId = "VENDEUR-1",
            sellerName = "Soa K.",
            paidAmount = 152000.0
        )

        assertTrue("Sale must succeed", result.isSuccess)
        assertNotNull(result.saleNumber)

        // Stock must be 20 - 4 = 16
        val remainingStock = database.storeStockDao().getStock(productId, "A1")?.quantity
        assertEquals(16, remainingStock)

        // Outbox pending mutation must be queued
        val pending = database.pendingSyncDao().getPendingOperationsSnapshot()
        assertEquals(1, pending.size)
        assertEquals("SALE", pending[0].entityType)
        assertEquals("PENDING", pending[0].status)
    }

    @Test
    fun `idempotency check prevents duplicate sales on retry`() = runBlocking {
        val productId = database.productDao().insertProduct(
            ProductEntity(
                code = "PRD-TEST-03",
                name = "Tuyau PVC",
                category = "Plomberie",
                purchasePrice = 12000.0,
                sellingPrice = 18000.0,
                minStockAlert = 2,
                unit = "Barre"
            )
        )
        database.storeStockDao().insertOrUpdateStock(
            StoreStockEntity(storeId = "A1", productId = productId, quantity = 10)
        )

        val cartItems = listOf(
            CartItem(
                productId = productId,
                productCode = "PRD-TEST-03",
                productName = "Tuyau PVC",
                unit = "Barre",
                unitPrice = 18000.0,
                purchasePrice = 12000.0,
                quantity = 2,
                availableInActiveStore = 10
            )
        )

        val fixedIdempotencyKey = "IDEMP-KEY-TEST-12345"

        // 1st attempt
        val firstResult = repository.processSale(
            storeId = "A1",
            cartItems = cartItems,
            saleType = SaleType.CASH,
            paymentMethod = PaymentMethod.ESPECES,
            customerId = null,
            customerName = null,
            sellerId = "VENDEUR-1",
            sellerName = "Soa K.",
            paidAmount = 36000.0,
            idempotencyKey = fixedIdempotencyKey
        )
        assertTrue(firstResult.isSuccess)

        // Stock was decremented from 10 to 8
        assertEquals(8, database.storeStockDao().getStock(productId, "A1")?.quantity)

        // 2nd retry attempt with EXACT SAME idempotencyKey
        val secondResult = repository.processSale(
            storeId = "A1",
            cartItems = cartItems,
            saleType = SaleType.CASH,
            paymentMethod = PaymentMethod.ESPECES,
            customerId = null,
            customerName = null,
            sellerId = "VENDEUR-1",
            sellerName = "Soa K.",
            paidAmount = 36000.0,
            idempotencyKey = fixedIdempotencyKey
        )
        assertTrue("Duplicate request should return existing sale successfully", secondResult.isSuccess)
        assertEquals(firstResult.saleId, secondResult.saleId)

        // CRITICAL CHECK: Stock must NOT have been decremented again (must still be 8)
        assertEquals(8, database.storeStockDao().getStock(productId, "A1")?.quantity)
    }

    @Test
    fun `credit sale increases customer debt atomically`() = runBlocking {
        val customerId = database.customerDao().insertCustomer(
            CustomerEntity(
                name = "Entreprise Test BTP",
                phone = "+261 34 00 111 22",
                address = "Tanjombato",
                creditLimit = 1000000.0,
                currentDebt = 100000.0
            )
        )

        val productId = database.productDao().insertProduct(
            ProductEntity(
                code = "PRD-TEST-04",
                name = "Peinture Blanche 5L",
                category = "Peinture",
                purchasePrice = 60000.0,
                sellingPrice = 80000.0,
                minStockAlert = 3,
                unit = "Pot"
            )
        )
        database.storeStockDao().insertOrUpdateStock(
            StoreStockEntity(storeId = "A1", productId = productId, quantity = 10)
        )

        val cartItems = listOf(
            CartItem(
                productId = productId,
                productCode = "PRD-TEST-04",
                productName = "Peinture Blanche 5L",
                unit = "Pot",
                unitPrice = 80000.0,
                purchasePrice = 60000.0,
                quantity = 2,
                availableInActiveStore = 10
            )
        )

        // Total = 160 000 Ar, paid upfront = 60 000 Ar, remaining debt = 100 000 Ar
        val result = repository.processSale(
            storeId = "A1",
            cartItems = cartItems,
            saleType = SaleType.CREDIT,
            paymentMethod = PaymentMethod.ESPECES,
            customerId = customerId,
            customerName = "Entreprise Test BTP",
            sellerId = "VENDEUR-1",
            sellerName = "Soa K.",
            paidAmount = 60000.0
        )

        assertTrue(result.isSuccess)

        // Initial debt 100 000 + new remaining 100 000 = 200 000 Ar
        val updatedCustomer = database.customerDao().getCustomerById(customerId)
        assertNotNull(updatedCustomer)
        assertEquals(200000.0, updatedCustomer!!.currentDebt, 0.01)
    }

    @Test
    fun `inter-store transfer updates source and destination stocks atomically`() = runBlocking {
        val productId = database.productDao().insertProduct(
            ProductEntity(
                code = "PRD-TEST-05",
                name = "Vis Bois 4x50",
                category = "Quincaillerie",
                purchasePrice = 8000.0,
                sellingPrice = 12000.0,
                minStockAlert = 2,
                unit = "Boîte"
            )
        )
        // A2 has 50 boxes, A1 has 10 boxes
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A2", productId, 50))
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A1", productId, 10))

        val result = repository.transferStock(
            productId = productId,
            productName = "Vis Bois 4x50",
            fromStoreId = "A2",
            toStoreId = "A1",
            quantity = 15,
            operatorName = "Mamy T. (Gérant)",
            reason = "Réassort A1"
        )

        assertTrue(result.isSuccess)

        // A2 must now be 50 - 15 = 35
        // A1 must now be 10 + 15 = 25
        assertEquals(35, database.storeStockDao().getStock(productId, "A2")?.quantity)
        assertEquals(25, database.storeStockDao().getStock(productId, "A1")?.quantity)
    }

    @Test
    fun `transfer retry with same key does not duplicate stock movement or outbox`() = runBlocking {
        val productId = database.productDao().insertProduct(
            ProductEntity(
                code = "PRD-IDEMP-01",
                name = "Produit canonique",
                category = "Test",
                purchasePrice = 10.0,
                sellingPrice = 20.0
            )
        )
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A2", productId, 10))
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A1", productId, 2))

        val first = repository.transferStock(
            productId = productId,
            productName = "Nom fourni par UI",
            fromStoreId = "A2",
            toStoreId = "A1",
            quantity = 3,
            operatorName = "Test",
            reason = "Retry test",
            idempotencyKey = "TRANSFER-IDEMP-01"
        )
        val retry = repository.transferStock(
            productId = productId,
            productName = "Autre nom UI",
            fromStoreId = "A2",
            toStoreId = "A1",
            quantity = 3,
            operatorName = "Test",
            reason = "Retry test",
            idempotencyKey = "TRANSFER-IDEMP-01"
        )

        assertTrue(first.isSuccess)
        assertTrue(retry.isSuccess)
        assertEquals(7, database.storeStockDao().getStock(productId, "A2")?.quantity)
        assertEquals(5, database.storeStockDao().getStock(productId, "A1")?.quantity)
        assertEquals(1, database.stockMovementDao().countAll())
        assertEquals(1, database.pendingSyncDao().countAll())

        val request = database.transferRequestDao()
            .getByIdempotencyKey("TRANSFER-IDEMP-01")
        assertEquals("COMPLETED", request?.status)
        assertEquals("Produit canonique", request?.productName)

        val payload = database.pendingSyncDao()
            .getByIdempotencyKey("TRANSFER-IDEMP-01")!!.payloadJson
        assertEquals("Produit canonique", JSONObject(payload).getString("productName"))
    }

    @Test
    fun `transfer idempotency key reused with different payload is rejected without mutation`() = runBlocking {
        val productId = database.productDao().insertProduct(
            ProductEntity(
                code = "PRD-IDEMP-02",
                name = "Produit conflit",
                category = "Test",
                purchasePrice = 10.0,
                sellingPrice = 20.0
            )
        )
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A2", productId, 10))
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A1", productId, 1))

        val first = repository.transferStock(
            productId, "Produit conflit", "A2", "A1", 3,
            "Test", "Demande initiale", "TRANSFER-CONFLICT-01"
        )
        val conflict = repository.transferStock(
            productId, "Produit conflit", "A2", "A1", 4,
            "Test", "Demande initiale", "TRANSFER-CONFLICT-01"
        )

        assertTrue(first.isSuccess)
        assertFalse(conflict.isSuccess)
        assertEquals(7, database.storeStockDao().getStock(productId, "A2")?.quantity)
        assertEquals(4, database.storeStockDao().getStock(productId, "A1")?.quantity)
        assertEquals(1, database.stockMovementDao().countAll())
        assertEquals(1, database.pendingSyncDao().countAll())
    }

    @Test
    fun `clearing active transfer key preserves it while request is prepared`() = runBlocking {
        val productId = database.productDao().insertProduct(
            ProductEntity(
                code = "PRD-KEYGUARD-01",
                name = "Produit keyguard",
                category = "Test",
                purchasePrice = 10.0,
                sellingPrice = 20.0
            )
        )

        val key = "TRANSFER-KEYGUARD-01"

        database.userPreferenceDao().savePreferences(
            UserPreferenceEntity(activeTransferIdempotencyKey = key)
        )

        database.transferRequestDao().insertRequest(
            TransferRequestEntity(
                idempotencyKey = key,
                productId = productId,
                productName = "Produit keyguard",
                fromStoreId = "A2",
                toStoreId = "A1",
                quantity = 1,
                operatorName = "Test keyguard",
                reason = "Test conservation de clé",
                status = "PREPARED",
                createdAt = System.currentTimeMillis(),
                completedAt = null
            )
        )

        repository.clearTransferIdempotencyKey(key)

        assertEquals(
            "La clé active doit être conservée tant que le transfert est PREPARED",
            key,
            database.userPreferenceDao().getPreferences()?.activeTransferIdempotencyKey
        )
        assertEquals(
            "PREPARED",
            database.transferRequestDao().getByIdempotencyKey(key)?.status
        )
    }

    @Test
    fun `prepared transfer recovery completes once and clears active key`() = runBlocking {
        val productId = database.productDao().insertProduct(
            ProductEntity(
                code = "PRD-RECOVERY-01",
                name = "Produit recovery",
                category = "Test",
                purchasePrice = 10.0,
                sellingPrice = 20.0
            )
        )
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A2", productId, 9))
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A1", productId, 1))

        val key = "TRANSFER-RECOVERY-01"
        database.userPreferenceDao().savePreferences(
            UserPreferenceEntity(activeTransferIdempotencyKey = key)
        )
        database.transferRequestDao().insertRequest(
            TransferRequestEntity(
                idempotencyKey = key,
                productId = productId,
                productName = "Produit recovery",
                fromStoreId = "A2",
                toStoreId = "A1",
                quantity = 4,
                operatorName = "Test recovery",
                reason = "Reprise après interruption",
                status = "PREPARED",
                createdAt = System.currentTimeMillis(),
                completedAt = null
            )
        )

        val results = repository.recoverPreparedTransfers()
        val secondRecovery = repository.recoverPreparedTransfers()
        assertTrue(secondRecovery.isEmpty())
        assertEquals(1, database.stockMovementDao().countAll())
        assertEquals(1, database.pendingSyncDao().countAll())

        assertEquals(1, results.size)
        assertTrue(results.single().isSuccess)
        assertEquals(5, database.storeStockDao().getStock(productId, "A2")?.quantity)
        assertEquals(5, database.storeStockDao().getStock(productId, "A1")?.quantity)
        assertEquals(1, database.stockMovementDao().countAll())
        assertEquals(1, database.pendingSyncDao().countAll())
        assertEquals(
            "COMPLETED",
            database.transferRequestDao().getByIdempotencyKey(key)?.status
        )
        assertNull(database.userPreferenceDao().getPreferences()?.activeTransferIdempotencyKey)
    }

    @Test
    fun `prepared transfer recovery rejects insufficient stock without mutation`() = runBlocking {
        val productId = database.productDao().insertProduct(
            ProductEntity(
                code = "PRD-RECOVERY-02",
                name = "Produit stock limité",
                category = "Test",
                purchasePrice = 10.0,
                sellingPrice = 20.0
            )
        )
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A2", productId, 2))
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A1", productId, 1))

        val key = "TRANSFER-RECOVERY-02"
        database.userPreferenceDao().savePreferences(
            UserPreferenceEntity(activeTransferIdempotencyKey = key)
        )
        database.transferRequestDao().insertRequest(
            TransferRequestEntity(
                idempotencyKey = key,
                productId = productId,
                productName = "Produit stock limité",
                fromStoreId = "A2",
                toStoreId = "A1",
                quantity = 5,
                operatorName = "Test recovery",
                reason = "Stock insuffisant",
                status = "PREPARED",
                createdAt = System.currentTimeMillis(),
                completedAt = null
            )
        )

        val results = repository.recoverPreparedTransfers()

        assertEquals(1, results.size)
        assertFalse(results.single().isSuccess)
        assertEquals(2, database.storeStockDao().getStock(productId, "A2")?.quantity)
        assertEquals(1, database.storeStockDao().getStock(productId, "A1")?.quantity)
        assertEquals(0, database.stockMovementDao().countAll())
        assertEquals(0, database.pendingSyncDao().countAll())
        assertEquals(
            "REJECTED",
            database.transferRequestDao().getByIdempotencyKey(key)?.status
        )
        assertNull(database.userPreferenceDao().getPreferences()?.activeTransferIdempotencyKey)
    }

    @Test
    fun `updating user preferences preserves active transfer key`() = runBlocking {
        val key = "TRANSFER-PREFERENCE-01"
        database.userPreferenceDao().savePreferences(
            UserPreferenceEntity(
                activeRole = "ADMIN",
                activeStoreId = "A1",
                operatorName = "Ancien opérateur",
                activeTransferIdempotencyKey = key
            )
        )

        repository.updateUserPreference(
            role = "GERANT",
            storeId = "A2",
            operator = "Nouvel opérateur"
        )

        val updated = database.userPreferenceDao().getPreferences()
        assertEquals("GERANT", updated?.activeRole)
        assertEquals("A2", updated?.activeStoreId)
        assertEquals("Nouvel opérateur", updated?.operatorName)
        assertEquals(key, updated?.activeTransferIdempotencyKey)
    }

    @Test
    fun `payment above remaining debt is rejected without debt journal or outbox mutation`() = runBlocking {
        val customerId = database.customerDao().insertCustomer(
            CustomerEntity(
                name = "Client Dette",
                phone = "0340000000",
                address = "Antananarivo",
                creditLimit = 500000.0,
                currentDebt = 100000.0
            )
        )
        val result = repository.recordDebtPayment(
            customerId = customerId,
            amount = 150000.0,
            paymentMethod = PaymentMethod.ESPECES,
            note = "Test dépassement",
            operatorName = "Test",
            idempotencyKey = "PAYMENT-OVER-100K"
        )
        assertTrue(result.isFailure)
        assertEquals(100000.0, database.customerDao().getCustomerById(customerId)!!.currentDebt, 0.01)
        assertTrue(database.debtPaymentDao().getAllPaymentsSnapshot().isEmpty())
        assertTrue(database.pendingSyncDao().getPendingOperationsSnapshot().isEmpty())
    }

    @Test
    fun `sale outbox payload is valid JSON and preserves special characters`() = runBlocking {
        val productId = database.productDao().insertProduct(
            ProductEntity(
                code = "PRD-JSON",
                name = "Produit \"spécial\"",
                category = "Catégorie",
                purchasePrice = 10.0,
                sellingPrice = 20.0
            )
        )
        database.storeStockDao().insertOrUpdateStock(StoreStockEntity("A1", productId, 2))
        val result = repository.processSale(
            storeId = "A1",
            cartItems = listOf(
                CartItem(
                    productId = productId,
                    productCode = "PRD-JSON",
                    productName = "Produit \"spécial\"",
                    unit = "Pcs",
                    unitPrice = 20.0,
                    purchasePrice = 10.0,
                    quantity = 1,
                    availableInActiveStore = 2
                )
            ),
            saleType = SaleType.CASH,
            paymentMethod = PaymentMethod.ESPECES,
            customerId = null,
            customerName = null,
            sellerId = "TEST",
            sellerName = "Opérateur \\ test",
            paidAmount = 20.0,
            idempotencyKey = "SALE-JSON-1"
        )
        assertTrue(result.isSuccess)
        val payload = database.pendingSyncDao().getPendingOperationsSnapshot().single().payloadJson
        val json = JSONObject(payload)
        assertEquals("A1", json.getString("storeId"))
        assertEquals("PRD-JSON", json.getJSONArray("items").getJSONObject(0).getString("code"))
    }

    @Test
    fun `demo reset refuses to overwrite existing user data`() = runBlocking {
        database.productDao().insertProduct(
            ProductEntity(
                code = "USER-PRODUCT",
                name = "Produit utilisateur",
                category = "Test",
                purchasePrice = 1.0,
                sellingPrice = 2.0
            )
        )
        val result = repository.resetDatabase()
        assertTrue(result.isFailure)
        assertEquals(1, database.productDao().countAll())
        assertEquals("USER-PRODUCT", database.productDao().getProductByCode("USER-PRODUCT")!!.code)
    }
}
