package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.*
import com.example.domain.model.*
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray
import org.json.JSONObject

class FanevaRepository(
    private val database: FanevaDatabase
) {
    private val productDao = database.productDao()
    private val stockDao = database.storeStockDao()
    private val customerDao = database.customerDao()
    private val saleDao = database.saleDao()
    private val movementDao = database.stockMovementDao()
    private val debtPaymentDao = database.debtPaymentDao()
    private val pendingSyncDao = database.pendingSyncDao()
    private val syncCursorDao = database.syncCursorDao()
    private val userPreferenceDao = database.userPreferenceDao()
    private val transferRequestDao = database.transferRequestDao()

    // Flows
    val allProducts: Flow<List<ProductEntity>> = productDao.getAllProducts()
    val allCustomers: Flow<List<CustomerEntity>> = customerDao.getAllCustomers()
    val allSales: Flow<List<SaleEntity>> = saleDao.getAllSales()
    val allStocks: Flow<List<StoreStockEntity>> = stockDao.getAllStocks()
    val allMovements: Flow<List<StockMovementEntity>> = movementDao.getAllMovements()
    val pendingOperations: Flow<List<PendingSyncOperationEntity>> = pendingSyncDao.getAllPendingOperations()
    val pendingCount: Flow<Int> = pendingSyncDao.getPendingCount()
    val syncCursor: Flow<SyncCursorEntity?> = syncCursorDao.getCursorFlow()
    val userPreferences: Flow<UserPreferenceEntity?> = userPreferenceDao.getPreferencesFlow()

    fun searchProducts(query: String): Flow<List<ProductEntity>> = productDao.searchProducts(query)

    fun getStocksByStore(storeId: String): Flow<List<StoreStockEntity>> = stockDao.getStocksByStore(storeId)

    suspend fun getStockForProductAndStore(productId: Long, storeId: String): Int {
        return stockDao.getStock(productId, storeId)?.quantity ?: 0
    }

    /**
     * Vente Atomique avec contrôle strict :
     * 1. Vérification de l'idempotence (prévention des doublons lors des retries)
     * 2. Règle absolue : Aucune quantité de stock négative
     * 3. Décrémentation atomique du stock
     * 4. Enregistrement de la vente et des lignes
     * 5. Cohérence vente à crédit / dette client
     * 6. Journalisation des mouvements de stock
     * 7. Mise en file d'attente Outbox (PendingSyncOperation)
     */
    suspend fun processSale(
        storeId: String,
        cartItems: List<CartItem>,
        saleType: SaleType,
        paymentMethod: PaymentMethod,
        customerId: Long?,
        customerName: String?,
        sellerId: String,
        sellerName: String,
        paidAmount: Double,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): SaleValidationResult {
        if (cartItems.isEmpty()) {
            return SaleValidationResult(isSuccess = false, errorMessage = "Le panier est vide")
        }

        return database.withTransaction {
            // 1. Détection des doublons idempotents
            val existingSale = saleDao.getSaleByIdempotencyKey(idempotencyKey)
            if (existingSale != null) {
                return@withTransaction SaleValidationResult(
                    isSuccess = true,
                    saleId = existingSale.id,
                    saleNumber = existingSale.saleNumber,
                    errorMessage = null
                )
            }

            // Validation stricte avant toute mutation du stock.
            if (cartItems.any { item ->
                    item.quantity <= 0 ||
                    !item.unitPrice.isFinite() ||
                    item.unitPrice < 0.0 ||
                    !item.total.isFinite() ||
                    item.total < 0.0
                }) {
                return@withTransaction SaleValidationResult(
                    isSuccess = false,
                    errorMessage = "Quantité ou prix invalide. La vente est annulée."
                )
            }

            // 2. Vérification stricte des stocks disponibles dans le magasin actif
            for (item in cartItems) {
                val currentStock = stockDao.getStock(item.productId, storeId)?.quantity ?: 0
                if (currentStock < item.quantity) {
                    return@withTransaction SaleValidationResult(
                        isSuccess = false,
                        errorMessage = "Stock insuffisant pour '${item.productName}' dans ${storeId}. Disponible: $currentStock, Demandé: ${item.quantity}. Aucune quantité négative n'est autorisée."
                    )
                }
            }

            // Calculs financiers
            val totalAmount = cartItems.sumOf { it.total }
            if (!totalAmount.isFinite() || totalAmount < 0.0) {
                return@withTransaction SaleValidationResult(
                    isSuccess = false,
                    errorMessage = "Montant total invalide. La vente est annulée."
                )
            }
            if (saleType == SaleType.CREDIT &&
                (!paidAmount.isFinite() || paidAmount < 0.0)) {
                return@withTransaction SaleValidationResult(
                    isSuccess = false,
                    errorMessage = "Le montant payé est invalide."
                )
            }
            if (saleType == SaleType.CREDIT && paidAmount > totalAmount) {
                return@withTransaction SaleValidationResult(
                    isSuccess = false,
                    errorMessage = "Le montant payé ne peut pas dépasser le total de la vente."
                )
            }
            val actualPaid = when (saleType) {
                SaleType.CASH -> totalAmount
                SaleType.CREDIT -> paidAmount.coerceIn(0.0, totalAmount)
            }
            val remaining = (totalAmount - actualPaid).coerceAtLeast(0.0)

            // Vente à crédit : vérification du client et cohérence dette
            if (saleType == SaleType.CREDIT) {
                if (customerId == null) {
                    return@withTransaction SaleValidationResult(
                        isSuccess = false,
                        errorMessage = "Une vente à crédit exige obligatoirement la sélection d'un client"
                    )
                }
                val customer = customerDao.getCustomerById(customerId)
                    ?: return@withTransaction SaleValidationResult(
                        isSuccess = false,
                        errorMessage = "Client introuvable dans la base"
                    )

                if (customer.currentDebt + remaining > customer.creditLimit) {
                    val excess = (customer.currentDebt + remaining) - customer.creditLimit
                    return@withTransaction SaleValidationResult(
                        isSuccess = false,
                        errorMessage = "Plafond de crédit dépassé de ${String.format("%,.0f", excess)} Ar pour ${customer.name}. Plafond max: ${String.format("%,.0f", customer.creditLimit)} Ar"
                    )
                }

                // Mise à jour cohérente de la dette client
                customerDao.updateDebt(customerId, customer.currentDebt + remaining)
            }

            val saleId = UUID.randomUUID().toString()
            val dateFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
            val saleNumber = "VNT-${dateFormat.format(Date())}-${(100..999).random()}"

            // 3. Décrémentation atomique du stock et création des mouvements
            for (item in cartItems) {
                val stock = stockDao.getStock(item.productId, storeId)
                val currentQty = stock?.quantity ?: 0
                val newQty = currentQty - item.quantity
                if (newQty < 0) {
                    // Double check invariant
                    throw IllegalStateException("Violation de stock négatif détectée")
                }
                check(stockDao.updateQuantity(item.productId, storeId, newQty) == 1) {
                    "Stock de vente non mis à jour"
                }

                // Mouvement de stock traçable
                movementDao.insertMovement(
                    StockMovementEntity(
                        id = UUID.randomUUID().toString(),
                        movementNumber = "MVT-${UUID.randomUUID().toString().take(8).uppercase()}",
                        productId = item.productId,
                        productName = item.productName,
                        fromStoreId = storeId,
                        toStoreId = null,
                        quantity = -item.quantity,
                        movementType = "VENTE",
                        referenceId = saleNumber,
                        reason = "Sortie vente $saleNumber ($storeId)",
                        performedBy = sellerName
                    )
                )
            }

            // 4. Enregistrement de la vente
            val saleEntity = SaleEntity(
                id = saleId,
                saleNumber = saleNumber,
                storeId = storeId,
                customerId = customerId,
                customerName = customerName,
                sellerId = sellerId,
                sellerName = sellerName,
                saleType = saleType.name,
                totalAmount = totalAmount,
                paidAmount = actualPaid,
                remainingAmount = remaining,
                paymentMethod = paymentMethod.name,
                status = "COMPLETED",
                idempotencyKey = idempotencyKey,
                createdAt = System.currentTimeMillis()
            )
            saleDao.insertSale(saleEntity)

            // Lignes de vente
            val itemsEntities = cartItems.map { item ->
                SaleItemEntity(
                    id = UUID.randomUUID().toString(),
                    saleId = saleId,
                    productId = item.productId,
                    productCode = item.productCode,
                    productName = item.productName,
                    unitPrice = item.unitPrice,
                    quantity = item.quantity,
                    total = item.total
                )
            }
            saleDao.insertSaleItems(itemsEntities)

            // 5. Persistance locale Outbox pour future synchronisation Cloudflare Workers
            val itemsJson = JSONArray()
            cartItems.forEach { item ->
                itemsJson.put(
                    JSONObject()
                        .put("code", item.productCode)
                        .put("qty", item.quantity)
                        .put("price", item.unitPrice)
                )
            }

            val payload = JSONObject()
                .put("saleId", saleId)
                .put("saleNumber", saleNumber)
                .put("storeId", storeId)
                .put("saleType", saleType.name)
                .put("totalAmount", totalAmount)
                .put("paidAmount", actualPaid)
                .put("remainingAmount", remaining)
                .put("paymentMethod", paymentMethod.name)
                .put("customerId", customerId ?: JSONObject.NULL)
                .put("idempotencyKey", idempotencyKey)
                .put("items", itemsJson)
                .put("timestamp", System.currentTimeMillis())
                .toString()

            pendingSyncDao.insertOperation(
                PendingSyncOperationEntity(
                    mutationId = UUID.randomUUID().toString(),
                    entityType = "SALE",
                    action = "CREATE",
                    payloadJson = payload,
                    idempotencyKey = idempotencyKey,
                    status = "PENDING"
                )
            )

            SaleValidationResult(
                isSuccess = true,
                saleId = saleId,
                saleNumber = saleNumber
            )
        }
    }

    /**
     * Transfert inter-magasins avec registre durable d'idempotence.
     *
     * PREPARED est enregistré dans une transaction séparée.
     * Stock, mouvement, outbox et COMPLETED sont ensuite atomiques.
     */
    /**
     * Retourne la clé durable du transfert en cours.
     * Une clé existante est toujours réutilisée, même si le transfert
     * est déjà terminé, afin d'éviter toute double exécution après interruption.
     */
    suspend fun getOrCreateTransferIdempotencyKey(): String {
        return database.withTransaction {
            val preferences = userPreferenceDao.getPreferences()
                ?: UserPreferenceEntity().also {
                    userPreferenceDao.savePreferences(it)
                }

            val currentKey = preferences.activeTransferIdempotencyKey
            if (!currentKey.isNullOrBlank()) {
                return@withTransaction currentKey
            }

            val newKey = UUID.randomUUID().toString()
            userPreferenceDao.savePreferences(
                preferences.copy(activeTransferIdempotencyKey = newKey)
            )
            newKey
        }
    }

    /**
     * Efface uniquement la clé correspondant à la requête terminée.
     */
    suspend fun clearTransferIdempotencyKey(key: String) {
        database.withTransaction {
            val request = transferRequestDao.getByIdempotencyKey(key)

            if (
                request == null ||
                request.status == "COMPLETED" ||
                request.status == "REJECTED"
            ) {
                userPreferenceDao.clearActiveTransferKey(key)
            }
        }
    }

    suspend fun transferStock(
        productId: Long,
        productName: String,
        fromStoreId: String,
        toStoreId: String,
        quantity: Int,
        operatorName: String,
        reason: String,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): TransferValidationResult {
        val key = idempotencyKey.trim()
        if (key.isBlank()) {
            return TransferValidationResult(
                isSuccess = false,
                errorMessage = "Clé d'idempotence invalide."
            )
        }

        if (fromStoreId == toStoreId) {
            return TransferValidationResult(
                isSuccess = false,
                errorMessage = "Les magasins source et destinataire doivent être distincts."
            )
        }

        if (quantity <= 0) {
            return TransferValidationResult(
                isSuccess = false,
                errorMessage = "La quantité doit être strictement positive."
            )
        }

        if (StoreId.entries.none { it.code == fromStoreId } ||
            StoreId.entries.none { it.code == toStoreId }) {
            return TransferValidationResult(
                isSuccess = false,
                errorMessage = "Magasin source ou destinataire inconnu."
            )
        }

        val normalizedReason = reason.trim().ifBlank {
            "Transfert de $fromStoreId vers $toStoreId"
        }

        val prepared = database.withTransaction {
            val existing = transferRequestDao.getByIdempotencyKey(key)

            if (existing != null) {
                val sameRequest =
                    existing.productId == productId &&
                    existing.fromStoreId == fromStoreId &&
                    existing.toStoreId == toStoreId &&
                    existing.quantity == quantity &&
                    existing.operatorName == operatorName &&
                    existing.reason == normalizedReason

                if (!sameRequest) {
                    return@withTransaction null
                }
                existing
            } else {
                val canonicalProduct = productDao.getProductById(productId)
                    ?: return@withTransaction null

                val requested = TransferRequestEntity(
                    idempotencyKey = key,
                    productId = productId,
                    productName = canonicalProduct.name,
                    fromStoreId = fromStoreId,
                    toStoreId = toStoreId,
                    quantity = quantity,
                    operatorName = operatorName,
                    reason = normalizedReason,
                    status = "PREPARED",
                    createdAt = System.currentTimeMillis(),
                    completedAt = null
                )
                transferRequestDao.insertRequest(requested)
                requested
            }
        } ?: return TransferValidationResult(
            isSuccess = false,
            errorMessage = "Conflit ou produit introuvable pour cette demande de transfert."
        )

        if (prepared.status == "COMPLETED") {
            return TransferValidationResult(
                isSuccess = true,
                transferId = key
            )
        }

        if (prepared.status == "REJECTED") {
            return TransferValidationResult(
                isSuccess = false,
                errorMessage = "Ce transfert a déjà été rejeté. Créez une nouvelle demande."
            )
        }

        return completePreparedTransfer(prepared)
    }

    private suspend fun rejectPreparedTransfer(
        stored: TransferRequestEntity,
        message: String
    ): TransferValidationResult {
        val updatedRows = transferRequestDao.updateRequest(
            stored.copy(
                status = "REJECTED",
                completedAt = null
            )
        )

        check(updatedRows == 1) {
            "État REJECTED du transfert non enregistré."
        }

        return TransferValidationResult(
            isSuccess = false,
            errorMessage = message
        )
    }

    private suspend fun completePreparedTransfer(
        request: TransferRequestEntity
    ): TransferValidationResult {
        return database.withTransaction {
            val stored = transferRequestDao.getByIdempotencyKey(
                request.idempotencyKey
            ) ?: return@withTransaction TransferValidationResult(
                isSuccess = false,
                errorMessage = "Demande de transfert introuvable."
            )

            if (stored.status == "COMPLETED") {
                return@withTransaction TransferValidationResult(
                    isSuccess = true,
                    transferId = stored.idempotencyKey
                )
            }

            if (stored.status != "PREPARED") {
                return@withTransaction TransferValidationResult(
                    isSuccess = false,
                    errorMessage = "État de transfert non reconnu : ${stored.status}"
                )
            }

            if (StoreId.entries.none { it.code == stored.fromStoreId } ||
                StoreId.entries.none { it.code == stored.toStoreId }) {
                return@withTransaction rejectPreparedTransfer(stored, "Magasin source ou destinataire inconnu.")
            }

            if (stored.quantity <= 0 ||
                stored.fromStoreId == stored.toStoreId) {
                return@withTransaction rejectPreparedTransfer(stored, "Données de transfert invalides.")
            }

            if (productDao.getProductById(stored.productId) == null) {
                return@withTransaction rejectPreparedTransfer(stored, "Produit introuvable.")
            }

            val fromStockEntity = stockDao.getStock(
                stored.productId,
                stored.fromStoreId
            )
            val toStockEntity = stockDao.getStock(
                stored.productId,
                stored.toStoreId
            )

            if (fromStockEntity == null || toStockEntity == null) {
                return@withTransaction rejectPreparedTransfer(stored, "Stock source ou destination introuvable.")
            }

            val fromStock = fromStockEntity.quantity
            val toStock = toStockEntity.quantity

            if (fromStock < 0 || toStock < 0) {
                return@withTransaction rejectPreparedTransfer(stored, "Transfert bloqué : un stock existant est négatif.")
            }

            if (fromStock < stored.quantity) {
                return@withTransaction rejectPreparedTransfer(stored, "Stock insuffisant dans ${stored.fromStoreId} : disponible $fromStock, requis ${stored.quantity}.")
            }

            if (toStock > Int.MAX_VALUE - stored.quantity) {
                return@withTransaction rejectPreparedTransfer(stored, "Dépassement de capacité du stock destination.")
            }

            val now = System.currentTimeMillis()
            val newFromQty = fromStock - stored.quantity
            val newToQty = toStock + stored.quantity

            check(
                stockDao.updateQuantity(
                    stored.productId,
                    stored.fromStoreId,
                    newFromQty,
                    now
                ) == 1
            ) { "Stock source non mis à jour." }

            check(
                stockDao.updateQuantity(
                    stored.productId,
                    stored.toStoreId,
                    newToQty,
                    now
                ) == 1
            ) { "Stock destination non mis à jour." }

            val movementNumber =
                "TRF-${UUID.randomUUID().toString().take(8).uppercase()}"

            movementDao.insertMovement(
                StockMovementEntity(
                    id = UUID.randomUUID().toString(),
                    movementNumber = movementNumber,
                    productId = stored.productId,
                    productName = stored.productName,
                    fromStoreId = stored.fromStoreId,
                    toStoreId = stored.toStoreId,
                    quantity = stored.quantity,
                    movementType = "TRANSFERT",
                    referenceId = stored.idempotencyKey,
                    reason = stored.reason,
                    performedBy = stored.operatorName,
                    createdAt = now
                )
            )

            val payload = JSONObject()
                .put("transferId", stored.idempotencyKey)
                .put("movementNumber", movementNumber)
                .put("productId", stored.productId)
                .put("productName", stored.productName)
                .put("fromStoreId", stored.fromStoreId)
                .put("toStoreId", stored.toStoreId)
                .put("quantity", stored.quantity)
                .put("operator", stored.operatorName)
                .put("reason", stored.reason)
                .put("idempotencyKey", stored.idempotencyKey)
                .put("timestamp", now)
                .toString()

            pendingSyncDao.insertOperation(
                PendingSyncOperationEntity(
                    mutationId = UUID.randomUUID().toString(),
                    entityType = "TRANSFER",
                    action = "CREATE",
                    payloadJson = payload,
                    idempotencyKey = stored.idempotencyKey,
                    status = "PENDING"
                )
            )

            val updatedRows = transferRequestDao.updateRequest(
                stored.copy(
                    status = "COMPLETED",
                    completedAt = now
                )
            )

            check(updatedRows == 1) {
                "État COMPLETED du transfert non enregistré."
            }

            TransferValidationResult(
                isSuccess = true,
                transferId = stored.idempotencyKey
            )
        }
    }

    suspend fun recoverPreparedTransfers(): List<TransferValidationResult> {
        val preparedRequests = transferRequestDao.getPreparedRequests()
        val results = preparedRequests.map { request ->
            completePreparedTransfer(request)
        }

        database.withTransaction {
            val activeKey = userPreferenceDao.getPreferences()
                ?.activeTransferIdempotencyKey

            if (!activeKey.isNullOrBlank()) {
                val request = transferRequestDao.getByIdempotencyKey(activeKey)
                if (request?.status == "COMPLETED" ||
                    request?.status == "REJECTED"
                ) {
                    userPreferenceDao.clearActiveTransferKey(activeKey)
                }
            }
        }

        return results
    }

    /**
     * Règlement de dette client
     */
    suspend fun recordDebtPayment(
        customerId: Long,
        amount: Double,
        paymentMethod: PaymentMethod,
        note: String,
        operatorName: String,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): Result<String> {
        if (!amount.isFinite() || amount <= 0) {
            return Result.failure(IllegalArgumentException("Le montant du règlement doit être fini et supérieur à zéro"))
        }

        return database.withTransaction {
            val customer = customerDao.getCustomerById(customerId)
                ?: return@withTransaction Result.failure(IllegalArgumentException("Client introuvable"))
            val existingPayment = debtPaymentDao.getPaymentByIdempotencyKey(idempotencyKey)
            if (existingPayment != null) return@withTransaction Result.success(existingPayment.receiptNumber)
            if (amount > customer.currentDebt) {
                return@withTransaction Result.failure(
                    IllegalArgumentException("Le paiement ne peut pas dépasser le solde restant de la dette")
                )
            }

            val newDebt = customer.currentDebt - amount
            check(newDebt >= 0.0) { "Le solde de dette ne peut pas être négatif" }
            customerDao.updateDebt(customerId, newDebt)

            val receiptNumber = "REC-${System.currentTimeMillis().toString().takeLast(6)}"
            val paymentId = idempotencyKey

            val paymentEntity = DebtPaymentEntity(
                id = paymentId,
                customerId = customerId,
                customerName = customer.name,
                amountPaid = amount,
                paymentMethod = paymentMethod.name,
                note = note,
                receiptNumber = receiptNumber,
                idempotencyKey = paymentId
            )
            debtPaymentDao.insertPayment(paymentEntity)

            val payload = JSONObject()
                .put("paymentId", paymentId)
                .put("receiptNumber", receiptNumber)
                .put("customerId", customerId)
                .put("amountPaid", amount)
                .put("newDebt", newDebt)
                .put("paymentMethod", paymentMethod.name)
                .put("operator", operatorName)
                .put("idempotencyKey", idempotencyKey)
                .put("timestamp", System.currentTimeMillis())
                .toString()

            pendingSyncDao.insertOperation(
                PendingSyncOperationEntity(
                    mutationId = UUID.randomUUID().toString(),
                    entityType = "DEBT_PAYMENT",
                    action = "CREATE",
                    payloadJson = payload,
                    idempotencyKey = paymentId,
                    status = "PENDING"
                )
            )

            Result.success(receiptNumber)
        }
    }

    suspend fun addNewCustomer(
        name: String,
        phone: String,
        address: String,
        creditLimit: Double
    ): Long {
        require(creditLimit.isFinite() && creditLimit >= 0.0) { "Plafond de crédit invalide" }
        return database.withTransaction {
            val id = customerDao.insertCustomer(
                CustomerEntity(
                    name = name,
                    phone = phone,
                    address = address,
                    creditLimit = creditLimit,
                    currentDebt = 0.0
                )
            )
            val payload = JSONObject()
                .put("customerId", id)
                .put("name", name)
                .put("phone", phone)
                .put("address", address)
                .put("creditLimit", creditLimit)
                .put("timestamp", System.currentTimeMillis())
                .toString()
            pendingSyncDao.insertOperation(
                PendingSyncOperationEntity(
                    mutationId = UUID.randomUUID().toString(),
                    entityType = "CUSTOMER",
                    action = "CREATE",
                    payloadJson = payload,
                    idempotencyKey = "CUST-$id",
                    status = "PENDING"
                )
            )
            id
        }
    }

    suspend fun addNewProduct(
        code: String,
        name: String,
        category: String,
        purchasePrice: Double,
        sellingPrice: Double,
        minAlert: Int,
        unit: String,
        initialStoreStocks: Map<String, Int>
    ): Long {
        require(purchasePrice.isFinite() && purchasePrice >= 0.0) { "Prix d'achat invalide" }
        require(sellingPrice.isFinite() && sellingPrice >= 0.0) { "Prix de vente invalide" }
        require(minAlert >= 0 && initialStoreStocks.values.all { it >= 0 }) { "Stock initial invalide" }
        return database.withTransaction {
            val productId = productDao.insertProduct(
                ProductEntity(
                    code = code,
                    name = name,
                    category = category,
                    purchasePrice = purchasePrice,
                    sellingPrice = sellingPrice,
                    minStockAlert = minAlert,
                    unit = unit
                )
            )
            StoreId.entries.forEach { store ->
                stockDao.insertOrUpdateStock(
                    StoreStockEntity(store.code, productId, initialStoreStocks[store.code] ?: 0)
                )
            }
            val payload = JSONObject()
                .put("productId", productId)
                .put("code", code)
                .put("name", name)
                .put("category", category)
                .put("sellingPrice", sellingPrice)
                .put("timestamp", System.currentTimeMillis())
                .toString()
            pendingSyncDao.insertOperation(
                PendingSyncOperationEntity(
                    mutationId = UUID.randomUUID().toString(),
                    entityType = "PRODUCT",
                    action = "CREATE",
                    payloadJson = payload,
                    idempotencyKey = "PRD-$productId",
                    status = "PENDING"
                )
            )
            productId
        }
    }

    suspend fun getSaleDetails(saleId: String): List<SaleItemEntity> {
        return saleDao.getSaleItems(saleId)
    }

    suspend fun updateUserPreference(role: String, storeId: String, operator: String) {
        database.withTransaction {
            val current = userPreferenceDao.getPreferences() ?: UserPreferenceEntity()
            userPreferenceDao.savePreferences(
                current.copy(
                    activeRole = role,
                    activeStoreId = storeId,
                    operatorName = operator
                )
            )
        }
    }

    suspend fun updateStagingApiUrl(url: String) {
        val current = syncCursorDao.getCursor() ?: SyncCursorEntity()
        syncCursorDao.saveCursor(current.copy(stagingApiUrl = url))
    }

    suspend fun toggleNetworkSimulation(isOnline: Boolean) {
        val current = syncCursorDao.getCursor() ?: SyncCursorEntity()
        syncCursorDao.saveCursor(current.copy(isSimulatedOnline = isOnline))
    }

    suspend fun resetDatabase(): Result<Unit> {
        val hasUserData = database.productDao().countAll() > 0 ||
            database.customerDao().countAll() > 0 ||
            database.saleDao().countAll() > 0 ||
            database.stockMovementDao().countAll() > 0 ||
            database.debtPaymentDao().countAll() > 0 ||
            database.pendingSyncDao().countAll() > 0
        if (hasUserData) {
            return Result.failure(
                IllegalStateException("Réinitialisation refusée : la base contient des données utilisateur ou des opérations non acquittées.")
            )
        }
        FanevaDatabase.populateInitialDemoData(database)
        return Result.success(Unit)
    }
}
