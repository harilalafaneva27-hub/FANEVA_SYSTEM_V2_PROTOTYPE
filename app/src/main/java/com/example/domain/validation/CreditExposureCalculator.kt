package com.example.domain.validation

import com.example.data.local.entity.SaleEntity
import com.example.domain.model.StoreId

data class CreditExposureBreakdown(
    val pendingSync: Double,
    val reconciliationRequired: Double,
    val nonReferencedCustomerExposure: Double = 0.0
) {
    val total: Double
        get() = pendingSync + reconciliationRequired
}

/**
 * Calcul local de l'exposition CREDIT non confirmée.
 * Même périmètre que la requête DAO : client, type CREDIT et
 * statuts PENDING_SYNC / RECONCILIATION_REQUIRED, magasins A1 à A5.
 */
object CreditExposureCalculator {

    private val pendingStatuses = setOf(
        "PENDING_SYNC",
        "RECONCILIATION_REQUIRED"
    )

    private val validStoreCodes = StoreId.entries.map { it.code }.toSet()

    fun calculatePendingExposureBreakdown(
        sales: List<SaleEntity>,
        customerId: Long
    ): CreditExposureBreakdown? {
        return calculateBreakdown(
            sales.filter { it.customerId == customerId },
            knownCustomerIds = null
        )
    }

    fun calculatePortfolioPendingExposureBreakdown(
        sales: List<SaleEntity>,
        knownCustomerIds: Set<Long>
    ): CreditExposureBreakdown? {
        return calculateBreakdown(
            sales.filter { it.customerId != null },
            knownCustomerIds
        )
    }

    private fun calculateBreakdown(
        sales: List<SaleEntity>,
        knownCustomerIds: Set<Long>?
    ): CreditExposureBreakdown? {
        var pendingSync = 0.0
        var reconciliationRequired = 0.0
        var nonReferencedCustomerExposure = 0.0

        for (sale in sales) {
            if (sale.saleType != "CREDIT") continue

            if (sale.status !in pendingStatuses) continue

            if (sale.storeId !in validStoreCodes) return null

            val amountsAreValid =
                MgaAmountValidator.isValidPositive(sale.totalAmount) &&
                MgaAmountValidator.isValidNonNegative(sale.paidAmount) &&
                MgaAmountValidator.isValidNonNegative(sale.remainingAmount) &&
                sale.paidAmount <= sale.totalAmount &&
                sale.remainingAmount == sale.totalAmount - sale.paidAmount

            if (!amountsAreValid) {
                return null
            }

            when (sale.status) {
                "PENDING_SYNC" -> pendingSync += sale.remainingAmount
                "RECONCILIATION_REQUIRED" ->
                    reconciliationRequired += sale.remainingAmount
            }

            if (
                knownCustomerIds != null &&
                sale.customerId != null &&
                sale.customerId !in knownCustomerIds
            ) {
                nonReferencedCustomerExposure += sale.remainingAmount
            }

            if (
                !MgaAmountValidator.isValidNonNegative(pendingSync) ||
                !MgaAmountValidator.isValidNonNegative(reconciliationRequired) ||
                !MgaAmountValidator.isValidNonNegative(nonReferencedCustomerExposure) ||
                !MgaAmountValidator.isValidNonNegative(
                    pendingSync + reconciliationRequired
                )
            ) {
                return null
            }
        }

        return CreditExposureBreakdown(
            pendingSync = pendingSync,
            reconciliationRequired = reconciliationRequired,
            nonReferencedCustomerExposure = nonReferencedCustomerExposure
        )
    }

    fun calculatePendingExposure(
        sales: List<SaleEntity>,
        customerId: Long
    ): Double? {
        return calculatePendingExposureBreakdown(sales, customerId)?.total
    }

    fun calculateProjectedExposure(
        officialDebt: Double,
        pendingExposure: Double,
        currentSaleRemaining: Double
    ): Double? {
        if (
            !MgaAmountValidator.isValidNonNegative(officialDebt) ||
            !MgaAmountValidator.isValidNonNegative(pendingExposure) ||
            !MgaAmountValidator.isValidNonNegative(currentSaleRemaining)
        ) {
            return null
        }

        val projected = officialDebt + pendingExposure + currentSaleRemaining
        return projected.takeIf(MgaAmountValidator::isValidNonNegative)
    }
}
