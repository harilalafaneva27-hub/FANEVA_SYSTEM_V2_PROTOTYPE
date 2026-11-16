package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
import com.example.data.repository.AdminSetupResult
import com.example.data.repository.AuthRepository
import com.example.data.repository.AuthResult
import com.example.data.repository.FanevaRepository
import com.example.domain.model.CartItem
import com.example.domain.model.PaymentMethod
import com.example.domain.model.SaleType
import kotlinx.coroutines.flow.first
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
class RepositoryAuthorizationTest {
    private lateinit var db: FanevaDatabase
    private lateinit var auth: AuthRepository
    private lateinit var repository: FanevaRepository

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FanevaDatabase::class.java)
            .allowMainThreadQueries().build()
        auth = AuthRepository(db.authDao(), db.adminRecoveryDao(), db.securitySessionDao())
        repository = FanevaRepository(db)
    }

    @After fun close() = db.close()


    @Test fun persistedSessionRevocationImmediatelyClosesRepositoryReadFlow() = runBlocking {
        auth.initializeFirstAdmin(
            "revokereadowner", "Owner", "StrongPassphrase-2026!".toCharArray()
        )
        val admin = auth.authenticateAdmin(
            "revokereadowner", "StrongPassphrase-2026!".toCharArray()
        ) as AuthResult.Success
        val productId = db.productDao().insertProduct(
            com.example.data.local.entity.ProductEntity(
                code = "REVOKE-READ-01", name = "Revocation test", category = "Audit",
                purchasePrice = 100.0, sellingPrice = 150.0
            )
        )

        assertEquals(productId, repository.allProducts.first { it.isNotEmpty() }.single().id)

        assertTrue(auth.revokeSession(admin.session.sessionId))
        val closed = kotlinx.coroutines.withTimeout(3_000L) {
            repository.allProducts.first { it.isEmpty() }
        }
        assertTrue(closed.isEmpty())
    }

    @Test fun protectedReadFlowsAreEmptyWithoutPersistedSession() = runBlocking {
        assertTrue(repository.allProducts.first().isEmpty())
        assertTrue(repository.allStocks.first().isEmpty())
        assertTrue(repository.allCustomers.first().isEmpty())
        assertTrue(repository.allSales.first().isEmpty())
        assertTrue(repository.allMovements.first().isEmpty())
        assertTrue(repository.debtPayments.first().isEmpty())
        assertTrue(repository.pendingOperations.first().isEmpty())
        assertNull(repository.syncCursor.first())
        assertTrue(repository.searchProducts("test").first().isEmpty())
        assertTrue(repository.getStocksByStore("A1").first().isEmpty())
        assertEquals(0, repository.getStockForProductAndStore(1L, "A1"))
    }

    @Test fun sellerReadFlowsRedactSensitiveFieldsAndRestrictSalesAndStores() = runBlocking {
        auth.initializeFirstAdmin("readowner", "Owner", "StrongPassphrase-2026!".toCharArray())
        val admin = auth.authenticateAdmin(
            "readowner", "StrongPassphrase-2026!".toCharArray()
        ) as AuthResult.Success
        assertTrue(auth.createSeller(
            admin.session, "readseller", "Seller",
            "SellerStrongPass-2026!".toCharArray()
        ).isSuccess)
        assertEquals(listOf("readseller"), repository.sellerAccounts.first().map { it.username })
        val seller = auth.authenticateSeller(
            "readseller", "SellerStrongPass-2026!".toCharArray()
        ) as AuthResult.Success

        val productId = db.productDao().insertProduct(
            com.example.data.local.entity.ProductEntity(
                code = "READ-P1", name = "Read product", category = "Audit",
                purchasePrice = 125.0, sellingPrice = 200.0
            )
        )
        db.storeStockDao().insertAll(listOf(
            com.example.data.local.entity.StoreStockEntity("A1", productId, 7),
            com.example.data.local.entity.StoreStockEntity("A2", productId, 99)
        ))
        db.customerDao().insertCustomer(
            com.example.data.local.entity.CustomerEntity(
                name = "Private debt", phone = "0340000000", address = "A1",
                creditLimit = 1000.0, currentDebt = 650.0
            )
        )
        fun sale(id: String, store: String, sellerId: String, remaining: Double = 0.0) =
            com.example.data.local.entity.SaleEntity(
                id = id, saleNumber = "SALE-$id", storeId = store,
                customerId = if (id == "own") 1L else null,
                customerName = if (id == "own") "Private POS customer" else null,
                sellerId = sellerId, sellerName = "Seller", saleType = "CASH",
                totalAmount = 200.0, paidAmount = 200.0, remainingAmount = remaining,
                paymentMethod = "ESPECES", idempotencyKey = "KEY-$id"
            )
        db.saleDao().insertSale(sale("own", "A1", seller.session.id, remaining = 80.0))
        db.saleDao().insertSale(sale("other-seller", "A1", "another-user"))
        db.saleDao().insertSale(sale("other-store", "A2", seller.session.id))

        val products = repository.allProducts.first()
        assertEquals(1, products.size)
        assertEquals(0.0, products.single().purchasePrice, 0.0)

        assertTrue(repository.allCustomers.first().isEmpty())
        val posCustomers = repository.posCustomers.first()
        assertEquals(1, posCustomers.size)
        assertEquals("", posCustomers.single().phone)
        assertEquals("", posCustomers.single().address)
        assertEquals(0.0, posCustomers.single().currentDebt, 0.0)
        assertEquals(0.0, posCustomers.single().creditLimit, 0.0)

        assertEquals(listOf("A1"), repository.allStocks.first().map { it.storeId }.distinct())
        val sellerSales = repository.allSales.first()
        assertEquals(listOf("own"), sellerSales.map { it.id })
        assertNull(sellerSales.single().customerId)
        assertNull(sellerSales.single().customerName)
        assertEquals(0.0, sellerSales.single().paidAmount, 0.0)
        assertEquals(0.0, sellerSales.single().remainingAmount, 0.0)
        assertEquals("", sellerSales.single().idempotencyKey)
        assertTrue(repository.allMovements.first().isEmpty())
        assertTrue(repository.debtPayments.first().isEmpty())
        assertTrue(repository.pendingOperations.first().isEmpty())
        assertEquals(0, repository.pendingCount.first())
        assertNull(repository.syncCursor.first())
        assertTrue(repository.sellerAccounts.first().isEmpty())
        assertTrue(runCatching { repository.getSaleDetails("own") }.isSuccess)
        assertTrue(runCatching { repository.getSaleDetails("other-seller") }.isFailure)
    }

    @Test fun sellerCreditLimitDenialDoesNotExposeCustomerFinancialDetails() = runBlocking {
        auth.initializeFirstAdmin(
            "creditowner", "Owner", "StrongPassphrase-2026!".toCharArray()
        )
        val admin = auth.authenticateAdmin(
            "creditowner", "StrongPassphrase-2026!".toCharArray()
        ) as AuthResult.Success
        assertTrue(auth.createSeller(
            admin.session, "creditseller", "Seller",
            "SellerStrongPass-2026!".toCharArray()
        ).isSuccess)
        val seller = auth.authenticateSeller(
            "creditseller", "SellerStrongPass-2026!".toCharArray()
        ) as AuthResult.Success

        val productId = db.productDao().insertProduct(
            com.example.data.local.entity.ProductEntity(
                code = "CREDIT-READ-01", name = "Credit test product", category = "Audit",
                purchasePrice = 100.0, sellingPrice = 200.0
            )
        )
        db.storeStockDao().insertAll(listOf(
            com.example.data.local.entity.StoreStockEntity("A1", productId, 5)
        ))
        val customerId = db.customerDao().insertCustomer(
            com.example.data.local.entity.CustomerEntity(
                name = "Private credit customer", phone = "0340000000", address = "A1",
                creditLimit = 1000.0, currentDebt = 950.0
            )
        )

        val result = repository.processSale(
            storeId = "A1",
            cartItems = listOf(
                CartItem(
                    productId = productId,
                    productCode = "CREDIT-READ-01",
                    productName = "Credit test product",
                    unit = "Pcs",
                    unitPrice = 200.0,
                    purchasePrice = 0.0,
                    quantity = 1,
                    availableInActiveStore = 5
                )
            ),
            saleType = SaleType.CREDIT,
            paymentMethod = PaymentMethod.ESPECES,
            customerId = customerId,
            customerName = "Private credit customer",
            sellerId = seller.session.id,
            sellerName = seller.session.name,
            paidAmount = 0.0,
            idempotencyKey = "seller-credit-limit-test"
        )

        assertFalse(result.isSuccess)
        assertEquals(
            "Vente à crédit refusée : plafond de crédit atteint. Contacter un ADMIN/GERANT.",
            result.errorMessage
        )
        assertFalse(result.errorMessage.orEmpty().contains("950"))
        assertFalse(result.errorMessage.orEmpty().contains("1000"))
    }

    @Test fun customerCreationAndPreferenceMutationAreDeniedWithoutPersistedSession() = runBlocking {
        val customer = runCatching {
            repository.addNewCustomer("Unauthorized", "", "", 0.0, "A1")
        }
        assertTrue(customer.isFailure)
        assertEquals(0, db.customerDao().countAll())
        val preference = runCatching {
            repository.updateUserPreference(
                com.example.domain.model.UserSession("forged", "Forged", "forged", com.example.domain.model.UserRole.ADMIN),
                "A1", "Forged"
            )
        }
        assertTrue(preference.isFailure)
    }

    @Test fun transferPreparationAndRecoveryAreDeniedWithoutPersistedSession() = runBlocking {
        assertTrue(runCatching { repository.getOrCreateTransferIdempotencyKey() }.isFailure)
        assertTrue(runCatching { repository.recoverPreparedTransfers() }.isFailure)
        assertNull(db.userPreferenceDao().getPreferences()?.activeTransferIdempotencyKey)
    }

    @Test fun adminProductCreationWritesOnlyExplicitlyAuthorizedStoreStocks() = runBlocking {
        auth.initializeFirstAdmin(
            "owner02", "Owner", "StrongPassphrase-2026!".toCharArray()
        )
        val admin = auth.authenticateAdmin(
            "owner02", "StrongPassphrase-2026!".toCharArray()
        ) as AuthResult.Success
        val now = System.currentTimeMillis()
        db.securitySessionDao().replaceStoreAccess(
            admin.session.id,
            listOf(
                com.example.data.local.entity.AuthAccountStoreAccessEntity(
                    accountId = admin.session.id,
                    storeId = "A1",
                    grantedAt = now,
                    grantedBy = admin.session.id
                )
            )
        )
        val productId = repository.addNewProduct(
            "P-ADMIN-A1", "A1 Scoped Product", "Audit",
            100.0, 150.0, 1, "Pcs", mapOf("A1" to 3)
        )
        assertEquals(3, db.storeStockDao().getStock(productId, "A1")?.quantity)
        assertNull("A1-scoped ADMIN must not receive implicit A2 stock", db.storeStockDao().getStock(productId, "A2"))
    }

    @Test fun directRepositoryMutationsAreDeniedWithoutPersistedSession() = runBlocking {
        val product = runCatching {
            repository.addNewProduct(
                "P-UNAUTH", "Unauthorized", "Audit",
                100.0, 150.0, 1, "Pcs", mapOf("A1" to 2)
            )
        }
        assertTrue(product.isFailure)
        assertTrue(repository.recordDebtPayment(
            customerId = 1,
            amount = 10.0,
            paymentMethod = PaymentMethod.ESPECES,
            note = "",
            operatorName = "forged"
        ).isFailure)
        assertEquals(0, db.productDao().countAll())
    }

    @Test fun repositoryRejectsSellerMutationOutsideAssignedStore() = runBlocking {
        auth.initializeFirstAdmin(
            "owner01", "Owner", "StrongPassphrase-2026!".toCharArray()
        ) as AdminSetupResult.Created
        val admin = auth.authenticateAdmin(
            "owner01", "StrongPassphrase-2026!".toCharArray()
        ) as AuthResult.Success
        assertTrue(auth.createSeller(
            admin.session, "seller01", "Seller",
            "SellerStrongPass-2026!".toCharArray()
        ).isSuccess)
        val seller = auth.authenticateSeller(
            "seller01", "SellerStrongPass-2026!".toCharArray()
        ) as AuthResult.Success

        val rejectedProductA2 = runCatching {
            repository.addNewProduct(
                "P-A2", "Wrong Store", "Audit",
                100.0, 150.0, 1, "Pcs", mapOf("A2" to 2)
            )
        }
        val rejectedProductA1 = runCatching {
            repository.addNewProduct(
                "P-A1", "Seller Product", "Audit",
                100.0, 150.0, 1, "Pcs", mapOf("A1" to 3)
            )
        }
        assertTrue(rejectedProductA2.isFailure)
        assertTrue(rejectedProductA1.isFailure)
        assertEquals(0, db.productDao().countAll())

        val rejectedSale = repository.processSale(
            storeId = "A2",
            cartItems = emptyList(),
            saleType = SaleType.CASH,
            paymentMethod = PaymentMethod.ESPECES,
            customerId = null,
            customerName = null,
            sellerId = seller.session.id,
            sellerName = seller.session.name,
            paidAmount = 0.0
        )
        assertFalse(rejectedSale.isSuccess)
        assertEquals(0, db.saleDao().countAll())

        assertTrue(repository.recordDebtPayment(
            customerId = 1,
            amount = 10.0,
            paymentMethod = PaymentMethod.ESPECES,
            note = "",
            operatorName = seller.session.name
        ).isFailure)
    }
    @Test fun repositoryRejectsFractionalMGASaleAndPaymentBeforeAnyMutation() = runBlocking {
        auth.initializeFirstAdmin(
            "mgaowner", "Owner", "StrongPassphrase-2026!".toCharArray()
        )
        val admin = auth.authenticateAdmin(
            "mgaowner", "StrongPassphrase-2026!".toCharArray()
        ) as AuthResult.Success
        val productId = db.productDao().insertProduct(
            com.example.data.local.entity.ProductEntity(
                code = "MGA-VALID-01", name = "MGA validation", category = "Audit",
                purchasePrice = 100.0, sellingPrice = 150.0
            )
        )
        db.storeStockDao().insertAll(listOf(
            com.example.data.local.entity.StoreStockEntity("A1", productId, 10)
        ))
        val customerId = db.customerDao().insertCustomer(
            com.example.data.local.entity.CustomerEntity(
                name = "MGA debt", phone = "", address = "",
                creditLimit = 1000.0, currentDebt = 200.0
            )
        )

        val fractionalSale = repository.processSale(
            storeId = "A1",
            cartItems = listOf(CartItem(
                productId = productId, productCode = "MGA-VALID-01",
                productName = "MGA validation", unit = "Pcs",
                unitPrice = 150.5, purchasePrice = 100.0,
                quantity = 1, availableInActiveStore = 10
            )),
            saleType = SaleType.CASH, paymentMethod = PaymentMethod.ESPECES,
            customerId = null, customerName = null,
            sellerId = admin.session.id, sellerName = admin.session.name,
            paidAmount = 0.0, idempotencyKey = "mga-fractional-sale"
        )
        assertFalse(fractionalSale.isSuccess)
        assertEquals(0, db.saleDao().countAll())
        assertEquals(10, db.storeStockDao().getStock(productId, "A1")!!.quantity)
        assertEquals(0, db.pendingSyncDao().countAll())

        val fractionalPayment = repository.recordDebtPayment(
            customerId = customerId, amount = 10.5,
            paymentMethod = PaymentMethod.ESPECES, note = "",
            operatorName = admin.session.name, idempotencyKey = "mga-fractional-payment"
        )
        assertTrue(fractionalPayment.isFailure)
        assertEquals(200.0, db.customerDao().getCustomerById(customerId)!!.currentDebt, 0.0)
        assertEquals(0, db.debtPaymentDao().countAll())
    }

}
