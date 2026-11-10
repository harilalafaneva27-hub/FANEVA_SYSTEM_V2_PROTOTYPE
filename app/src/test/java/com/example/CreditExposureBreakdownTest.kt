package com.example

import com.example.data.local.entity.SaleEntity
import com.example.domain.validation.CreditExposureCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreditExposureBreakdownTest {

    private fun sale(
        id: String,
        customerId: Long? = 7L,
        type: String = "CREDIT",
        status: String = "PENDING_SYNC",
        amount: Double = 100.0,
        paid: Double = 0.0,
        store: String = "A1",
        remaining: Double = amount - paid
    ) = SaleEntity(
        id = id,
        saleNumber = "SALE-$id",
        storeId = store,
        customerId = customerId,
        customerName = "Client",
        sellerId = "seller",
        sellerName = "Seller",
        saleType = type,
        totalAmount = amount,
        paidAmount = paid,
        remainingAmount = remaining,
        paymentMethod = "ESPECES",
        status = status,
        idempotencyKey = "key-$id"
    )

    @Test
    fun `separates both pending statuses for one customer across stores`() {
        val result = CreditExposureCalculator.calculatePendingExposureBreakdown(
            listOf(
                sale("1", amount = 100.0, store = "A1"),
                sale("2", amount = 200.0, store = "A2"),
                sale("3", status = "RECONCILIATION_REQUIRED", amount = 50.0, store = "A5"),
                sale("4", customerId = 8L, amount = 900.0)
            ),
            7L
        )!!

        assertEquals(300.0, result.pendingSync, 0.0)
        assertEquals(50.0, result.reconciliationRequired, 0.0)
        assertEquals(350.0, result.total, 0.0)
    }

    @Test
    fun `portfolio excludes cash completed and sales without customer`() {
        val result = CreditExposureCalculator.calculatePortfolioPendingExposureBreakdown(
            listOf(
                sale("1", customerId = 7L, amount = 100.0),
                sale("2", customerId = 8L, amount = 200.0, store = "A2"),
                sale("3", type = "CASH", amount = 300.0),
                sale("4", status = "COMPLETED", amount = 400.0),
                sale("5", customerId = null, amount = 500.0)
            ),
            setOf(7L)
        )!!

        assertEquals(300.0, result.pendingSync, 0.0)
        assertEquals(0.0, result.reconciliationRequired, 0.0)
        assertEquals(200.0, result.nonReferencedCustomerExposure, 0.0)
        assertEquals(300.0, result.total, 0.0)
    }

    @Test
    fun `portfolio separates reconciliation exposure`() {
        val result = CreditExposureCalculator.calculatePortfolioPendingExposureBreakdown(
            listOf(
                sale("1", amount = 100.0),
                sale("2", status = "RECONCILIATION_REQUIRED", amount = 250.0)
            ),
            setOf(7L)
        )!!

        assertEquals(100.0, result.pendingSync, 0.0)
        assertEquals(250.0, result.reconciliationRequired, 0.0)
        assertEquals(0.0, result.nonReferencedCustomerExposure, 0.0)
        assertEquals(350.0, result.total, 0.0)
    }

    @Test
    fun `counts orphan exposure across both pending statuses without double counting`() {
        val result = CreditExposureCalculator.calculatePortfolioPendingExposureBreakdown(
            listOf(
                sale("1", customerId = 7L, amount = 100.0, store = "A1"),
                sale("2", customerId = 99L, amount = 150.0, store = "A2"),
                sale(
                    "3",
                    customerId = 99L,
                    status = "RECONCILIATION_REQUIRED",
                    amount = 250.0,
                    store = "A5"
                )
            ),
            setOf(7L)
        )!!

        assertEquals(250.0, result.pendingSync, 0.0)
        assertEquals(250.0, result.reconciliationRequired, 0.0)
        assertEquals(400.0, result.nonReferencedCustomerExposure, 0.0)
        assertEquals(500.0, result.total, 0.0)
    }

    @Test
    fun `rejects invalid amounts in pending credit sales`() {
        listOf(-1.0, 10.5, Double.NaN, Double.POSITIVE_INFINITY).forEach { amount ->
            assertNull(
                CreditExposureCalculator.calculatePendingExposureBreakdown(
                    listOf(sale("invalid-$amount", amount = amount)),
                    7L
                )
            )
        }
    }

    @Test
    fun `rejects overflow in combined portfolio exposure`() {
        val result = CreditExposureCalculator.calculatePortfolioPendingExposureBreakdown(
            listOf(
                sale("1", amount = 9_007_199_254_740_991.0),
                sale("2", status = "RECONCILIATION_REQUIRED", amount = 1.0)
            ),
            setOf(7L)
        )

        assertNull(result)
    }


    @Test
    fun `includes pending credit exposure from all five stores`() {
        val result = CreditExposureCalculator.calculatePortfolioPendingExposureBreakdown(
            listOf(
                sale("A1", amount = 100.0, store = "A1"),
                sale("A2", amount = 200.0, store = "A2"),
                sale("A3", amount = 300.0, store = "A3"),
                sale("A4", amount = 400.0, store = "A4"),
                sale("A5", amount = 500.0, store = "A5")
            ),
            setOf(7L)
        )!!

        assertEquals(1500.0, result.pendingSync, 0.0)
        assertEquals(0.0, result.reconciliationRequired, 0.0)
        assertEquals(0.0, result.nonReferencedCustomerExposure, 0.0)
        assertEquals(1500.0, result.total, 0.0)
    }

    @Test
    fun `rejects invalid amount in portfolio pending credit sales`() {
        val result = CreditExposureCalculator.calculatePortfolioPendingExposureBreakdown(
            listOf(
                sale("valid", amount = 100.0, store = "A1"),
                sale("invalid", amount = 10.5, store = "A5")
            ),
            setOf(7L)
        )

        assertNull(result)
    }

    @Test
    fun `rejects unknown store in customer pending credit exposure`() {
        val result = CreditExposureCalculator.calculatePendingExposureBreakdown(
            listOf(
                sale("valid-store", amount = 100.0, store = "A1"),
                sale("unknown-store", amount = 200.0, store = "A6")
            ),
            7L
        )

        assertNull(result)
    }

    @Test
    fun `rejects unknown store in portfolio pending credit exposure`() {
        val result = CreditExposureCalculator.calculatePortfolioPendingExposureBreakdown(
            listOf(
                sale("valid-store", amount = 100.0, store = "A5"),
                sale("unknown-store", customerId = 99L, amount = 200.0, store = "UNKNOWN")
            ),
            setOf(7L)
        )

        assertNull(result)
    }


    @Test
    fun `uses remaining balance after partial upfront payment for customer and portfolio exposure`() {
        val creditSale = sale("acompte", amount = 1000.0, paid = 300.0)

        val customerResult =
            CreditExposureCalculator.calculatePendingExposureBreakdown(
                listOf(creditSale),
                7L
            )!!

        val portfolioResult =
            CreditExposureCalculator.calculatePortfolioPendingExposureBreakdown(
                listOf(creditSale),
                setOf(7L)
            )!!

        assertEquals(700.0, customerResult.total, 0.0)
        assertEquals(700.0, portfolioResult.total, 0.0)
    }


    @Test
    fun `rejects invalid amounts in reconciliation required credit sales`() {
        listOf(-1.0, 10.5, Double.NaN, Double.POSITIVE_INFINITY).forEach { amount ->
            assertNull(
                CreditExposureCalculator.calculatePendingExposureBreakdown(
                    listOf(
                        sale(
                            "invalid-reconciliation-$amount",
                            status = "RECONCILIATION_REQUIRED",
                            amount = amount
                        )
                    ),
                    7L
                )
            )
        }
    }

    @Test
    fun `validates projected exposure inputs and combined total`() {
        assertEquals(
            600.0,
            CreditExposureCalculator.calculateProjectedExposure(
                officialDebt = 100.0,
                pendingExposure = 200.0,
                currentSaleRemaining = 300.0
            )!!,
            0.0
        )

        listOf(-1.0, 10.5, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertNull(
                CreditExposureCalculator.calculateProjectedExposure(
                    officialDebt = invalid,
                    pendingExposure = 0.0,
                    currentSaleRemaining = 0.0
                )
            )
        }

        assertNull(
            CreditExposureCalculator.calculateProjectedExposure(
                officialDebt = 9_007_199_254_740_991.0,
                pendingExposure = 1.0,
                currentSaleRemaining = 0.0
            )
        )
    }

    @Test
    fun `rejects upfront payment greater than credit sale total`() {
        val invalidSale = sale(
            "acompte-too-large",
            amount = 1000.0,
            paid = 1200.0
        )

        assertNull(
            CreditExposureCalculator.calculatePendingExposureBreakdown(
                listOf(invalidSale),
                7L
            )
        )

        assertNull(
            CreditExposureCalculator.calculatePortfolioPendingExposureBreakdown(
                listOf(invalidSale),
                setOf(7L)
            )
        )
    }


    @Test
    fun `rejects decimal zero and negative CREDIT sale totals`() {
        listOf(10.5, 0.0, -1.0).forEachIndexed { index, total ->
            assertNull(
                CreditExposureCalculator.calculatePendingExposureBreakdown(
                    listOf(sale("invalid-total-$index", amount = total, paid = 0.0)),
                    7L
                )
            )
        }
    }

    @Test
    fun `rejects decimal and negative CREDIT upfront payments`() {
        val invalidPaidAmounts = listOf(10.5, -1.0)

        invalidPaidAmounts.forEachIndexed { index, paid ->
            assertNull(
                CreditExposureCalculator.calculatePendingExposureBreakdown(
                    listOf(sale("invalid-paid-$index", amount = 100.0, paid = paid)),
                    7L
                )
            )
        }
    }

    @Test
    fun `rejects remaining balance inconsistent with total and upfront payment`() {
        val inconsistentSale = sale(
            "inconsistent-balance",
            amount = 1000.0,
            paid = 300.0,
            remaining = 650.0
        )

        assertNull(
            CreditExposureCalculator.calculatePendingExposureBreakdown(
                listOf(inconsistentSale),
                7L
            )
        )
        assertNull(
            CreditExposureCalculator.calculatePortfolioPendingExposureBreakdown(
                listOf(inconsistentSale),
                setOf(7L)
            )
        )
    }

    @Test
    fun `rejects decimal remaining balance even when total and payment are valid`() {
        val invalidSale = sale(
            "decimal-remaining",
            amount = 1000.0,
            paid = 300.0,
            remaining = 699.5
        )

        assertNull(
            CreditExposureCalculator.calculatePendingExposureBreakdown(
                listOf(invalidSale),
                7L
            )
        )
    }

}
