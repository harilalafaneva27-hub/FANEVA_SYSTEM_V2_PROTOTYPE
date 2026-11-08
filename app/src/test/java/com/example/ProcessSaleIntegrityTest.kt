package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.*
import com.example.data.repository.FanevaRepository
import com.example.domain.model.*
import com.example.domain.validation.MgaAmountValidator
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
class ProcessSaleIntegrityTest {

    private lateinit var database: FanevaDatabase
    private lateinit var repository: FanevaRepository
    private var productId: Long = 0L
    private var customerId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        database = Room.inMemoryDatabaseBuilder(
            context,
            FanevaDatabase::class.java
        ).allowMainThreadQueries().build()

        repository = FanevaRepository(database)

        productId = database.productDao().insertProduct(
            ProductEntity(
                code = "TEST-INTEGRITY-001",
                name = "Test Product",
                category = "TEST",
                purchasePrice = 50.0,
                sellingPrice = 100.0
            )
        )

        database.storeStockDao().insertOrUpdateStock(
            StoreStockEntity(
                storeId = "A1",
                productId = productId,
                quantity = 10
            )
        )

        customerId = database.customerDao().insertCustomer(
            CustomerEntity(
                name = "Test Customer",
                phone = "000000000",
                address = "Test",
                creditLimit = 1000.0,
                currentDebt = 0.0
            )
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) {
            database.close()
        }
    }

    private fun cart(
        quantity: Int = 1,
        unitPrice: Double = 100.0,
        purchasePrice: Double = 50.0
    ) = listOf(
        CartItem(
            productId = productId,
            productCode = "TEST-INTEGRITY-001",
            productName = "Test Product",
            unit = "Pcs",
            unitPrice = unitPrice,
            purchasePrice = purchasePrice,
            quantity = quantity,
            availableInActiveStore = 10
        )
    )

    private suspend fun seedPendingCredit(
        storeId: String,
        remainingAmount: Double,
        idempotencyKey: String
    ) {
        database.saleDao().insertSale(
            SaleEntity(
                id = "seed-$idempotencyKey",
                saleNumber = "SEED-$idempotencyKey",
                storeId = storeId,
                customerId = customerId,
                customerName = "Test Customer",
                sellerId = "seller-test",
                sellerName = "Seller Test",
                saleType = SaleType.CREDIT.name,
                totalAmount = remainingAmount,
                paidAmount = 0.0,
                remainingAmount = remainingAmount,
                paymentMethod = PaymentMethod.ESPECES.name,
                status = "PENDING_SYNC",
                idempotencyKey = idempotencyKey
            )
        )
    }

    @Test
    fun outboxInsertFailure_rollsBackEntireSaleTransaction() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_sale_outbox_insert
            BEFORE INSERT ON pending_sync_operations
            WHEN NEW.entityType = 'SALE'
            BEGIN
                SELECT RAISE(ABORT, 'forced outbox insert failure');
            END
            """.trimIndent()
        )

        var insertionFailureMessage: String? = null

        try {
            repository.processSale(
                storeId = "A1",
                cartItems = cart(quantity = 2),
                saleType = SaleType.CASH,
                paymentMethod = PaymentMethod.ESPECES,
                customerId = null,
                customerName = null,
                sellerId = "seller-test",
                sellerName = "Seller Test",
                paidAmount = 200.0,
                idempotencyKey = "cash-outbox-rollback-001"
            )
        } catch (e: Exception) {
            insertionFailureMessage = generateSequence(e as Throwable?) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" | ")
        }

        assertTrue(
            "Expected forced Outbox insertion failure, got: $insertionFailureMessage",
            insertionFailureMessage?.contains("forced outbox insert failure") == true
        )

        assertEquals(
            10,
            database.storeStockDao().getStock(productId, "A1")?.quantity
        )
        assertEquals(0, database.saleDao().countAll())

        val saleItemsCursor = database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM sale_items"
        )
        try {
            assertTrue(saleItemsCursor.moveToFirst())
            assertEquals(0, saleItemsCursor.getInt(0))
        } finally {
            saleItemsCursor.close()
        }

        assertEquals(0, database.stockMovementDao().countAll())
        assertEquals(0, database.pendingSyncDao().countAll())
    }

    @Test
    fun cashSale_isPendingAndDecrementsStockExactlyOnce() = runBlocking {
        val result = repository.processSale(
            storeId = "A1",
            cartItems = cart(quantity = 2),
            saleType = SaleType.CASH,
            paymentMethod = PaymentMethod.ESPECES,
            customerId = null,
            customerName = null,
            sellerId = "seller-test",
            sellerName = "Seller Test",
            paidAmount = 200.0,
            idempotencyKey = "cash-integrity-001"
        )

        assertTrue(result.errorMessage ?: "CASH sale failed", result.isSuccess)

        val sale = database.saleDao().getSaleById(result.saleId!!)
        assertEquals("PENDING_SYNC", sale?.status)

        assertEquals(
            8,
            database.storeStockDao().getStock(productId, "A1")?.quantity
        )

        assertEquals(1, database.saleDao().countAll())
        assertEquals(1, database.stockMovementDao().countAll())
        assertEquals(1, database.pendingSyncDao().countAll())

        val outbox = database.pendingSyncDao()
            .getPendingOperationsSnapshot()
            .single()

        assertEquals("PENDING", outbox.status)
        assertEquals("SALE", outbox.entityType)
    }

    @Test
    fun creditSale_doesNotIncreaseOfficialDebtBeforeServerAck() = runBlocking {
        val result = repository.processSale(
            storeId = "A1",
            cartItems = cart(),
            saleType = SaleType.CREDIT,
            paymentMethod = PaymentMethod.ESPECES,
            customerId = customerId,
            customerName = "Test Customer",
            sellerId = "seller-test",
            sellerName = "Seller Test",
            paidAmount = 20.0,
            idempotencyKey = "credit-integrity-001"
        )

        assertTrue(result.errorMessage ?: "CREDIT sale failed", result.isSuccess)

        val customer = database.customerDao().getCustomerById(customerId)
        assertEquals(0.0, customer!!.currentDebt, 0.001)

        val sale = database.saleDao().getSaleById(result.saleId!!)
        assertEquals("PENDING_SYNC", sale?.status)
        assertEquals(80.0, sale!!.remainingAmount, 0.001)
    }

    @Test
    fun pendingCreditExposureFromA2_isIncludedWhenSellingAtA1() = runBlocking {
        database.customerDao().updateCustomer(
            database.customerDao().getCustomerById(customerId)!!.copy(
                creditLimit = 100.0,
                currentDebt = 10.0
            )
        )

        seedPendingCredit(
            storeId = "A2",
            remainingAmount = 70.0,
            idempotencyKey = "pending-a2-001"
        )

        val result = repository.processSale(
            storeId = "A1",
            cartItems = cart(),
            saleType = SaleType.CREDIT,
            paymentMethod = PaymentMethod.ESPECES,
            customerId = customerId,
            customerName = "Test Customer",
            sellerId = "seller-test",
            sellerName = "Seller Test",
            paidAmount = 0.0,
            idempotencyKey = "credit-a1-after-a2"
        )

        assertFalse("Sale should exceed global credit limit", result.isSuccess)
        assertTrue(result.errorMessage.orEmpty().contains("Plafond"))

        assertEquals(
            10,
            database.storeStockDao().getStock(productId, "A1")?.quantity
        )
        assertEquals(1, database.saleDao().countAll())
        assertEquals(0, database.stockMovementDao().countAll())
        assertEquals(0, database.pendingSyncDao().countAll())
    }

    @Test
    fun creditLimitExceeded_doesNotMutateSaleStockOrOutbox() = runBlocking {
        database.customerDao().updateCustomer(
            database.customerDao().getCustomerById(customerId)!!.copy(
                creditLimit = 50.0
            )
        )

        val result = repository.processSale(
            storeId = "A1",
            cartItems = cart(),
            saleType = SaleType.CREDIT,
            paymentMethod = PaymentMethod.ESPECES,
            customerId = customerId,
            customerName = "Test Customer",
            sellerId = "seller-test",
            sellerName = "Seller Test",
            paidAmount = 0.0,
            idempotencyKey = "credit-limit-exceeded"
        )

        assertFalse(result.isSuccess)
        assertEquals(
            10,
            database.storeStockDao().getStock(productId, "A1")?.quantity
        )
        assertEquals(0, database.saleDao().countAll())
        assertEquals(0, database.stockMovementDao().countAll())
        assertEquals(0, database.pendingSyncDao().countAll())
    }

    @Test
    fun invalidCreditFinancialValues_areRejectedWithoutMutation() = runBlocking {
        val invalidCases = listOf(
            Triple(-1, 100.0, 0.0),
            Triple(1, Double.NaN, 0.0),
            Triple(1, 100.0, Double.NaN),
            Triple(1, 100.0, -1.0)
        )

        invalidCases.forEachIndexed { index, (quantity, price, paid) ->
            val result = repository.processSale(
                storeId = "A1",
                cartItems = cart(quantity, price),
                saleType = SaleType.CREDIT,
                paymentMethod = PaymentMethod.ESPECES,
                customerId = customerId,
                customerName = "Test Customer",
                sellerId = "seller-test",
                sellerName = "Seller Test",
                paidAmount = paid,
                idempotencyKey = "invalid-credit-$index"
            )

            assertFalse("Invalid case $index was accepted", result.isSuccess)
        }

        assertEquals(
            10,
            database.storeStockDao().getStock(productId, "A1")?.quantity
        )
        assertEquals(0, database.saleDao().countAll())
        assertEquals(0, database.stockMovementDao().countAll())
        assertEquals(0, database.pendingSyncDao().countAll())
    }


    @Test
    fun decimalMgaAmounts_areRejectedWithoutMutation() = runBlocking {
        val invalidCases = listOf(
            Triple(100.5, 50.0, 0.0),
            Triple(100.0, 50.5, 0.0),
            Triple(100.0, 50.0, 20.5),
            Triple(
                MgaAmountValidator.MAX_SAFE_INTEGER.toDouble() + 1.0,
                50.0,
                0.0
            )
        )

        invalidCases.forEachIndexed { index, (price, purchasePrice, paid) ->
            val result = repository.processSale(
                storeId = "A1",
                cartItems = cart(
                    unitPrice = price,
                    purchasePrice = purchasePrice
                ),
                saleType = SaleType.CREDIT,
                paymentMethod = PaymentMethod.ESPECES,
                customerId = customerId,
                customerName = "Test Customer",
                sellerId = "seller-test",
                sellerName = "Seller Test",
                paidAmount = paid,
                idempotencyKey = "decimal-mga-$index"
            )

            assertFalse("Invalid MGA amount case $index was accepted", result.isSuccess)
            assertEquals(10, database.storeStockDao().getStock(productId, "A1")?.quantity)
            assertEquals(0, database.saleDao().countAll())
            assertEquals(0, database.stockMovementDao().countAll())
            assertEquals(0, database.pendingSyncDao().countAll())
        }
    }

    @Test
    fun duplicateIdempotencyKey_doesNotCreateSecondSaleOrStockMovement() = runBlocking {
        val first = repository.processSale(
            storeId = "A1",
            cartItems = cart(),
            saleType = SaleType.CASH,
            paymentMethod = PaymentMethod.ESPECES,
            customerId = null,
            customerName = null,
            sellerId = "seller-test",
            sellerName = "Seller Test",
            paidAmount = 100.0,
            idempotencyKey = "duplicate-cash-001"
        )

        assertTrue(first.isSuccess)

        val second = repository.processSale(
            storeId = "A1",
            cartItems = cart(),
            saleType = SaleType.CASH,
            paymentMethod = PaymentMethod.ESPECES,
            customerId = null,
            customerName = null,
            sellerId = "seller-test",
            sellerName = "Seller Test",
            paidAmount = 100.0,
            idempotencyKey = "duplicate-cash-001"
        )

        assertTrue(second.isSuccess)
        assertEquals(first.saleId, second.saleId)

        assertEquals(9, database.storeStockDao().getStock(productId, "A1")?.quantity)
        assertEquals(1, database.saleDao().countAll())
        assertEquals(1, database.stockMovementDao().countAll())
        assertEquals(1, database.pendingSyncDao().countAll())
    }

    @Test
    fun reconciliationRequiredIdempotencyKey_isTerminalAndRejected() = runBlocking {
        database.saleDao().insertSale(
            SaleEntity(
                id = "terminal-sale",
                saleNumber = "TERMINAL-001",
                storeId = "A1",
                customerId = customerId,
                customerName = "Test Customer",
                sellerId = "seller-test",
                sellerName = "Seller Test",
                saleType = SaleType.CREDIT.name,
                totalAmount = 100.0,
                paidAmount = 0.0,
                remainingAmount = 100.0,
                paymentMethod = PaymentMethod.ESPECES.name,
                status = "RECONCILIATION_REQUIRED",
                idempotencyKey = "terminal-key-001"
            )
        )

        val result = repository.processSale(
            storeId = "A1",
            cartItems = cart(),
            saleType = SaleType.CREDIT,
            paymentMethod = PaymentMethod.ESPECES,
            customerId = customerId,
            customerName = "Test Customer",
            sellerId = "seller-test",
            sellerName = "Seller Test",
            paidAmount = 0.0,
            idempotencyKey = "terminal-key-001"
        )

        assertFalse(result.isSuccess)
        assertEquals("terminal-sale", result.saleId)
        assertEquals(
            10,
            database.storeStockDao().getStock(productId, "A1")?.quantity
        )
        assertEquals(1, database.saleDao().countAll())
        assertEquals(0, database.stockMovementDao().countAll())
    }
}
