package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.*
import com.example.domain.model.*
import com.example.domain.security.AuthorizationPolicy
import com.example.domain.security.ProtectedAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
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

    /**
     * Repository boundary authorization. This deliberately reloads the persisted session,
     * active account and store grants from Room; ViewModel state/preferences are not trusted.
     */
    private suspend fun hasRepositoryAuthorization(
        action: ProtectedAction,
        storeIds: List<String> = emptyList()
    ): Boolean {
        val sessionDao = database.securitySessionDao()
        val persisted = sessionDao.getCurrentActiveSession() ?: return false
        val account = database.authDao().findActiveById(persisted.userId) ?: run {
            sessionDao.revokeSession(persisted.sessionId)
            return false
        }
        if (persisted.role != account.role) {
            sessionDao.revokeSession(persisted.sessionId)
            return false
        }
        val stores = sessionDao.getAuthorizedStores(account.id).map { it.storeId }.toSet()
        val role = UserRole.entries.firstOrNull { it.code == account.role } ?: return false
        val session = UserSession(
            id = account.id,
            name = account.displayName,
            email = account.username,
            role = role,
            defaultStoreId = stores.sorted().firstOrNull() ?: return false,
            sessionId = persisted.sessionId,
            createdAt = persisted.createdAt,
            expiresAt = persisted.expiresAt,
            authorizedStoreIds = stores,
            status = persisted.status
        )
        if (!AuthorizationPolicy.isSessionValid(session)) return false
        if (storeIds.isEmpty()) return AuthorizationPolicy.canPerform(session, action)
        return storeIds.distinct().all { store ->
            AuthorizationPolicy.canPerform(session, action, store)
        }
    }

    private suspend fun loadPersistedReadSession(): UserSession? {
        val sessionDao = database.securitySessionDao()
        val persisted = sessionDao.getCurrentActiveSession() ?: return null
        val account = database.authDao().findActiveById(persisted.userId) ?: return null
        if (persisted.role != account.role) return null
        val stores = sessionDao.getAuthorizedStores(account.id).map { it.storeId }.toSet()
        val role = UserRole.entries.firstOrNull { it.code == account.role } ?: return null
        val session = UserSession(
            id = account.id,
            name = account.displayName,
            email = account.username,
            role = role,
            defaultStoreId = stores.sorted().firstOrNull() ?: return null,
            sessionId = persisted.sessionId,
            createdAt = persisted.createdAt,
            expiresAt = persisted.expiresAt,
            authorizedStoreIds = stores,
            status = persisted.status
        )
        return session.takeIf { AuthorizationPolicy.isSessionValid(it) }
    }

    // Room invalidation reacts to login/logout/grant changes. Periodic refresh closes
    // streams when a persisted session expires without another database write.
    private val persistedReadSessionFlow: Flow<UserSession?> = combine(
        database.securitySessionDao().observeActiveSession(),
        flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(15_000L)
            }
        }
    ) { _, _ -> loadPersistedReadSession() }.distinctUntilChanged()

    val sellerAccounts: Flow<List<SellerAccountSummary>> =
        persistedReadSessionFlow.flatMapLatest { session ->
            if (session == null || session.role != UserRole.ADMIN) {
                flowOf(emptyList())
            } else {
                database.authDao().observeActiveSellers().map { accounts ->
                    accounts.filter { it.accountStatus == "ACTIVE" }
                        .map { SellerAccountSummary(it.username, it.displayName, it.createdAt) }
                }
            }
        }

    private suspend fun hasPersistedTransferRole(): Boolean {
        val persisted = database.securitySessionDao().getCurrentActiveSession() ?: return false
        val account = database.authDao().findActiveById(persisted.userId) ?: return false
        if (account.role != persisted.role) return false
        val stores = database.securitySessionDao().getAuthorizedStores(account.id).map { it.storeId }.toSet()
        val role = UserRole.entries.firstOrNull { it.code == account.role } ?: return false
        val session = UserSession(
            id = account.id,
            name = account.displayName,
            email = account.username,
            role = role,
            defaultStoreId = stores.sorted().firstOrNull() ?: return false,
            sessionId = persisted.sessionId,
            createdAt = persisted.createdAt,
            expiresAt = persisted.expiresAt,
            authorizedStoreIds = stores,
            status = persisted.status
        )
        return AuthorizationPolicy.isSessionValid(session) &&
            role in setOf(UserRole.ADMIN, UserRole.GERANT)
    }

    private suspend fun isPersistedSessionAuthorizedForStore(
        session: UserSession,
        storeId: String
    ): Boolean {
        if (!AuthorizationPolicy.isSessionValid(session) || StoreId.parseStrict(storeId) == null) return false
        val persisted = database.securitySessionDao().getCurrentActiveSession() ?: return false
        if (persisted.sessionId != session.sessionId || persisted.userId != session.id ||
            persisted.role != session.role.code || persisted.status != "ACTIVE" ||
            persisted.expiresAt <= System.currentTimeMillis()) return false
        val account = database.authDao().findActiveById(session.id) ?: return false
        if (account.role != session.role.code) return false
        val grants = database.securitySessionDao().getAuthorizedStores(session.id).map { it.storeId }.toSet()
        return storeId in grants && storeId in session.authorizedStoreIds
    }

    // Protected read streams are authorized from the persisted Room session, never UI preferences.
    val allProducts: Flow<List<ProductEntity>> = persistedReadSessionFlow.flatMapLatest { session ->
        if (session == null || !AuthorizationPolicy.canPerform(session, ProtectedAction.READ_PRODUCTS)) {
            flowOf(emptyList())
        } else {
            productDao.getAllProducts().map { products ->
                if (session.role == UserRole.VENDEUR) products.map { it.copy(purchasePrice = 0.0) }
                else products
            }
        }
    }

    // Financial customer registry is ADMIN/GERANT only; sellers get a separate POS-safe stream.
    val allCustomers: Flow<List<CustomerEntity>> = persistedReadSessionFlow.flatMapLatest { session ->
        if (session == null ||
            !AuthorizationPolicy.canPerform(session, ProtectedAction.READ_CUSTOMERS) ||
            !AuthorizationPolicy.canPerform(session, ProtectedAction.READ_CUSTOMER_DEBT) ||
            !AuthorizationPolicy.canPerform(session, ProtectedAction.READ_CREDIT_LIMIT)) {
            flowOf(emptyList())
        } else {
            customerDao.getAllCustomers()
        }
    }

    val posCustomers: Flow<List<CustomerEntity>> = persistedReadSessionFlow.flatMapLatest { session ->
        if (session == null || !AuthorizationPolicy.canPerform(session, ProtectedAction.READ_POS_CUSTOMERS)) {
            flowOf(emptyList())
        } else {
            customerDao.getAllCustomers().map { customers ->
                if (session.role == UserRole.VENDEUR) {
                    customers.map { it.copy(phone = "", address = "", currentDebt = 0.0, creditLimit = 0.0) }
                } else {
                    customers
                }
            }
        }
    }

    val allSales: Flow<List<SaleEntity>> = persistedReadSessionFlow.flatMapLatest { session ->
        if (session == null || session.authorizedStoreIds.isEmpty()) {
            flowOf(emptyList())
        } else {
            val readAction = if (session.role == UserRole.VENDEUR) ProtectedAction.READ_OWN_SALES
            else ProtectedAction.READ_SALES
            if (!session.authorizedStoreIds.all { AuthorizationPolicy.canPerform(session, readAction, it) }) {
                flowOf(emptyList())
            } else if (session.role == UserRole.VENDEUR) {
                saleDao.getSalesForSellerAndStores(session.id, session.authorizedStoreIds.toList()).map { sales ->
                    sales.map {
                        it.copy(
                            customerId = null,
                            customerName = null,
                            paidAmount = 0.0,
                            remainingAmount = 0.0,
                            idempotencyKey = ""
                        )
                    }
                }
            } else {
                saleDao.getSalesForStores(session.authorizedStoreIds.toList())
            }
        }
    }

    val allStocks: Flow<List<StoreStockEntity>> = persistedReadSessionFlow.flatMapLatest { session ->
        if (session == null || session.authorizedStoreIds.isEmpty() ||
            !session.authorizedStoreIds.all { AuthorizationPolicy.canPerform(session, ProtectedAction.READ_STOCK, it) }) {
            flowOf(emptyList())
        } else {
            stockDao.getStocksForStores(session.authorizedStoreIds.toList())
        }
    }

    val allMovements: Flow<List<StockMovementEntity>> = persistedReadSessionFlow.flatMapLatest { session ->
        if (session == null || session.authorizedStoreIds.isEmpty() ||
            !session.authorizedStoreIds.all { AuthorizationPolicy.canPerform(session, ProtectedAction.READ_STOCK_MOVEMENTS, it) }) {
            flowOf(emptyList())
        } else {
            movementDao.getMovementsForStores(session.authorizedStoreIds.toList()).map { movements ->
                movements.filter { movement ->
                    val involvedStores = listOfNotNull(movement.fromStoreId, movement.toStoreId)
                    involvedStores.isNotEmpty() && involvedStores.all { it in session.authorizedStoreIds }
                }
            }
        }
    }

    val debtPayments: Flow<List<DebtPaymentEntity>> = persistedReadSessionFlow.flatMapLatest { session ->
        if (session == null || !AuthorizationPolicy.canPerform(session, ProtectedAction.READ_DEBT_PAYMENTS)) flowOf(emptyList())
        else debtPaymentDao.getAllPayments()
    }

    val pendingOperations: Flow<List<PendingSyncOperationEntity>> = persistedReadSessionFlow.flatMapLatest { session ->
        if (session == null || !AuthorizationPolicy.canPerform(session, ProtectedAction.READ_PENDING_OPERATIONS)) flowOf(emptyList())
        else pendingSyncDao.getAllPendingOperations()
    }
    val pendingCount: Flow<Int> = persistedReadSessionFlow.flatMapLatest { session ->
        if (session == null || !AuthorizationPolicy.canPerform(session, ProtectedAction.READ_PENDING_OPERATIONS)) flowOf(0)
        else pendingSyncDao.getPendingCount()
    }
    val syncCursor: Flow<SyncCursorEntity?> = persistedReadSessionFlow.flatMapLatest { session ->
        if (session == null || !AuthorizationPolicy.canPerform(session, ProtectedAction.SYSTEM_CONFIGURATION)) flowOf(null)
        else syncCursorDao.getCursorFlow()
    }
    val userPreferences: Flow<UserPreferenceEntity?> = persistedReadSessionFlow.flatMapLatest { session ->
        if (session == null) flowOf(null)
        else userPreferenceDao.getPreferencesFlow().map { preference ->
            preference?.takeIf { it.activeStoreId in session.authorizedStoreIds }?.copy(
                activeRole = session.role.code,
                operatorName = session.name,
                activeTransferIdempotencyKey = null
            )
        }
    }

    fun searchProducts(query: String): Flow<List<ProductEntity>> =
        persistedReadSessionFlow.flatMapLatest { session ->
            if (session == null || !AuthorizationPolicy.canPerform(session, ProtectedAction.READ_PRODUCTS)) flowOf(emptyList())
            else productDao.searchProducts(query).map { products ->
                if (session.role == UserRole.VENDEUR) products.map { it.copy(purchasePrice = 0.0) }
                else products
            }
        }

    fun getStocksByStore(storeId: String): Flow<List<StoreStockEntity>> =
        persistedReadSessionFlow.flatMapLatest { session ->
            if (session == null || !AuthorizationPolicy.canPerform(session, ProtectedAction.READ_STOCK, storeId)) flowOf(emptyList())
            else stockDao.getStocksByStore(storeId)
        }

    suspend fun getStockForProductAndStore(productId: Long, storeId: String): Int {
        val session = loadPersistedReadSession() ?: return 0
        if (!AuthorizationPolicy.canPerform(session, ProtectedAction.READ_STOCK, storeId)) return 0
        return stockDao.getStock(productId, storeId)?.quantity ?: 0
    }

    suspend fun getSaleDetails(saleId: String): List<SaleItemEntity> {
        val session = loadPersistedReadSession() ?: throw SecurityException("Session de lecture invalide.")
        val sale = saleDao.getSaleById(saleId) ?: return emptyList()
        val readAction = if (session.role == UserRole.VENDEUR) ProtectedAction.READ_OWN_SALES
        else ProtectedAction.READ_SALES
        if (!AuthorizationPolicy.canPerform(session, readAction, sale.storeId) ||
            (session.role == UserRole.VENDEUR && sale.sellerId != session.id)) {
            throw SecurityException("Accès aux détails de cette vente refusé.")
        }
        return saleDao.getSaleItems(saleId)
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
        val saleAction = if (saleType == SaleType.CASH) ProtectedAction.CASH_SALE else ProtectedAction.CREDIT_SALE
        val actorSession = loadPersistedReadSession()
        if (actorSession == null ||
            !AuthorizationPolicy.canPerform(actorSession, saleAction, storeId)) {
            return SaleValidationResult(isSuccess = false, errorMessage = "Session invalide ou autorisation de vente refusée au niveau Repository.")
        }
        if (cartItems.isEmpty()) {
            return SaleValidationResult(isSuccess = false, errorMessage = "Le panier est vide")
        }
        if (idempotencyKey.isBlank() ||
            database.securitySessionDao().getCurrentActiveSession()?.userId != sellerId) {
            return SaleValidationResult(isSuccess = false, errorMessage = "Identité vendeur ou clé d'idempotence invalide.")
        }

        return database.withTransaction {
            // 1. Détection des doublons idempotents
            val existingSale = saleDao.getSaleByIdempotencyKey(idempotencyKey)
            if (existingSale != null) {
                val previousItems = saleDao.getSaleItems(existingSale.id)
                val samePayload = existingSale.storeId == storeId &&
                    existingSale.sellerId == sellerId &&
                    existingSale.saleType == saleType.name &&
                    existingSale.customerId == customerId &&
                    existingSale.paymentMethod == paymentMethod.name &&
                    previousItems.size == cartItems.size &&
                    cartItems.all { requested -> previousItems.any { previous ->
                        previous.productId == requested.productId &&
                            previous.productCode == requested.productCode &&
                            previous.quantity == requested.quantity &&
                            previous.unitPrice == requested.unitPrice &&
                            previous.total == requested.total
                    } }
                return@withTransaction if (samePayload) SaleValidationResult(
                    isSuccess = true, saleId = existingSale.id, saleNumber = existingSale.saleNumber
                ) else SaleValidationResult(
                    isSuccess = false, errorMessage = "Clé d'idempotence déjà utilisée pour une vente différente."
                )
            }

            // Resolve trusted purchase costs from the canonical product table.
            // Never trust CartItem.purchasePrice: VENDEUR POS intentionally redacts it.
            val purchaseCosts = mutableMapOf<Long, Double>()
            for (item in cartItems) {
                val product = productDao.getProductById(item.productId)
                    ?: return@withTransaction SaleValidationResult(
                        isSuccess = false, errorMessage = "Produit introuvable: ${item.productName}"
                    )
                if (!product.isActive || product.code != item.productCode ||
                    !product.purchasePrice.isFinite() || product.purchasePrice < 0.0) {
                    return@withTransaction SaleValidationResult(
                        isSuccess = false, errorMessage = "Données produit invalides: ${item.productName}"
                    )
                }
                purchaseCosts[item.productId] = product.purchasePrice
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
                    val errorMessage = if (actorSession.role == UserRole.VENDEUR) {
                        "Vente à crédit refusée : plafond de crédit atteint. Contacter un ADMIN/GERANT."
                    } else {
                        val excess = (customer.currentDebt + remaining) - customer.creditLimit
                        "Plafond de crédit dépassé de ${String.format("%,.0f", excess)} Ar pour ${customer.name}. Plafond max: ${String.format("%,.0f", customer.creditLimit)} Ar"
                    }
                    return@withTransaction SaleValidationResult(
                        isSuccess = false,
                        errorMessage = errorMessage
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
                    total = item.total,
                    purchasePriceSnapshot = purchaseCosts[item.productId]
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
        if (!hasPersistedTransferRole()) throw SecurityException("Préparation de transfert réservée à ADMIN/GERANT authentifié.")
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
        if (!hasPersistedTransferRole()) throw SecurityException("Clôture de transfert réservée à ADMIN/GERANT authentifié.")
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
        if (!hasRepositoryAuthorization(ProtectedAction.STOCK_TRANSFER, listOf(fromStoreId, toStoreId))) {
            return TransferValidationResult(isSuccess = false, errorMessage = "Session invalide ou droits de transfert refusés au niveau Repository.")
        }
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
        if (!hasPersistedTransferRole()) throw SecurityException("Reprise de transfert réservée à ADMIN/GERANT authentifié.")
        val preparedRequests = transferRequestDao.getPreparedRequests()
        val results = preparedRequests.map { request ->
            if (hasRepositoryAuthorization(
                    ProtectedAction.STOCK_TRANSFER,
                    listOf(request.fromStoreId, request.toStoreId)
                )) completePreparedTransfer(request)
            else TransferValidationResult(
                isSuccess = false,
                errorMessage = "Reprise ignorée : droits absents sur les deux magasins."
            )
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
        if (!hasRepositoryAuthorization(ProtectedAction.DEBT_PAYMENT)) {
            return Result.failure(SecurityException("Paiement de dette refusé au niveau Repository."))
        }
        if (!amount.isFinite() || amount <= 0) {
            return Result.failure(IllegalArgumentException("Le montant du règlement doit être fini et supérieur à zéro"))
        }

        return database.withTransaction {
            val customer = customerDao.getCustomerById(customerId)
                ?: return@withTransaction Result.failure(IllegalArgumentException("Client introuvable"))
            if (idempotencyKey.isBlank()) return@withTransaction Result.failure(
                IllegalArgumentException("Clé d'idempotence invalide")
            )
            val existingPayment = debtPaymentDao.getPaymentByIdempotencyKey(idempotencyKey)
            if (existingPayment != null) {
                val samePayload = existingPayment.customerId == customerId &&
                    existingPayment.amountPaid == amount &&
                    existingPayment.paymentMethod == paymentMethod.name &&
                    existingPayment.note == note
                return@withTransaction if (samePayload) Result.success(existingPayment.receiptNumber)
                else Result.failure(SecurityException("Clé d'idempotence déjà utilisée pour un paiement différent."))
            }
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
        creditLimit: Double,
        storeId: String
    ): Long {
        if (!hasRepositoryAuthorization(ProtectedAction.CREATE_CUSTOMER, listOf(storeId))) {
            throw SecurityException("Création client refusée au niveau Repository.")
        }
        require(name.isNotBlank()) { "Nom client obligatoire" }
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
        require(initialStoreStocks.isNotEmpty()) { "Au moins un magasin initial autorisé est requis" }
        require(initialStoreStocks.keys.all { StoreId.parseStrict(it) != null }) { "Magasin initial inconnu" }
        if (!hasRepositoryAuthorization(ProtectedAction.CREATE_PRODUCT, initialStoreStocks.keys.toList())) {
            throw SecurityException("Création produit refusée au niveau Repository.")
        }
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
            initialStoreStocks.forEach { (storeId, quantity) ->
                stockDao.insertOrUpdateStock(
                    StoreStockEntity(storeId, productId, quantity)
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


    suspend fun updateProductDetails(
        productId: Long, storeId: String, name: String, category: String,
        purchasePrice: Double, sellingPrice: Double, minAlert: Int, unit: String
    ) {
        if (!hasRepositoryAuthorization(ProtectedAction.EDIT_PRODUCT, listOf(storeId))) {
            throw SecurityException("Modification produit refusée au niveau Repository.")
        }
        require(productId > 0L && name.isNotBlank() && category.isNotBlank() && unit.isNotBlank()) {
            "Informations produit obligatoires."
        }
        require(purchasePrice.isFinite() && purchasePrice >= 0.0) { "Prix d'achat invalide." }
        require(sellingPrice.isFinite() && sellingPrice > 0.0) { "Prix de vente invalide." }
        require(minAlert >= 0) { "Seuil d'alerte invalide." }
        database.withTransaction {
            val product = productDao.getProductById(productId) ?: throw IllegalArgumentException("Produit introuvable.")
            require(product.isActive) { "Produit déjà archivé." }
            val updated = product.copy(
                name = name.trim(), category = category.trim(), purchasePrice = purchasePrice,
                sellingPrice = sellingPrice, minStockAlert = minAlert, unit = unit.trim()
            )
            productDao.updateProduct(updated)
            val now = System.currentTimeMillis()
            val payload = JSONObject().put("productId", productId).put("code", product.code)
                .put("name", updated.name).put("category", updated.category)
                .put("sellingPrice", updated.sellingPrice).put("purchasePrice", updated.purchasePrice)
                .put("minStockAlert", updated.minStockAlert).put("unit", updated.unit)
                .put("timestamp", now).toString()
            pendingSyncDao.insertOperation(PendingSyncOperationEntity(
                mutationId = UUID.randomUUID().toString(), entityType = "PRODUCT", action = "UPDATE",
                payloadJson = payload, idempotencyKey = "PRODUCT-UPDATE-$productId-" + UUID.randomUUID(),
                status = "PENDING", createdAt = now
            ))
        }
    }

    suspend fun adjustStoreStock(
        productId: Long, storeId: String, newQuantity: Int, reason: String, operatorName: String
    ) {
        if (!hasRepositoryAuthorization(ProtectedAction.ADJUST_STOCK, listOf(storeId))) {
            throw SecurityException("Ajustement de stock refusé au niveau Repository.")
        }
        require(productId > 0L && newQuantity >= 0) { "Quantité de stock invalide." }
        val normalizedReason = reason.trim()
        require(normalizedReason.isNotBlank()) { "Le motif d'ajustement est obligatoire." }
        database.withTransaction {
            val product = productDao.getProductById(productId) ?: throw IllegalArgumentException("Produit introuvable.")
            require(product.isActive) { "Produit archivé : ajustement interdit." }
            val existing = stockDao.getStock(productId, storeId)
            val previous = existing?.quantity ?: 0
            require(previous >= 0) { "Stock existant négatif : ajustement bloqué." }
            val delta = newQuantity.toLong() - previous.toLong()
            require(delta in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Écart de stock trop important." }
            val now = System.currentTimeMillis()
            if (existing == null) {
                stockDao.insertOrUpdateStock(StoreStockEntity(storeId, productId, newQuantity, now))
            } else {
                check(stockDao.updateQuantity(productId, storeId, newQuantity, now) == 1) {
                    "Mise à jour du stock impossible."
                }
            }
            if (delta != 0L) {
                val movementId = UUID.randomUUID().toString()
                movementDao.insertMovement(StockMovementEntity(
                    id = movementId,
                    movementNumber = "ADJ-" + UUID.randomUUID().toString().take(8).uppercase(),
                    productId = productId, productName = product.name,
                    fromStoreId = if (delta < 0L) storeId else null,
                    toStoreId = if (delta > 0L) storeId else null,
                    quantity = kotlin.math.abs(delta).toInt(), movementType = "AJUSTEMENT",
                    referenceId = movementId, reason = normalizedReason, performedBy = operatorName,
                    createdAt = now
                ))
            }
            val key = "STOCK-ADJUST-$productId-$storeId-$now-" + UUID.randomUUID()
            val payload = JSONObject().put("productId", productId).put("productCode", product.code)
                .put("storeId", storeId).put("previousQuantity", previous).put("newQuantity", newQuantity)
                .put("delta", delta).put("reason", normalizedReason).put("operator", operatorName)
                .put("idempotencyKey", key).put("timestamp", now).toString()
            pendingSyncDao.insertOperation(PendingSyncOperationEntity(
                mutationId = UUID.randomUUID().toString(), entityType = "STOCK_ADJUSTMENT",
                action = "UPDATE", payloadJson = payload, idempotencyKey = key,
                status = "PENDING", createdAt = now
            ))
        }
    }

    suspend fun archiveProduct(productId: Long, storeId: String) {
        if (!hasRepositoryAuthorization(ProtectedAction.DELETE_PRODUCT, listOf(storeId))) {
            throw SecurityException("Suppression produit réservée à ADMIN.")
        }
        require(productId > 0L) { "Produit invalide." }
        database.withTransaction {
            val product = productDao.getProductById(productId) ?: throw IllegalArgumentException("Produit introuvable.")
            require(product.isActive) { "Produit déjà archivé." }
            productDao.softDeleteProduct(productId)
            val now = System.currentTimeMillis()
            val payload = JSONObject().put("productId", productId).put("code", product.code)
                .put("timestamp", now).toString()
            pendingSyncDao.insertOperation(PendingSyncOperationEntity(
                mutationId = UUID.randomUUID().toString(), entityType = "PRODUCT", action = "DELETE",
                payloadJson = payload, idempotencyKey = "PRODUCT-DELETE-$productId-" + UUID.randomUUID(),
                status = "PENDING", createdAt = now
            ))
        }
    }

    suspend fun updateUserPreference(session: UserSession, storeId: String, operator: String) {
        database.withTransaction {
            if (!isPersistedSessionAuthorizedForStore(session, storeId)) {
                throw SecurityException("Modification des préférences refusée : session persistée ou magasin invalide.")
            }
            val current = userPreferenceDao.getPreferences() ?: UserPreferenceEntity()
            userPreferenceDao.savePreferences(
                current.copy(
                    activeRole = session.role.code,
                    activeStoreId = storeId,
                    operatorName = operator
                )
            )
        }
    }

    suspend fun updateStagingApiUrl(url: String) {
        if (!hasRepositoryAuthorization(ProtectedAction.SYSTEM_CONFIGURATION)) {
            throw SecurityException("Configuration système réservée à ADMIN.")
        }
        require(url.startsWith("https://")) { "URL STAGING HTTPS obligatoire" }
        val current = syncCursorDao.getCursor() ?: SyncCursorEntity()
        syncCursorDao.saveCursor(current.copy(stagingApiUrl = url))
    }

    suspend fun toggleNetworkSimulation(isOnline: Boolean) {
        if (!hasRepositoryAuthorization(ProtectedAction.SYSTEM_CONFIGURATION)) {
            throw SecurityException("Configuration système réservée à ADMIN.")
        }
        val current = syncCursorDao.getCursor() ?: SyncCursorEntity()
        syncCursorDao.saveCursor(current.copy(isSimulatedOnline = isOnline))
    }

    suspend fun resetDatabase(): Result<Unit> {
        if (!hasRepositoryAuthorization(ProtectedAction.SYSTEM_CONFIGURATION)) {
            return Result.failure(SecurityException("Réinitialisation réservée à ADMIN authentifié."))
        }
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
