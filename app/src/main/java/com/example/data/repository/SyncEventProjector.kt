package com.example.data.repository

import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.*
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Projects committed protocol-v1 events into the local business tables.
 * Caller must invoke this inside the same Room transaction that stores the
 * inbox rows and advances the server cursor.
 */
internal class SyncEventProjector(private val db: FanevaDatabase) {
    private val mappings get() = db.serverIdMappingDao()

    suspend fun project(events: List<SyncEventInboxEntity>) {
        for (event in events.sortedBy { it.cursor }) {
            require(event.storeId in setOf("A1", "A2", "A3", "A4", "A5")) { "Event store scope is invalid." }
            require(event.eventType == "UPSERT") { "Unsupported event operation: ${event.eventType}" }
            val payload = JSONObject(event.payloadJson)
            val mutationId = payload.optString("mutationId").takeIf { it.isNotBlank() }
            if (mutationId != null) {
                val localMutation = db.pendingSyncDao().getByIdempotencyKey(mutationId)
                if (localMutation?.status == "SYNCED") continue
            }
            when (event.entityType) {
                "PRODUCT" -> projectProduct(event, payload)
                "CUSTOMER" -> projectCustomer(event, payload)
                "STOCK" -> projectStock(event, payload)
                "SALE" -> projectSale(event, payload, mutationId)
                "TRANSFER" -> projectTransfer(event, payload, mutationId)
                "DEBT_PAYMENT" -> projectDebtPayment(event, payload, mutationId)
                else -> error("Unsupported protocol-v1 event type: ${event.entityType}")
            }
        }
    }

    private suspend fun projectProduct(event: SyncEventInboxEntity, envelope: JSONObject) {
        val p = envelope.optJSONObject("payload") ?: envelope
        val code = p.getString("productCode")
        require(code.isNotBlank() && code.length <= 64)
        val mapped = mappings.getByServerId("PRODUCT", event.entityId)
        val existing = mapped?.let { db.productDao().getProductById(it.localId.toLongOrNull() ?: error("Invalid product mapping")) }
            ?: db.productDao().getProductByCode(code)
        val selling = mga(p, "sellingPriceMga")
        val purchase = if (p.has("purchasePriceMga")) mga(p, "purchasePriceMga").toDouble()
            else existing?.purchasePrice ?: 0.0
        val local = (existing ?: ProductEntity(
            code = code, name = p.getString("name"), category = p.optString("category"),
            purchasePrice = purchase, sellingPrice = selling.toDouble()
        )).copy(
            code = code, name = p.getString("name"), category = p.optString("category"),
            purchasePrice = purchase, sellingPrice = selling.toDouble(),
            isActive = p.optBoolean("isActive", true)
        )
        val localId = if (existing == null) db.productDao().insertProduct(local) else {
            db.productDao().updateProduct(local)
            existing.id
        }
        saveMapping("PRODUCT", localId.toString(), event.entityId, envelope.optLong("version", 1L))
    }

    private suspend fun projectCustomer(event: SyncEventInboxEntity, envelope: JSONObject) {
        val p = envelope.optJSONObject("payload") ?: envelope
        val name = p.getString("displayName")
        require(name.isNotBlank() && name.length <= 160)
        val mapped = mappings.getByServerId("CUSTOMER", event.entityId)
        val existing = mapped?.let { db.customerDao().getCustomerById(it.localId.toLongOrNull() ?: error("Invalid customer mapping")) }
        val local = (existing ?: CustomerEntity(
            name = name, phone = p.optString("phone"), address = p.optString("address"),
            creditLimit = mga(p, "creditLimitMga").toDouble()
        )).copy(
            name = name, phone = p.optString("phone"), address = p.optString("address"),
            creditLimit = mga(p, "creditLimitMga").toDouble()
        )
        val localId = if (existing == null) {
            db.customerDao().insertCustomer(local)
        } else {
            db.customerDao().updateCustomer(local)
            existing.id
        }
        saveMapping("CUSTOMER", localId.toString(), event.entityId, envelope.optLong("version", 1L))
    }

    private suspend fun projectStock(event: SyncEventInboxEntity, envelope: JSONObject) {
        val p = envelope.optJSONObject("payload") ?: envelope
        val productServerId = p.optString("productId").ifBlank { event.entityId }
        val product = localProduct(productServerId)
        val quantity = mga(p, "quantity")
        require(quantity <= Int.MAX_VALUE)
        db.storeStockDao().insertOrUpdateStock(
            StoreStockEntity(event.storeId, product.id, quantity.toInt(), event.createdAt)
        )
    }

    private suspend fun projectSale(
        event: SyncEventInboxEntity,
        p: JSONObject,
        mutationId: String?
    ) {
        val saleId = p.optString("saleId").ifBlank { event.entityId }
        require(saleId.isNotBlank()) { "SALE ID is missing." }
        val lines = p.optJSONArray("lines") ?: error("SALE event has no lines array")
        require(lines.length() in 1..500)
        val resolved = mutableListOf<SaleItemEntity>()
        for (i in 0 until lines.length()) {
            val line = lines.getJSONObject(i)
            val product = localProduct(line.getString("productId"))
            val quantity = line.getInt("quantity")
            require(quantity > 0)
            val unit = mga(line, "unitPriceMga")
            val total = mga(line, "lineTotalMga")
            require(Math.multiplyExact(unit, quantity.toLong()) == total)
            resolved += SaleItemEntity(
                id = "${event.eventId}-$i", saleId = saleId, productId = product.id,
                productCode = line.getString("productCode"), productName = line.getString("productName"),
                unitPrice = unit.toDouble(), quantity = quantity, total = total.toDouble()
            )
        }

        val total = mga(p, "totalMga")
        val paid = mga(p, "paidMga")
        val lineTotal = resolved.fold(0L) { sum, item -> Math.addExact(sum, item.total.toLong()) }
        require(lineTotal == total) { "SALE line totals do not equal the authoritative sale total." }
        require(paid <= total) { "SALE paid amount exceeds total." }
        val saleType = p.getString("saleType")
        require(saleType in setOf("CASH", "CREDIT")) { "SALE type is invalid." }
        val customerRedacted = p.optBoolean("customerRedacted", false)
        val customerServerId = p.optString("customerId").takeIf { it.isNotBlank() && it != "null" }
        require(!customerRedacted || (saleType == "CREDIT" && customerServerId == null)) {
            "SALE redaction marker is inconsistent."
        }
        val customer = if (customerServerId == null) null else {
            val customerMapping = mappings.getByServerId("CUSTOMER", customerServerId)
                ?: error("SALE references a customer without a local server-ID mapping.")
            db.customerDao().getCustomerById(
                customerMapping.localId.toLongOrNull() ?: error("Invalid customer local mapping")
            ) ?: error("SALE customer mapping points to a missing local customer.")
        }
        val remaining = if (customerRedacted) 0L else mga(p, "remainingMga")
        require((saleType == "CASH" && customer == null && !customerRedacted && remaining == 0L) ||
            (saleType == "CREDIT" && (customer != null || customerRedacted) &&
                (customerRedacted || remaining == total - paid))) {
            "SALE customer/payment type is inconsistent."
        }
        val saleNumber = p.getString("saleNumber")
        require(saleNumber.isNotBlank() && saleNumber.length <= 80) { "SALE number is invalid." }
        val paymentMethod = p.optString("paymentMethod")
        require(paymentMethod.isNotBlank() && paymentMethod.length <= 40) { "SALE payment method is invalid." }
        val actor = p.optString("actorPrincipalId")
        require(actor.isNotBlank()) { "SALE actor is missing." }

        val mappedSale = mappings.getByServerId("SALE", saleId)
        val existingById = db.saleDao().getSaleById(saleId)
        val existingByMutation = mutationId?.let { db.saleDao().getSaleByIdempotencyKey(it) }
        val priorSale = existingById ?: existingByMutation
        if (mappedSale != null || priorSale != null) {
            require(priorSale != null) { "SALE mapping exists without its local sale row." }
            require(mappedSale == null || mappedSale.localId == priorSale.id) {
                "SALE mapping points to a different local sale."
            }
            require(priorSale.id == saleId) { "SALE mutationId is already bound to a different sale ID." }
            require(mutationId == null || priorSale.idempotencyKey == mutationId) {
                "SALE ID is already bound to a different mutationId."
            }
            require(priorSale.storeId == event.storeId) { "SALE replay store attribution mismatch." }
            require(
                priorSale.saleNumber == saleNumber &&
                priorSale.saleType == saleType &&
                priorSale.totalAmount.toLong() == total &&
                priorSale.paidAmount.toLong() == paid &&
                priorSale.remainingAmount.toLong() == remaining &&
                priorSale.paymentMethod == paymentMethod &&
                priorSale.sellerId == actor &&
                priorSale.customerId == customer?.id
            ) { "SALE replay conflicts with the previously projected sale payload." }
            val existingLines = db.saleDao().getSaleItems(saleId)
            fun lineKey(item: SaleItemEntity) =
                listOf(item.productId, item.productCode, item.productName, item.quantity,
                    item.unitPrice.toLong(), item.total.toLong()).joinToString("|")
            require(existingLines.map(::lineKey).sorted() == resolved.map(::lineKey).sorted()) {
                "SALE replay lines conflict with the previously projected sale payload."
            }
            saveMapping("SALE", priorSale.id, saleId, 1L)
            return
        }

        val stockDeltas = linkedMapOf<Long, Int>()
        for (item in resolved) stockDeltas[item.productId] =
            Math.addExact(stockDeltas[item.productId] ?: 0, item.quantity)
        for ((productId, quantity) in stockDeltas) {
            val stock = db.storeStockDao().getStock(productId, event.storeId)
                ?: error("SALE stock snapshot missing for product $productId at ${event.storeId}")
            require(stock.quantity >= quantity) { "SALE projection would make stock negative." }
            db.storeStockDao().insertOrUpdateStock(stock.copy(
                quantity = stock.quantity - quantity, lastUpdated = event.createdAt
            ))
            val item = resolved.first { it.productId == productId }
            db.stockMovementDao().insertMovement(StockMovementEntity(
                id = "${event.eventId}-$productId", movementNumber = event.eventId,
                productId = productId, productName = item.productName, fromStoreId = event.storeId,
                quantity = -quantity, movementType = "VENTE", referenceId = saleId,
                reason = "Synchronisation serveur", performedBy = actor, createdAt = event.createdAt
            ))
        }
        db.saleDao().insertSale(SaleEntity(
            id = saleId, saleNumber = saleNumber, storeId = event.storeId,
            customerId = customer?.id, customerName = customer?.name,
            sellerId = actor, sellerName = actor, saleType = saleType,
            totalAmount = total.toDouble(), paidAmount = paid.toDouble(),
            remainingAmount = remaining.toDouble(), paymentMethod = paymentMethod,
            idempotencyKey = mutationId ?: "remote-${event.eventId}", createdAt = event.createdAt
        ))
        db.saleDao().insertSaleItems(resolved)
        if (!customerRedacted && customer != null && remaining > 0L) {
            db.customerDao().updateDebt(customer.id, Math.addExact(customer.currentDebt.toLong(), remaining).toDouble())
        }
        saveMapping("SALE", saleId, saleId, 1L)
    }

    private suspend fun projectTransfer(
        event: SyncEventInboxEntity,
        p: JSONObject,
        mutationId: String?
    ) {
        val transferId = p.optString("transferId").ifBlank { event.entityId }
        val product = localProduct(p.getString("productId"))
        val from = p.getString("fromStoreId")
        val to = p.getString("toStoreId")
        val quantity = p.getInt("quantity")
        require(quantity > 0 && from != to)
        val delta = when (event.storeId) {
            from -> -quantity
            to -> quantity
            else -> error("TRANSFER event store is not an endpoint.")
        }
        // A logical transfer emits one event per endpoint. eventId-only inbox
        // deduplication is insufficient when an endpoint is replayed with a
        // fresh eventId, so persist a deterministic movement key.
        val movementId = "transfer:$transferId:${event.storeId}"
        val priorMovement = db.stockMovementDao().getMovementById(movementId)
        if (priorMovement != null) {
            require(
                priorMovement.referenceId == transferId &&
                priorMovement.productId == product.id &&
                priorMovement.fromStoreId == from &&
                priorMovement.toStoreId == to &&
                priorMovement.quantity == quantity &&
                priorMovement.movementType == "TRANSFERT"
            ) { "TRANSFER replay conflicts with the previously projected endpoint." }
            return
        }
        val stock = db.storeStockDao().getStock(product.id, event.storeId)
        val next = Math.addExact(stock?.quantity ?: 0, delta)
        require(next >= 0) { "TRANSFER projection would make stock negative." }
        db.storeStockDao().insertOrUpdateStock(
            StoreStockEntity(event.storeId, product.id, next, event.createdAt)
        )
        db.stockMovementDao().insertMovement(StockMovementEntity(
            id = movementId, movementNumber = transferId, productId = product.id,
            productName = product.name, fromStoreId = from, toStoreId = to,
            quantity = quantity, movementType = "TRANSFERT", referenceId = transferId,
            reason = p.optString("reason"), performedBy = p.optString("actorPrincipalId"),
            createdAt = event.createdAt
        ))
        val key = mutationId ?: "remote-$transferId"
        if (db.transferRequestDao().getByIdempotencyKey(key) == null) {
            db.transferRequestDao().insertRequest(TransferRequestEntity(
                idempotencyKey = key, productId = product.id, productName = product.name,
                fromStoreId = from, toStoreId = to, quantity = quantity,
                operatorName = p.optString("actorPrincipalId"), reason = p.optString("reason"),
                status = "COMPLETED", createdAt = event.createdAt, completedAt = event.createdAt
            ))
        }
        saveMapping("TRANSFER", transferId, transferId, 1L)
    }

    private suspend fun projectDebtPayment(
        event: SyncEventInboxEntity,
        p: JSONObject,
        mutationId: String?
    ) {
        val paymentId = p.optString("paymentId").ifBlank { event.entityId }
        require(paymentId.isNotBlank()) { "DEBT_PAYMENT ID is missing." }
        val payloadStore = p.optString("storeId")
        require(payloadStore.isBlank() || payloadStore == event.storeId) { "DEBT_PAYMENT store attribution mismatch." }
        val customer = localCustomer(p.getString("customerId"))
        val amount = mga(p, "amountMga")
        require(amount > 0L)
        val key = mutationId ?: "remote-$paymentId"
        val mappedPayment = mappings.getByServerId("DEBT_PAYMENT", paymentId)
        if (mappedPayment != null) {
            require(mappedPayment.localId == paymentId) { "DEBT_PAYMENT mapping points to a different local payment." }
            val existing = db.debtPaymentDao().getPaymentById(paymentId)
                ?: error("DEBT_PAYMENT mapping exists without its local payment row.")
            require(existing.idempotencyKey == key) {
                "DEBT_PAYMENT ID is already bound to a different mutationId."
            }
            require(existing.customerId == customer.id && existing.amountPaid == amount.toDouble()) {
                "DEBT_PAYMENT replay conflicts with the previously projected payment."
            }
            return
        }
        val priorPayment = db.debtPaymentDao().getPaymentByIdempotencyKey(key)
        if (priorPayment != null) {
            require(priorPayment.id == paymentId) {
                "DEBT_PAYMENT mutationId is already bound to a different payment ID."
            }
            require(priorPayment.customerId == customer.id && priorPayment.amountPaid == amount.toDouble()) {
                "DEBT_PAYMENT replay conflicts with the previously projected payment."
            }
        } else {
            require(customer.currentDebt >= amount.toDouble()) { "DEBT_PAYMENT exceeds the locally known debt." }
            db.debtPaymentDao().insertPayment(DebtPaymentEntity(
                id = paymentId, customerId = customer.id, customerName = customer.name,
                amountPaid = amount.toDouble(), paymentMethod = p.optString("paymentMethod"),
                note = p.optString("note"), receiptNumber = paymentId, idempotencyKey = key,
                createdAt = event.createdAt
            ))
            db.customerDao().updateDebt(customer.id, customer.currentDebt - amount.toDouble())
        }
        saveMapping("DEBT_PAYMENT", paymentId, paymentId, 1L)
    }

    private suspend fun localProduct(serverId: String): ProductEntity {
        val mapping = mappings.getByServerId("PRODUCT", serverId)
            ?: error("Missing PRODUCT server ID mapping: $serverId")
        return db.productDao().getProductById(mapping.localId.toLongOrNull() ?: error("Invalid product local ID"))
            ?: error("Mapped local product not found: ${mapping.localId}")
    }

    private suspend fun localCustomer(serverId: String): CustomerEntity {
        val mapping = mappings.getByServerId("CUSTOMER", serverId)
            ?: error("Missing CUSTOMER server ID mapping: $serverId")
        return db.customerDao().getCustomerById(mapping.localId.toLongOrNull() ?: error("Invalid customer local ID"))
            ?: error("Mapped local customer not found: ${mapping.localId}")
    }

    private suspend fun saveMapping(type: String, localId: String, serverId: String, version: Long) {
        require(version >= 0L)
        val owner = mappings.getAnyByServerId(serverId)
        require(owner == null || (owner.entityType == type && owner.localId == localId)) {
            "Server ID already belongs to another local entity."
        }
        val existing = mappings.getMapping(type, localId)
        require(existing == null || existing.serverId == serverId) {
            "Local entity already maps to a different server ID."
        }
        mappings.upsertMapping(ServerIdMappingEntity(type, localId, serverId, version, System.currentTimeMillis()))
    }

    private fun mga(json: JSONObject, field: String): Long {
        val value = json.opt(field)
        require(value is Number) { "Missing or non-numeric MGA field: $field" }
        val result = BigDecimal(value.toString()).longValueExact()
        require(result >= 0L && result <= 9_007_199_254_740_991L) { "MGA field outside safe integer range: $field" }
        return result
    }
}
