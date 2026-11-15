package com.example.ui.screens

import com.example.domain.model.SaleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaleCheckoutRulesTest {
    @Test fun rejectsZeroOrInvalidTotal() {
        assertEquals(
            "Le montant total de la vente doit être supérieur à zéro.",
            SaleCheckoutRules.validate(SaleType.CASH, 0.0, 0.0, false)
        )
        assertEquals(
            "Le montant total de la vente doit être supérieur à zéro.",
            SaleCheckoutRules.validate(SaleType.CASH, Double.NaN, 0.0, false)
        )
    }

    @Test fun creditSaleRequiresExistingCustomer() {
        assertEquals(
            "Sélectionnez un client pour une vente à crédit.",
            SaleCheckoutRules.validate(SaleType.CREDIT, 100.0, 0.0, false)
        )
    }

    @Test fun creditSaleRejectsInvalidOrOutOfRangeDeposit() {
        assertEquals("Saisissez un acompte valide.", SaleCheckoutRules.validate(SaleType.CREDIT, 100.0, null, true))
        assertEquals("L'acompte ne peut pas être négatif.", SaleCheckoutRules.validate(SaleType.CREDIT, 100.0, -1.0, true))
        assertEquals("L'acompte ne peut pas dépasser le total de la vente.", SaleCheckoutRules.validate(SaleType.CREDIT, 100.0, 101.0, true))
    }

    @Test fun acceptsCashAndValidCreditCheckout() {
        assertNull(SaleCheckoutRules.validate(SaleType.CASH, 100.0, null, false))
        assertNull(SaleCheckoutRules.validate(SaleType.CREDIT, 100.0, 40.0, true))
        assertNull(SaleCheckoutRules.validate(SaleType.CREDIT, 100.0, 0.0, true))
    }
}
