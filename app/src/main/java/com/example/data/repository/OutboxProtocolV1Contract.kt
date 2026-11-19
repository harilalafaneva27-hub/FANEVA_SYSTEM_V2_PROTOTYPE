package com.example.data.repository

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID

/**
 * Converts the persisted Room outbox shape into the authoritative protocol-v1
 * mutation shape. This is a boundary contract only: SyncRepository must not
 * transmit these mutations until the Worker advertises support for the type.
 */
data class ProtocolV1OutboxMutation(
    val mutationId: String,
    val idempotencyKey: String,
    val entityType: String,
    val action: String,
    val entityId: String,
    val expectedVersion: Long,
    val storeId: String?,
    val payload: JSONObject
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mutationId", mutationId)
        .put("idempotencyKey", idempotencyKey)
        .put("entityType", entityType)
        .put("action", action)
        .put("entityId", entityId)
        .put("expectedVersion", expectedVersion)
        .put("storeId", storeId ?: JSONObject.NULL)
        .put("payload", payload)
}

interface OutboxProtocolV1Resolver {
    suspend fun serverId(entityType: String, localId: String): String?
    suspend fun serverVersion(entityType: String, localId: String): Long
    suspend fun productServerIdByCode(code: String): String?
    suspend fun productPurchasePriceMga(localProductId: String): Long?
}

class OutboxProtocolV1ConversionException(message: String) : IllegalArgumentException(message)

class OutboxProtocolV1Converter(
    private val resolver: OutboxProtocolV1Resolver
) {
    suspend fun convert(operation: com.example.data.local.entity.PendingSyncOperationEntity): ProtocolV1OutboxMutation {
        val source = try { JSONObject(operation.payloadJson) } catch (_: Exception) {
            throw OutboxProtocolV1ConversionException("Outbox JSON tsy manan-kery: ${operation.mutationId}")
        }
        val stableKey = RoomSyncStateStore.stableIdempotencyKey(operation.mutationId)
        return when (operation.entityType) {
            "CUSTOMER" -> convertCustomer(operation, source, stableKey)
            "PRODUCT" -> convertProduct(operation, source, stableKey)
            "SALE" -> convertSale(operation, source, stableKey)
            "TRANSFER" -> convertTransfer(operation, source, stableKey)
            "DEBT_PAYMENT" -> convertDebtPayment(operation, source, stableKey)
            else -> throw OutboxProtocolV1ConversionException(
                "Entity type tsy mbola ao amin'ny protocol-v1 contract: ${operation.entityType}"
            )
        }
    }

    private suspend fun convertCustomer(
        op: com.example.data.local.entity.PendingSyncOperationEntity,
        p: JSONObject,
        key: String
    ): ProtocolV1OutboxMutation {
        requireCreate(op)
        val localId = requiredLong(p, "customerId").toString()
        val id = resolver.serverId("CUSTOMER", localId)
            ?: RoomSyncStateStore.stableServerId("CUSTOMER", localId)
        val payload = JSONObject()
            .put("displayName", requiredText(p, "name", 160))
            .put("phone", optionalText(p, "phone"))
            .put("address", optionalText(p, "address"))
            .put("creditLimitMga", requiredMga(p, "creditLimit"))
        return mutation(op, key, "CUSTOMER", id, resolver.serverVersion("CUSTOMER", localId), null, payload)
    }

    private suspend fun convertProduct(
        op: com.example.data.local.entity.PendingSyncOperationEntity,
        p: JSONObject,
        key: String
    ): ProtocolV1OutboxMutation {
        requireCreate(op)
        val localId = requiredLong(p, "productId").toString()
        val id = resolver.serverId("PRODUCT", localId)
            ?: RoomSyncStateStore.stableServerId("PRODUCT", localId)
        val purchase = resolver.productPurchasePriceMga(localId)
            ?: throw OutboxProtocolV1ConversionException("Tsy hita ny prix d'achat MGA integer ho an'ny product $localId.")
        val payload = JSONObject()
            .put("productCode", requiredText(p, "code", 64))
            .put("name", requiredText(p, "name", 160))
            .put("category", optionalText(p, "category"))
            .put("purchasePriceMga", requireMgaLong(purchase, "purchasePriceMga"))
            .put("sellingPriceMga", requiredMga(p, "sellingPrice"))
            .put("isActive", true)
        return mutation(op, key, "PRODUCT", id, resolver.serverVersion("PRODUCT", localId), null, payload)
    }

    private suspend fun convertSale(
        op: com.example.data.local.entity.PendingSyncOperationEntity,
        p: JSONObject,
        key: String
    ): ProtocolV1OutboxMutation {
        requireCreate(op)
        val saleId = requiredText(p, "saleId", 64)
        val entityId = RoomSyncStateStore.stableServerId("SALE", saleId)
        val store = requiredStore(p, "storeId")
        val saleType = requiredEnum(p, "saleType", setOf("CASH", "CREDIT"))
        val lines = p.optJSONArray("items") ?: fail("SALE items tsy hita.")
        if (lines.length() !in 1..200) fail("SALE items tsy mety.")
        val convertedLines = JSONArray()
        var calculatedTotal = 0L
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: fail("SALE line $i tsy object.")
            val code = requiredText(line, "code", 64)
            val productId = resolver.productServerIdByCode(code)
                ?: fail("Mapping server PRODUCT tsy hita ho an'ny code $code.")
            val quantity = requiredPositiveInt(line, "qty")
            val unitPrice = requiredMga(line, "price")
            val lineTotal = try { Math.multiplyExact(unitPrice, quantity.toLong()) }
                catch (_: ArithmeticException) { fail("SALE line total mihoatra ny fetra MGA.") }
            calculatedTotal = try { Math.addExact(calculatedTotal, lineTotal) }
                catch (_: ArithmeticException) { fail("SALE total mihoatra ny fetra MGA.") }
            convertedLines.put(JSONObject()
                .put("productId", productId)
                .put("unitPriceMga", unitPrice)
                .put("quantity", quantity)
                .put("lineTotalMga", lineTotal))
        }
        val total = requiredMga(p, "totalAmount")
        val paid = requiredMga(p, "paidAmount")
        val remaining = requiredMga(p, "remainingAmount")
        if (calculatedTotal != total || paid > total || remaining != total - paid) {
            fail("SALE total/paid/remaining tsy mifanaraka amin'ny lines.")
        }
        val customerId = if (p.isNull("customerId")) null else {
            val localCustomer = requiredLong(p, "customerId").toString()
            resolver.serverId("CUSTOMER", localCustomer)
                ?: fail("Mapping server CUSTOMER tsy hita: $localCustomer")
        }
        if (saleType == "CREDIT" && customerId == null) fail("SALE CREDIT mila customer mapping.")
        if (saleType == "CASH" && (customerId != null || remaining != 0L)) fail("SALE CASH tsy mahazo debt.")
        val payload = JSONObject()
            .put("saleNumber", requiredText(p, "saleNumber", 80))
            .put("storeId", store)
            .put("customerId", customerId ?: JSONObject.NULL)
            .put("saleType", saleType)
            .put("totalMga", total)
            .put("paidMga", paid)
            .put("remainingMga", remaining)
            .put("paymentMethod", normalizePaymentMethod(requiredText(p, "paymentMethod", 32)))
            .put("status", "COMPLETED")
            .put("lines", convertedLines)
        return mutation(op, key, "SALE", entityId, 0L, store, payload)
    }

    private suspend fun convertTransfer(
        op: com.example.data.local.entity.PendingSyncOperationEntity,
        p: JSONObject,
        key: String
    ): ProtocolV1OutboxMutation {
        requireCreate(op)
        val localId = requiredLong(p, "productId").toString()
        val productId = resolver.serverId("PRODUCT", localId)
            ?: fail("Mapping server PRODUCT tsy hita: $localId")
        val from = requiredStore(p, "fromStoreId")
        val to = requiredStore(p, "toStoreId")
        if (from == to) fail("TRANSFER source sy destination tsy maintsy samy hafa.")
        val quantity = requiredPositiveInt(p, "quantity")
        val transferLocalId = requiredText(p, "transferId", 80)
        val transferId = RoomSyncStateStore.stableServerId("TRANSFER", transferLocalId)
        val payload = JSONObject()
            .put("productId", productId)
            .put("fromStoreId", from)
            .put("toStoreId", to)
            .put("quantity", quantity)
            .put("reason", optionalText(p, "reason"))
        return mutation(op, key, "TRANSFER", transferId, 0L, from, payload)
    }

    private suspend fun convertDebtPayment(
        op: com.example.data.local.entity.PendingSyncOperationEntity,
        p: JSONObject,
        key: String
    ): ProtocolV1OutboxMutation {
        requireCreate(op)
        val localCustomer = requiredLong(p, "customerId").toString()
        val customerId = resolver.serverId("CUSTOMER", localCustomer)
            ?: fail("Mapping server CUSTOMER tsy hita: $localCustomer")
        val paymentLocalId = requiredText(p, "paymentId", 80)
        val paymentId = RoomSyncStateStore.stableServerId("DEBT_PAYMENT", paymentLocalId)
        val store = requiredStore(p, "storeId")
        val payload = JSONObject()
            .put("storeId", store)
            .put("customerId", customerId)
            .put("amountMga", requiredMga(p, "amountPaid", allowZero = false))
            .put("paymentMethod", normalizePaymentMethod(requiredText(p, "paymentMethod", 32)))
            .put("note", optionalText(p, "receiptNumber"))
        // newDebt is deliberately ignored: the server ledger is authoritative.
        return mutation(op, key, "DEBT_PAYMENT", paymentId, 0L, store, payload)
    }

    private fun mutation(
        op: com.example.data.local.entity.PendingSyncOperationEntity,
        key: String,
        type: String,
        id: String,
        version: Long,
        store: String?,
        payload: JSONObject
    ) = ProtocolV1OutboxMutation(op.mutationId, key, type, "CREATE", id, version, store, payload)

    private fun requireCreate(op: com.example.data.local.entity.PendingSyncOperationEntity) {
        if (op.action != "CREATE") fail("Outbox action tsy mbola tohanan'ity contract ity: ${op.action}")
    }

    private fun requiredMga(p: JSONObject, field: String, allowZero: Boolean = true): Long {
        val raw = p.opt(field)
        val amount = try {
            when (raw) {
                is Number -> BigDecimal(raw.toString()).longValueExact()
                else -> fail("$field tsy MGA number integer.")
            }
        } catch (_: ArithmeticException) { fail("$field tsy MGA integer azo antoka.") }
        if (amount < (if (allowZero) 0L else 1L) || amount > 9_007_199_254_740_991L) {
            fail("$field ivelan'ny fetra MGA.")
        }
        return amount
    }

    private fun requireMgaLong(value: Long, field: String): Long {
        if (value < 0L || value > 9_007_199_254_740_991L) fail("$field ivelan'ny fetra MGA.")
        return value
    }

    private fun requiredLong(p: JSONObject, field: String): Long {
        val raw = p.opt(field)
        if (raw !is Number) fail("$field tsy integer.")
        return try { BigDecimal(raw.toString()).longValueExact().also { if (it < 1L) fail("$field tsy mety.") } }
        catch (_: ArithmeticException) { fail("$field tsy integer azo antoka.") }
    }

    private fun requiredPositiveInt(p: JSONObject, field: String): Int {
        val value = requiredLong(p, field)
        if (value > Int.MAX_VALUE) fail("$field mihoatra ny fetra.")
        return value.toInt()
    }

    private fun requiredText(p: JSONObject, field: String, max: Int): String {
        val value = p.optString(field, "").trim()
        if (value.isEmpty() || value.length > max) fail("$field tsy feno na lava loatra.")
        return value
    }

    private fun optionalText(p: JSONObject, field: String): String {
        if (p.isNull(field)) return ""
        val value = p.optString(field, "")
        if (value.length > 500) fail("$field lava loatra.")
        return value
    }

    private fun requiredStore(p: JSONObject, field: String): String {
        val store = requiredText(p, field, 2)
        if (store !in setOf("A1", "A2", "A3", "A4", "A5")) fail("$field tsy magasin A1-A5.")
        return store
    }

    private fun requiredEnum(p: JSONObject, field: String, allowed: Set<String>): String {
        val value = requiredText(p, field, 32)
        if (value !in allowed) fail("$field tsy ekena.")
        return value
    }

    private fun normalizePaymentMethod(value: String): String = when (value.uppercase()) {
        "ESPECES", "CASH" -> "CASH"
        "MVOLA" -> "MVOLA"
        "ORANGE_MONEY", "ORANGE" -> "ORANGE_MONEY"
        "AIRTEL_MONEY", "AIRTEL" -> "AIRTEL_MONEY"
        "BANK", "VIREMENT" -> "BANK"
        else -> "OTHER"
    }

    private fun fail(message: String): Nothing = throw OutboxProtocolV1ConversionException(message)
}

sealed class ProtocolV1MutationResult {
    data class Applied(val idempotencyKey: String, val entityId: String, val version: Long, val serverCursor: Long?) : ProtocolV1MutationResult()
    data class Rejected(val idempotencyKey: String?, val error: String) : ProtocolV1MutationResult()
    data class Conflict(val idempotencyKey: String, val error: String, val serverVersion: Long?) : ProtocolV1MutationResult()
}

object ProtocolV1ResultParser {
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

    fun parse(value: JSONObject): ProtocolV1MutationResult {
        val status = value.optString("status", "")
        val key = value.optString("idempotencyKey", "").takeIf { it.isNotBlank() }
        return when (status) {
            "APPLIED" -> {
                val entityId = value.optString("entityId", "")
                val version = value.optLong("version", -1L)
                if (key == null || !uuid.matches(entityId) || version < 1L) {
                    throw OutboxProtocolV1ConversionException("APPLIED response tsy feno na tsy manan-kery.")
                }
                val cursor = if (value.has("serverCursor") && !value.isNull("serverCursor")) value.optLong("serverCursor", -1L) else null
                if (cursor != null && cursor < 0L) throw OutboxProtocolV1ConversionException("serverCursor tsy mety.")
                ProtocolV1MutationResult.Applied(key, entityId, version, cursor)
            }
            "REJECTED" -> ProtocolV1MutationResult.Rejected(key, value.optString("error", "MUTATION_REJECTED"))
            "CONFLICT" -> ProtocolV1MutationResult.Conflict(
                key ?: throw OutboxProtocolV1ConversionException("CONFLICT tsy manana idempotencyKey."),
                value.optString("error", "VERSION_CONFLICT"),
                if (value.has("serverVersion") && !value.isNull("serverVersion")) value.optLong("serverVersion", -1L).takeIf { it >= 0L } else null
            )
            else -> throw OutboxProtocolV1ConversionException("Status server tsy fantatra: $status")
        }
    }
}

/** Room-backed resolver. References are fail-closed: only server-confirmed mappings are returned. */
class RoomOutboxProtocolV1Resolver(
    private val database: com.example.data.local.FanevaDatabase
) : OutboxProtocolV1Resolver {
    override suspend fun serverId(entityType: String, localId: String): String? =
        database.serverIdMappingDao().getMapping(entityType, localId)?.serverId

    override suspend fun serverVersion(entityType: String, localId: String): Long =
        database.serverIdMappingDao().getMapping(entityType, localId)?.serverVersion ?: 0L

    override suspend fun productServerIdByCode(code: String): String? {
        val product = database.productDao().getProductByCode(code) ?: return null
        return serverId("PRODUCT", product.id.toString())
    }

    override suspend fun productPurchasePriceMga(localProductId: String): Long? {
        val localId = localProductId.toLongOrNull() ?: return null
        val product = database.productDao().getProductById(localId) ?: return null
        return try {
            BigDecimal(product.purchasePrice.toString()).longValueExact()
                .takeIf { it >= 0L && it <= 9_007_199_254_740_991L }
        } catch (_: ArithmeticException) {
            null
        }
    }
}
