package com.example.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CartQuantityRulesTest {
    @Test fun acceptsOneAndMultipleUnitsWithinAvailableStock() {
        assertTrue(CartQuantityRules.isAllowed(1, 1))
        assertTrue(CartQuantityRules.isAllowed(7, 12))
        assertTrue(CartQuantityRules.isAllowed(12, 12))
    }

    @Test fun rejectsZeroNegativeAndQuantityAboveStock() {
        assertFalse(CartQuantityRules.isAllowed(0, 12))
        assertFalse(CartQuantityRules.isAllowed(-1, 12))
        assertFalse(CartQuantityRules.isAllowed(13, 12))
        assertFalse(CartQuantityRules.isAllowed(1, 0))
        assertFalse(CartQuantityRules.isAllowed(1, -1))
    }
}
