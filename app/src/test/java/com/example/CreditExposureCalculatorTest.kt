package com.example

import com.example.data.local.entity.SaleEntity
import com.example.domain.validation.CreditExposureCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreditExposureCalculatorTest {

    private fun sale(
        customerId: Long? = 7L,
        saleType: String = "CREDIT",
        status: String = "PENDING_SYNC",
        remaining: Double = 100.0,
        storeId: String = "A1",
        totalAmount: Double = remaining,
        paidAmount: Double = 0.0
    ) = SaleEntity(
        id = "id-$storeId-$status-$remaining",
        saleNumber = "SALE-$storeId",
        storeId = storeId,
        customerId = customerId,
        customerName = "Client",
        sellerId = "seller-1",
        sellerName = "Seller",
        saleType = saleType,
        totalAmount = totalAmount,
        paidAmount = paidAmount,
        remainingAmount = remaining,
        paymentMethod = "ESPECES",
        status = status,
        idempotencyKey = "key-$storeId-$status-$remaining"
    )

    @Test
    fun `sums pending credit exposure across stores and both pending statuses`() {
        val sales = listOf(
            sale(remaining = 100.0, storeId = "A1"),
            sale(remaining = 250.0, storeId = "A2"),
            sale(remaining = 50.0, storeId = "A3", status = "RECONCILIATION_REQUIRED"),
            sale(remaining = 900.0, storeId = "A4", status = "COMPLETED"),
            sale(remaining = 800.0, storeId = "A5", saleType = "CASH"),
            sale(remaining = 700.0, storeId = "A1", customerId = 99L)
        )

        assertEquals(
            400.0,
            CreditExposureCalculator.calculatePendingExposure(sales, 7L)!!,
            0.0
        )
    }

    @Test
    fun `returns zero when customer has no pending credit sales`() {
        assertEquals(
            0.0,
            CreditExposureCalculator.calculatePendingExposure(
                listOf(sale(customerId = 99L)),
                7L
            )!!,
            0.0
        )
    }

    @Test
    fun `rejects invalid pending remaining amounts`() {
        listOf(-1.0, 10.5, Double.NaN, Double.POSITIVE_INFINITY).forEach { amount ->
            assertNull(
                CreditExposureCalculator.calculatePendingExposure(
                    listOf(sale(remaining = amount)),
                    7L
                )
            )
        }
    }


    @Test
    fun `rejects pending credit sales with unknown store ids`() {
        assertNull(
            CreditExposureCalculator.calculatePendingExposure(
                listOf(sale(storeId = "A6")),
                7L
            )
        )
    }

    @Test
    fun `rejects pending credit sales with invalid amount coherence`() {
        val invalidSales = listOf(
            sale(totalAmount = 0.0, paidAmount = 0.0, remaining = 0.0),
            sale(totalAmount = 100.5, paidAmount = 0.0, remaining = 100.5),
            sale(totalAmount = 100.0, paidAmount = -1.0, remaining = 101.0),
            sale(totalAmount = 100.0, paidAmount = 101.0, remaining = 0.0),
            sale(totalAmount = 1000.0, paidAmount = 300.0, remaining = 650.0),
            sale(totalAmount = 1000.0, paidAmount = 300.0, remaining = 699.5)
        )

        invalidSales.forEach { invalidSale ->
            assertNull(
                CreditExposureCalculator.calculatePendingExposure(
                    listOf(invalidSale),
                    7L
                )
            )
        }
    }

    @Test
    fun `calculates projected exposure using official pending and current sale amounts`() {
        assertEquals(
            1750.0,
            CreditExposureCalculator.calculateProjectedExposure(
                officialDebt = 1000.0,
                pendingExposure = 400.0,
                currentSaleRemaining = 350.0
            )!!,
            0.0
        )
    }

    @Test
    fun `rejects decimal negative and unsafe projected exposure inputs`() {
        assertNull(
            CreditExposureCalculator.calculateProjectedExposure(
                100.5, 0.0, 0.0
            )
        )
        assertNull(
            CreditExposureCalculator.calculateProjectedExposure(
                100.0, -1.0, 0.0
            )
        )
        assertNull(
            CreditExposureCalculator.calculateProjectedExposure(
                9_007_199_254_740_991.0, 1.0, 0.0
            )
        )
    }
}
