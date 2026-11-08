package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.CustomerEntity
import com.example.data.repository.FanevaRepository
import com.example.domain.model.PaymentMethod
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
class CustomerDebtIntegrityTest {

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
    fun decimalCreditLimit_isRejectedWithoutCreatingCustomerOrOutbox() = runBlocking {
        val result = runCatching {
            repository.addNewCustomer(
                name = "Client test",
                phone = "0340000000",
                address = "Test",
                creditLimit = 100_000.5
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, database.customerDao().countAll())
        assertEquals(0, database.pendingSyncDao().countAll())
    }

    @Test
    fun decimalDebtPayment_isRejectedWithoutMutation() = runBlocking {
        val customerId = database.customerDao().insertCustomer(
            CustomerEntity(
                name = "Client test",
                phone = "0340000000",
                address = "Test",
                creditLimit = 200_000.0,
                currentDebt = 100_000.0
            )
        )

        val result = repository.recordDebtPayment(
            customerId = customerId,
            amount = 10_000.5,
            paymentMethod = PaymentMethod.ESPECES,
            note = "Test montant décimal",
            operatorName = "Admin",
            idempotencyKey = "TEST-DECIMAL-PAYMENT"
        )

        assertTrue(result.isFailure)
        assertEquals(
            100_000.0,
            database.customerDao().getCustomerById(customerId)!!.currentDebt,
            0.0
        )
        assertEquals(0, database.debtPaymentDao().countAll())
        assertEquals(0, database.pendingSyncDao().countAll())
    }

    @Test
    fun decimalStoredDebt_isRejectedWithoutMutation() = runBlocking {
        val customerId = database.customerDao().insertCustomer(
            CustomerEntity(
                name = "Client dette invalide",
                phone = "0340000001",
                address = "Test",
                creditLimit = 200_000.0,
                currentDebt = 100_000.5
            )
        )

        val result = repository.recordDebtPayment(
            customerId = customerId,
            amount = 10_000.0,
            paymentMethod = PaymentMethod.ESPECES,
            note = "Test dette décimale",
            operatorName = "Admin",
            idempotencyKey = "TEST-INVALID-STORED-DEBT"
        )

        assertTrue(result.isFailure)
        assertEquals(
            100_000.5,
            database.customerDao().getCustomerById(customerId)!!.currentDebt,
            0.0
        )
        assertEquals(0, database.debtPaymentDao().countAll())
        assertEquals(0, database.pendingSyncDao().countAll())
    }


    @Test
    fun validWholeNumberDebtPayment_updatesDebtAndCreatesPaymentAndOutbox() = runBlocking {
        val customerId = database.customerDao().insertCustomer(
            CustomerEntity(
                name = "Client paiement valide",
                phone = "0340000002",
                address = "Test",
                creditLimit = 600_000.0,
                currentDebt = 500_000.0
            )
        )

        val result = repository.recordDebtPayment(
            customerId = customerId,
            amount = 125_000.0,
            paymentMethod = PaymentMethod.ESPECES,
            note = "Paiement entier",
            operatorName = "Admin",
            idempotencyKey = "TEST-WHOLE-NUMBER-PAYMENT"
        )

        assertTrue(result.isSuccess)
        assertEquals(
            375_000.0,
            database.customerDao().getCustomerById(customerId)!!.currentDebt,
            0.0
        )
        assertEquals(1, database.debtPaymentDao().countAll())
        assertEquals(1, database.pendingSyncDao().countAll())
    }



    @Test
    fun retryWithSameKey_returnsExistingReceiptWithoutApplyingDebtTwice() = runBlocking {
        val customerId = database.customerDao().insertCustomer(
            CustomerEntity(
                name = "Client retry",
                phone = "0340000010",
                address = "Test",
                creditLimit = 600_000.0,
                currentDebt = 500_000.0
            )
        )

        val first = repository.recordDebtPayment(
            customerId = customerId,
            amount = 125_000.0,
            paymentMethod = PaymentMethod.ESPECES,
            note = "Paiement retry",
            operatorName = "Admin",
            idempotencyKey = "DEBT-RETRY-SAME-KEY"
        )

        val retry = repository.recordDebtPayment(
            customerId = customerId,
            amount = 125_000.0,
            paymentMethod = PaymentMethod.ESPECES,
            note = "Paiement retry",
            operatorName = "Admin",
            idempotencyKey = "DEBT-RETRY-SAME-KEY"
        )

        assertTrue(first.isSuccess)
        assertTrue(retry.isSuccess)
        assertEquals(first.getOrThrow(), retry.getOrThrow())

        assertEquals(
            375_000.0,
            database.customerDao().getCustomerById(customerId)!!.currentDebt,
            0.0
        )

        assertEquals(1, database.debtPaymentDao().countAll())
        assertEquals(1, database.pendingSyncDao().countAll())

        val payment = database.debtPaymentDao()
            .getPaymentByIdempotencyKey("DEBT-RETRY-SAME-KEY")
        assertEquals(125_000.0, payment!!.amountPaid, 0.0)

        val outbox = database.pendingSyncDao()
            .getByIdempotencyKey("DEBT-RETRY-SAME-KEY")
        assertEquals("DEBT_PAYMENT", outbox!!.entityType)
    }

    @Test
    fun sameIdempotencyKeyWithDifferentAmount_isRejectedWithoutMutation() = runBlocking {
        val customerId = database.customerDao().insertCustomer(
            CustomerEntity(
                name = "Client conflit",
                phone = "0340000011",
                address = "Test",
                creditLimit = 600_000.0,
                currentDebt = 500_000.0
            )
        )

        val first = repository.recordDebtPayment(
            customerId = customerId,
            amount = 125_000.0,
            paymentMethod = PaymentMethod.ESPECES,
            note = "Paiement initial",
            operatorName = "Admin",
            idempotencyKey = "DEBT-RETRY-CONFLICT"
        )

        val conflict = repository.recordDebtPayment(
            customerId = customerId,
            amount = 200_000.0,
            paymentMethod = PaymentMethod.ESPECES,
            note = "Paiement initial",
            operatorName = "Admin",
            idempotencyKey = "DEBT-RETRY-CONFLICT"
        )

        assertTrue(first.isSuccess)
        assertTrue(
            "A reused idempotency key with a different amount must be rejected",
            conflict.isFailure
        )

        assertEquals(
            375_000.0,
            database.customerDao().getCustomerById(customerId)!!.currentDebt,
            0.0
        )

        assertEquals(1, database.debtPaymentDao().countAll())
        assertEquals(1, database.pendingSyncDao().countAll())

        val payment = database.debtPaymentDao()
            .getPaymentByIdempotencyKey("DEBT-RETRY-CONFLICT")
        assertEquals(125_000.0, payment!!.amountPaid, 0.0)
        assertEquals("Paiement initial", payment.note)
    }

}
