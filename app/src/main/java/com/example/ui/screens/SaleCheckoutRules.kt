package com.example.ui.screens

import com.example.domain.model.SaleType

/** Pure checkout preflight rules; Repository remains the authoritative validation boundary. */
internal object SaleCheckoutRules {
    fun validate(
        saleType: SaleType,
        totalAmount: Double,
        paidAmount: Double?,
        customerSelected: Boolean
    ): String? {
        if (!totalAmount.isFinite() || totalAmount <= 0.0) {
            return "Le montant total de la vente doit être supérieur à zéro."
        }
        if (saleType == SaleType.CREDIT) {
            if (!customerSelected) return "Sélectionnez un client pour une vente à crédit."
            if (paidAmount == null || !paidAmount.isFinite()) {
                return "Saisissez un acompte valide."
            }
            if (paidAmount < 0.0) return "L'acompte ne peut pas être négatif."
            if (paidAmount > totalAmount) return "L'acompte ne peut pas dépasser le total de la vente."
        }
        return null
    }
}
