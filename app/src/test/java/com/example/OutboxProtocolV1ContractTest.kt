package com.example

import com.example.data.local.entity.PendingSyncOperationEntity
import com.example.data.repository.OutboxProtocolV1Converter
import com.example.data.repository.OutboxProtocolV1ConversionException
import com.example.data.repository.OutboxProtocolV1Resolver
import com.example.data.repository.ProtocolV1MutationResult
import com.example.data.repository.ProtocolV1ResultParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OutboxProtocolV1ContractTest {
    private val resolver = object : OutboxProtocolV1Resolver {
        override suspend fun serverId(entityType: String, localId: String): String? = when ("$entityType/$localId") {
            "CUSTOMER/7" -> "11111111-1111-4111-8111-111111111111"
            "PRODUCT/9" -> "22222222-2222-4222-8222-222222222222"
            else -> null
        }
        override suspend fun serverVersion(entityType: String, localId: String): Long = 0L
        override suspend fun productServerIdByCode(code: String): String? =
            if (code == "P-9") "22222222-2222-4222-8222-222222222222" else null
        override suspend fun productPurchasePriceMga(localProductId: String): Long? =
            if (localProductId == "9") 800L else null
    }

    private fun operation(type: String, json: String, id: String = "mutation-$type") =
        PendingSyncOperationEntity(
            mutationId = id, entityType = type, action = "CREATE",
            payloadJson = json, idempotencyKey = "legacy-$type"
        )

    @Test
    fun `customer and product conversion emit server UUIDs and whole MGA`() = kotlinx.coroutines.runBlocking {
        val converter = OutboxProtocolV1Converter(resolver)
        val customer = converter.convert(operation(
            "CUSTOMER", """{"customerId":7,"name":"Client","phone":"034","address":"Tana","creditLimit":50000}"""
        ))
        assertEquals("CUSTOMER", customer.entityType)
        assertEquals("11111111-1111-4111-8111-111111111111", customer.entityId)
        assertEquals(50000L, customer.payload.getLong("creditLimitMga"))

        val product = converter.convert(operation(
            "PRODUCT", """{"productId":9,"code":"P-9","name":"Produit","category":"Test","sellingPrice":1200}"""
        ))
        assertEquals(800L, product.payload.getLong("purchasePriceMga"))
        assertEquals(1200L, product.payload.getLong("sellingPriceMga"))
    }

    @Test
    fun `sale conversion resolves each product and customer and verifies arithmetic`() = kotlinx.coroutines.runBlocking {
        val converter = OutboxProtocolV1Converter(resolver)
        val sale = converter.convert(operation(
            "SALE",
            """{"saleId":"local-sale-1","saleNumber":"VNT-1","storeId":"A1","saleType":"CREDIT","totalAmount":2400,"paidAmount":400,"remainingAmount":2000,"paymentMethod":"ESPECES","customerId":7,"items":[{"code":"P-9","qty":2,"price":1200}]}"""
        ))
        assertEquals("SALE", sale.entityType)
        assertEquals("A1", sale.storeId)
        assertEquals(2400L, sale.payload.getLong("totalMga"))
        assertEquals("11111111-1111-4111-8111-111111111111", sale.payload.getString("customerId"))
        assertEquals("22222222-2222-4222-8222-222222222222",
            sale.payload.getJSONArray("lines").getJSONObject(0).getString("productId"))
    }

    @Test
    fun `transfer and debt payment use mapped references and ignore client debt total`() = kotlinx.coroutines.runBlocking {
        val converter = OutboxProtocolV1Converter(resolver)
        val transfer = converter.convert(operation(
            "TRANSFER",
            """{"transferId":"transfer-local-1","productId":9,"fromStoreId":"A1","toStoreId":"A2","quantity":3,"reason":"Réassort"}"""
        ))
        assertEquals("TRANSFER", transfer.entityType)
        assertEquals("22222222-2222-4222-8222-222222222222", transfer.payload.getString("productId"))

        val payment = converter.convert(operation(
            "DEBT_PAYMENT",
            """{"paymentId":"payment-local-1","customerId":7,"amountPaid":1500,"newDebt":0,"paymentMethod":"MVOLA","receiptNumber":"REC-1"}"""
        ))
        assertEquals("DEBT_PAYMENT", payment.entityType)
        assertEquals(1500L, payment.payload.getLong("amountMga"))
        assertEquals(false, payment.payload.has("newDebt"))
    }

    @Test
    fun `fractional MGA and unresolved references fail closed`() = kotlinx.coroutines.runBlocking {
        val converter = OutboxProtocolV1Converter(resolver)
        assertThrows(OutboxProtocolV1ConversionException::class.java) {
            kotlinx.coroutines.runBlocking {
                converter.convert(operation(
                    "CUSTOMER", """{"customerId":7,"name":"Client","creditLimit":500.5}"""
                ))
            }
        }
        assertThrows(OutboxProtocolV1ConversionException::class.java) {
            kotlinx.coroutines.runBlocking {
                converter.convert(operation(
                    "TRANSFER",
                    """{"transferId":"transfer-local-2","productId":99,"fromStoreId":"A1","toStoreId":"A2","quantity":1}"""
                ))
            }
        }
    }

    @Test
    fun `server APPLIED REJECTED and CONFLICT are parsed distinctly`() {
        val applied = ProtocolV1ResultParser.parse(JSONObject(
            """{"idempotencyKey":"k1","status":"APPLIED","entityId":"33333333-3333-4333-8333-333333333333","version":2,"serverCursor":19}"""
        ))
        assertEquals(19L, (applied as ProtocolV1MutationResult.Applied).serverCursor)

        val rejected = ProtocolV1ResultParser.parse(JSONObject(
            """{"idempotencyKey":"k2","status":"REJECTED","error":"PERMISSION_DENIED"}"""
        ))
        assertEquals("PERMISSION_DENIED", (rejected as ProtocolV1MutationResult.Rejected).error)

        val conflict = ProtocolV1ResultParser.parse(JSONObject(
            """{"idempotencyKey":"k3","status":"CONFLICT","error":"VERSION_CONFLICT","serverVersion":8}"""
        ))
        assertEquals(8L, (conflict as ProtocolV1MutationResult.Conflict).serverVersion)
    }
}
