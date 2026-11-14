package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
import com.example.data.repository.AdminSetupResult
import com.example.data.repository.AuthRepository
import com.example.data.repository.AuthResult
import com.example.data.repository.FanevaRepository
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

        val rejectedProduct = runCatching {
            repository.addNewProduct(
                "P-A2", "Wrong Store", "Audit",
                100.0, 150.0, 1, "Pcs", mapOf("A2" to 2)
            )
        }
        assertTrue(rejectedProduct.isFailure)

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
}
