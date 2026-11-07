package com.example

import com.example.domain.validation.MgaAmountValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MgaAmountValidatorTest {

    @Test
    fun `accepts valid whole MGA amounts`() {
        assertTrue(MgaAmountValidator.isValidNonNegative(0.0))
        assertTrue(MgaAmountValidator.isValidNonNegative(1.0))
        assertTrue(MgaAmountValidator.isValidNonNegative(125_000.0))
        assertTrue(
            MgaAmountValidator.isValidNonNegative(
                MgaAmountValidator.MAX_SAFE_INTEGER.toDouble()
            )
        )
    }

    @Test
    fun `rejects decimal negative non finite and unsafe amounts`() {
        assertFalse(MgaAmountValidator.isValidNonNegative(12.5))
        assertFalse(MgaAmountValidator.isValidNonNegative(-1.0))
        assertFalse(MgaAmountValidator.isValidNonNegative(Double.NaN))
        assertFalse(MgaAmountValidator.isValidNonNegative(Double.POSITIVE_INFINITY))
        assertFalse(MgaAmountValidator.isValidNonNegative(Double.NEGATIVE_INFINITY))
        assertFalse(
            MgaAmountValidator.isValidNonNegative(
                MgaAmountValidator.MAX_SAFE_INTEGER.toDouble() + 1.0
            )
        )
    }

    @Test
    fun `positive validation rejects zero`() {
        assertFalse(MgaAmountValidator.isValidPositive(0.0))
        assertTrue(MgaAmountValidator.isValidPositive(1.0))
        assertFalse(MgaAmountValidator.isValidPositive(1.5))
    }

    @Test
    fun `parses only digit strings within safe integer range`() {
        assertEquals(0.0, MgaAmountValidator.parseNonNegativeInteger("0")!!, 0.0)
        assertEquals(125000.0, MgaAmountValidator.parseNonNegativeInteger("125000")!!, 0.0)
        assertEquals(
            MgaAmountValidator.MAX_SAFE_INTEGER.toDouble(),
            MgaAmountValidator.parseNonNegativeInteger(
                MgaAmountValidator.MAX_SAFE_INTEGER.toString()
            )!!,
            0.0
        )
    }

    @Test
    fun `rejects blank decimal signed malformed and unsafe input`() {
        listOf(
            "",
            " ",
            "12.5",
            "12,5",
            "-1",
            "+1",
            "1e3",
            "1 000",
            "abc",
            "9007199254740992"
        ).forEach { input ->
            assertNull("Should reject input: '$input'",
                MgaAmountValidator.parseNonNegativeInteger(input))
        }
    }
}
