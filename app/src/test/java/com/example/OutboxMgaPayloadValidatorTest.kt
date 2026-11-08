package com.example

import com.example.domain.validation.OutboxMgaPayloadValidator
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxMgaPayloadValidatorTest {

    @Test
    fun `accepts valid whole MGA payloads for supported financial entities`() {
        assertNull(
            OutboxMgaPayloadValidator.validate(
                "SALE",
                """{"totalAmount":100,"paidAmount":25,"remainingAmount":75,"items":[{"price":100}]}"""
            )
        )
        assertNull(
            OutboxMgaPayloadValidator.validate(
                "DEBT_PAYMENT",
                """{"amountPaid":25,"newDebt":75}"""
            )
        )
        assertNull(
            OutboxMgaPayloadValidator.validate(
                "CUSTOMER",
                """{"creditLimit":0}"""
            )
        )
        assertNull(
            OutboxMgaPayloadValidator.validate(
                "PRODUCT",
                """{"sellingPrice":125000}"""
            )
        )
        assertNull(OutboxMgaPayloadValidator.validate("TRANSFER", """{}"""))
        assertNotNull(OutboxMgaPayloadValidator.validate("STOCK_ADJUSTMENT", """{}"""))
    }

    @Test
    fun `rejects decimal negative and unsafe MGA amounts in financial fields`() {
        val invalidValues = listOf("12.5", "-1", "9007199254740992")

        for (value in invalidValues) {
            assertNotNull(
                "PRODUCT sellingPrice must reject $value",
                OutboxMgaPayloadValidator.validate(
                    "PRODUCT",
                    """{"sellingPrice":$value}"""
                )
            )
            assertNotNull(
                "CUSTOMER creditLimit must reject $value",
                OutboxMgaPayloadValidator.validate(
                    "CUSTOMER",
                    """{"creditLimit":$value}"""
                )
            )
            assertNotNull(
                "DEBT_PAYMENT amountPaid must reject $value",
                OutboxMgaPayloadValidator.validate(
                    "DEBT_PAYMENT",
                    """{"amountPaid":$value,"newDebt":0}"""
                )
            )
        }
    }

    @Test
    fun `rejects non finite values and malformed JSON`() {
        for (value in listOf("NaN", "Infinity", "-Infinity")) {
            assertNotNull(
                "PRODUCT sellingPrice must reject $value",
                OutboxMgaPayloadValidator.validate(
                    "PRODUCT",
                    """{"sellingPrice":$value}"""
                )
            )
        }

        assertNotNull(
            OutboxMgaPayloadValidator.validate("PRODUCT", """{"sellingPrice":""")
        )
    }

    @Test
    fun `rejects fractional MGA values even when Double precision could hide the fraction`() {
        val precisionLossValues = listOf(
            "1.00000000000000001",
            "9007199254740990.9"
        )

        for (value in precisionLossValues) {
            assertNotNull(
                "PRODUCT sellingPrice must reject exact fractional token $value",
                OutboxMgaPayloadValidator.validate(
                    "PRODUCT",
                    """{"sellingPrice":$value}"""
                )
            )
            assertNotNull(
                "DEBT_PAYMENT amountPaid must reject exact fractional token $value",
                OutboxMgaPayloadValidator.validate(
                    "DEBT_PAYMENT",
                    """{"amountPaid":$value,"newDebt":0}"""
                )
            )
        }
    }

    @Test
    fun `validates exact decimal representation across every MGA payload field`() {
        val invalidCases = listOf(
            "PRODUCT" to """{"sellingPrice":1.00000000000000001}""",
            "CUSTOMER" to """{"creditLimit":1.00000000000000001}""",
            "DEBT_PAYMENT" to """{"amountPaid":0,"newDebt":1.00000000000000001}""",
            "SALE" to """{"totalAmount":1.00000000000000001,"paidAmount":0,"remainingAmount":0,"items":[{"price":1}]}""",
            "SALE" to """{"totalAmount":1,"paidAmount":0,"remainingAmount":0,"items":[{"price":1.00000000000000001}]}"""
        )

        for ((entityType, payload) in invalidCases) {
            assertNotNull(
                "Must reject hidden fractional MGA in $entityType: $payload",
                OutboxMgaPayloadValidator.validate(entityType, payload)
            )
        }

        assertNull(
            OutboxMgaPayloadValidator.validate(
                "PRODUCT",
                """{"sellingPrice":100.0}"""
            )
        )
    }

    @Test
    fun `rejects missing null and string financial values`() {
        val invalidPayloads = listOf(
            """{}""",
            """{"sellingPrice":null}""",
            """{"sellingPrice":"100"}"""
        )

        for (payload in invalidPayloads) {
            assertNotNull(
                "Invalid PRODUCT payload must be rejected: $payload",
                OutboxMgaPayloadValidator.validate("PRODUCT", payload)
            )
        }
    }

    @Test
    fun `validates every SALE total payment balance and item price`() {
        val invalidPayloads = listOf(
            """{"paidAmount":25,"remainingAmount":75,"items":[{"price":100}]}""",
            """{"totalAmount":100,"remainingAmount":75,"items":[{"price":100}]}""",
            """{"totalAmount":100,"paidAmount":25,"items":[{"price":100}]}""",
            """{"totalAmount":100,"paidAmount":25,"remainingAmount":75,"items":[{"price":12.5}]}""",
            """{"totalAmount":100,"paidAmount":25,"remainingAmount":75,"items":[{"price":-1}]}""",
            """{"totalAmount":100,"paidAmount":25,"remainingAmount":75}""",
            """{"totalAmount":100,"paidAmount":25,"remainingAmount":75,"items":[null]}"""
        )

        for (payload in invalidPayloads) {
            assertNotNull(
                "Invalid SALE payload must be rejected: $payload",
                OutboxMgaPayloadValidator.validate("SALE", payload)
            )
        }
    }

    @Test
    fun `rejects unknown entity types`() {
        val error = OutboxMgaPayloadValidator.validate(
            "UNKNOWN_ENTITY",
            """{"amount":100}"""
        )

        assertNotNull(error)
        assertTrue(error!!.contains("UNKNOWN_ENTITY"))
    }


    @Test
    fun `rejects invalid newDebt and accepts maximum safe MGA integer`() {
        for (value in listOf("12.5", "-1", "9007199254740992")) {
            assertNotNull(
                "DEBT_PAYMENT newDebt must reject $value",
                OutboxMgaPayloadValidator.validate(
                    "DEBT_PAYMENT",
                    """{"amountPaid":0,"newDebt":$value}"""
                )
            )
        }

        assertNull(
            OutboxMgaPayloadValidator.validate(
                "PRODUCT",
                """{"sellingPrice":9007199254740991}"""
            )
        )
    }

    @Test
    fun `rejects invalid whole MGA values in every SALE financial field`() {
        val fields = listOf("totalAmount", "paidAmount", "remainingAmount")
        val invalidValues = listOf("12.5", "-1", "9007199254740992")

        for (field in fields) {
            for (value in invalidValues) {
                val payload = """{"totalAmount":${if (field == "totalAmount") value else "100"},"paidAmount":${if (field == "paidAmount") value else "25"},"remainingAmount":${if (field == "remainingAmount") value else "75"},"items":[{"price":100}]}"""
                assertNotNull(
                    "SALE $field must reject $value",
                    OutboxMgaPayloadValidator.validate("SALE", payload)
                )
            }
        }
    }

}
