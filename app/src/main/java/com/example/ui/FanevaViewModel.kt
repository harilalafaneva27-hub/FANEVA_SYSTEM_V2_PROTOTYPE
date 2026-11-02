package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.*
import com.example.data.repository.AuthRepository
import com.example.data.repository.AuthResult
import com.example.data.repository.FanevaRepository
import com.example.data.repository.SyncPushResult
import com.example.data.repository.SyncRepository
import com.example.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface AuthUiState {
    data object Loading : AuthUiState
    data object SignedOut : AuthUiState
    data class SignedIn(val session: UserSession) : AuthUiState
}

data class ProductWithStock(
    val product: ProductEntity,
    val activeStoreQuantity: Int,
    val stocksByStore: Map<String, Int> // "A1" to 140, "A2" to 280, etc.
)

class FanevaViewModel(
    private val fanevaRepository: FanevaRepository,
    private val syncRepository: SyncRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    val sellerAccounts: StateFlow<List<SellerAccountSummary>> = authRepository.sellers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // User preferences & role
    private val _currentRole = MutableStateFlow(UserRole.ADMIN)
    val currentRole: StateFlow<UserRole> = _currentRole.asStateFlow()

    private val _activeStoreId = MutableStateFlow("A1")
    val activeStoreId: StateFlow<String> = _activeStoreId.asStateFlow()

    private val _operatorName = MutableStateFlow("Harilala R. (Admin)")
    val operatorName: StateFlow<String> = _operatorName.asStateFlow()

    // Data streams from Room
    val allProducts: StateFlow<List<ProductEntity>> = fanevaRepository.allProducts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allStocks: StateFlow<List<StoreStockEntity>> = fanevaRepository.allStocks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allCustomers: StateFlow<List<CustomerEntity>> = fanevaRepository.allCustomers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSales: StateFlow<List<SaleEntity>> = fanevaRepository.allSales
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMovements: StateFlow<List<StockMovementEntity>> = fanevaRepository.allMovements
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingOperations: StateFlow<List<PendingSyncOperationEntity>> = syncRepository.pendingOperations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingCount: StateFlow<Int> = syncRepository.pendingCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val syncCursor: StateFlow<SyncCursorEntity?> = syncRepository.syncCursor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isOnline: StateFlow<Boolean> = syncRepository.isOnlineFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // Combined Products with Stocks for the active store and all stores
    val productsWithStock: StateFlow<List<ProductWithStock>> = combine(
        allProducts,
        allStocks,
        activeStoreId
    ) { products, stocks, storeId ->
        val stockMap = stocks.groupBy { it.productId }
        products.map { product ->
            val productStocks = stockMap[product.id] ?: emptyList()
            val byStore = productStocks.associate { it.storeId to it.quantity }
            val inActiveStore = byStore[storeId] ?: 0
            ProductWithStock(
                product = product,
                activeStoreQuantity = inActiveStore,
                stocksByStore = byStore
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // POS Cart
    private val _cartItems = MutableStateFlow<List<CartItem>>(emptyList())
    val cartItems: StateFlow<List<CartItem>> = _cartItems.asStateFlow()
    private val _saleIdempotencyKey = MutableStateFlow<String?>(null)
    private val _debtPaymentIdempotencyKey = MutableStateFlow<String?>(null)

    // Search and filters
    val searchQuery = MutableStateFlow("")
    val selectedCategory = MutableStateFlow<String?>(null)

    // Sync progress
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _transferRecoveryMessage = MutableStateFlow<String?>(null)
    val transferRecoveryMessage: StateFlow<String?> =
        _transferRecoveryMessage.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.ensureInitialAdmin()
            _authState.value = AuthUiState.SignedOut
        }
        // Recover durable transfers left in PREPARED after interruption.
        viewModelScope.launch {
            try {
                val recoveryResults = fanevaRepository.recoverPreparedTransfers()
                val failedResult = recoveryResults.firstOrNull { !it.isSuccess }
                _transferRecoveryMessage.value = failedResult?.errorMessage
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The failed DB transaction leaves the request PREPARED for retry.
                _transferRecoveryMessage.value =
                    e.message ?: "La reprise des transferts en attente a échoué."
            }
        }

        // Preferences are convenience settings only, never authentication evidence.
        viewModelScope.launch {
            fanevaRepository.userPreferences.collect { pref ->
                if (pref != null) {
                    _activeStoreId.value = pref.activeStoreId
                }
            }
        }
    }

    fun authenticateAdmin(code: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            when (val result = authRepository.authenticateAdmin(code.toCharArray())) {
                is AuthResult.Success -> {
                    applySession(result.session)
                    onResult(null)
                }
                is AuthResult.Failure -> onResult(result.message)
            }
        }
    }

    fun authenticateSeller(username: String, password: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            when (val result = authRepository.authenticateSeller(username, password.toCharArray())) {
                is AuthResult.Success -> {
                    applySession(result.session)
                    onResult(null)
                }
                is AuthResult.Failure -> onResult(result.message)
            }
        }
    }

    fun logout() {
        _authState.value = AuthUiState.SignedOut
        _currentRole.value = UserRole.VENDEUR
        _operatorName.value = ""
    }

    fun createSeller(username: String, displayName: String, password: String, onResult: (String?) -> Unit) {
        if (!isAdminAuthenticated()) {
            onResult("Action réservée à une session Admin authentifiée")
            return
        }
        viewModelScope.launch {
            val result = authRepository.createSeller(username, displayName, password.toCharArray())
            onResult(result.exceptionOrNull()?.message)
        }
    }

    private fun applySession(session: UserSession) {
        _authState.value = AuthUiState.SignedIn(session)
        _currentRole.value = session.role
        _activeStoreId.value = session.defaultStoreId
        _operatorName.value = session.name
        viewModelScope.launch {
            fanevaRepository.updateUserPreference(session.role.code, session.defaultStoreId, session.name)
        }
    }

    fun isAdminAuthenticated(): Boolean =
        (_authState.value as? AuthUiState.SignedIn)?.session?.role == UserRole.ADMIN

    // Role switcher
    fun switchRole(role: UserRole) {
        // Les rôles de démonstration ne peuvent plus modifier une session authentifiée.
        if (isAdminAuthenticated() && role == UserRole.ADMIN) return
    }

    // Active store switcher (A1..A5)
    fun switchActiveStore(storeId: String) {
        _activeStoreId.value = storeId
        // Update cart items availability in the newly selected store
        _cartItems.update { items ->
            items.map { item ->
                val available = productsWithStock.value.find { it.product.id == item.productId }?.stocksByStore?.get(storeId) ?: 0
                item.copy(availableInActiveStore = available)
            }
        }
        viewModelScope.launch {
            fanevaRepository.updateUserPreference(_currentRole.value.code, storeId, _operatorName.value)
        }
    }

    // Cart operations
    fun addToCart(product: ProductEntity) {
        val available = productsWithStock.value.find { it.product.id == product.id }?.activeStoreQuantity ?: 0
        if (available <= 0) return

        _cartItems.update { current ->
            if (current.isEmpty()) _saleIdempotencyKey.value = null
            val existing = current.find { it.productId == product.id }
            if (existing != null) {
                if (existing.quantity < available) {
                    current.map {
                        if (it.productId == product.id) it.copy(quantity = it.quantity + 1) else it
                    }
                } else {
                    current // Cannot exceed available stock
                }
            } else {
                current + CartItem(
                    productId = product.id,
                    productCode = product.code,
                    productName = product.name,
                    unit = product.unit,
                    unitPrice = product.sellingPrice,
                    purchasePrice = product.purchasePrice,
                    quantity = 1,
                    availableInActiveStore = available
                )
            }
        }
    }

    fun updateCartQuantity(productId: Long, newQuantity: Int) {
        if (newQuantity <= 0) {
            removeFromCart(productId)
            return
        }
        _cartItems.update { current ->
            current.map { item ->
                if (item.productId == productId) {
                    val capped = newQuantity.coerceAtMost(item.availableInActiveStore)
                    item.copy(quantity = capped)
                } else {
                    item
                }
            }
        }
    }

    fun removeFromCart(productId: Long) {
        _cartItems.update { it.filter { item -> item.productId != productId } }
    }

    fun clearCart() {
        _cartItems.value = emptyList()
        _saleIdempotencyKey.value = null
    }

    // Process Sale with strict constraints
    fun executeSale(
        saleType: SaleType,
        paymentMethod: PaymentMethod,
        customerId: Long?,
        customerName: String?,
        paidAmount: Double,
        onResult: (SaleValidationResult) -> Unit
    ) {
        viewModelScope.launch {
            val idempotencyKey = _saleIdempotencyKey.value ?: UUID.randomUUID().toString().also { _saleIdempotencyKey.value = it }
            val result = fanevaRepository.processSale(
                storeId = _activeStoreId.value,
                cartItems = _cartItems.value,
                saleType = saleType,
                paymentMethod = paymentMethod,
                customerId = customerId,
                customerName = customerName,
                sellerId = "USR-${_currentRole.value.code}",
                sellerName = _operatorName.value,
                paidAmount = paidAmount,
                idempotencyKey = idempotencyKey
            )
            if (result.isSuccess) {
                clearCart()
            }
            onResult(result)
        }
    }

    // Inter-store stock transfer
    fun executeTransfer(
        productId: Long,
        productName: String,
        fromStore: String,
        toStore: String,
        quantity: Int,
        reason: String,
        onResult: (TransferValidationResult) -> Unit
    ) {
        viewModelScope.launch {
            val idempotencyKey = try {
                fanevaRepository.getOrCreateTransferIdempotencyKey()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(
                    TransferValidationResult(
                        isSuccess = false,
                        errorMessage = e.message
                            ?: "Impossible de préparer le transfert."
                    )
                )
                return@launch
            }

            val result = try {
                fanevaRepository.transferStock(
                    productId = productId,
                    productName = productName,
                    fromStoreId = fromStore,
                    toStoreId = toStore,
                    quantity = quantity,
                    operatorName = _operatorName.value,
                    reason = reason,
                    idempotencyKey = idempotencyKey
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the key so a retry uses the same request identity.
                onResult(
                    TransferValidationResult(
                        isSuccess = false,
                        errorMessage = e.message
                            ?: "Erreur technique. Le transfert pourra être repris."
                    )
                )
                return@launch
            }

            // The transfer result is already committed. If clearing the key
            // fails, the repository can reconcile it from the durable ledger.
            try {
                fanevaRepository.clearTransferIdempotencyKey(idempotencyKey)
                _transferRecoveryMessage.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _transferRecoveryMessage.value =
                    e.message ?: "La clé du transfert sera réconciliée ultérieurement."
            }

            onResult(result)
        }
    }

    // Customer Debt Settlement
    fun recordDebtPayment(
        customerId: Long,
        amount: Double,
        method: PaymentMethod,
        note: String,
        onResult: (Result<String>) -> Unit
    ) {
        viewModelScope.launch {
            val idempotencyKey = _debtPaymentIdempotencyKey.value ?: UUID.randomUUID().toString().also { _debtPaymentIdempotencyKey.value = it }
            val result = fanevaRepository.recordDebtPayment(
                customerId = customerId,
                amount = amount,
                paymentMethod = method,
                note = note,
                operatorName = _operatorName.value,
                idempotencyKey = idempotencyKey
            )
            if (result.isSuccess) _debtPaymentIdempotencyKey.value = null
            onResult(result)
        }
    }

    // New Customer
    fun addNewCustomer(
        name: String,
        phone: String,
        address: String,
        creditLimit: Double,
        onComplete: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val id = fanevaRepository.addNewCustomer(name, phone, address, creditLimit)
            onComplete(id)
        }
    }

    // New Product (Admin only)
    fun addNewProduct(
        code: String,
        name: String,
        category: String,
        purchasePrice: Double,
        sellingPrice: Double,
        minAlert: Int,
        unit: String,
        initialStocks: Map<String, Int>,
        onComplete: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val id = fanevaRepository.addNewProduct(
                code, name, category, purchasePrice, sellingPrice, minAlert, unit, initialStocks
            )
            onComplete(id)
        }
    }

    // Synchronization
    fun triggerManualSync(onComplete: (SyncPushResult) -> Unit) {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Synchronisation des mutations vers Cloudflare Staging..."
            val result = syncRepository.triggerSync()
            _isSyncing.value = false
            _syncMessage.value = when (result) {
                is SyncPushResult.Success -> "${result.countSynced} opération(s) synchronisée(s). Curseur mis à jour à ${result.updatedCursor}."
                is SyncPushResult.Offline -> result.reason
                is SyncPushResult.ServerNotConfigured -> result.reason
                is SyncPushResult.PartialError -> "Avertissement : ${result.syncedCount} réussis, ${result.failedCount} échecs : ${result.error}"
            }
            onComplete(result)
        }
    }

    fun purgeSyncedOutbox() {
        viewModelScope.launch {
            syncRepository.purgeSyncedOperations()
        }
    }

    fun toggleNetwork(isOnline: Boolean) {
        viewModelScope.launch {
            syncRepository.setSimulatedNetwork(isOnline)
        }
    }

    fun updateStagingUrl(url: String) {
        viewModelScope.launch {
            syncRepository.setStagingUrl(url)
        }
    }

    fun resetDemoData() {
        viewModelScope.launch {
            fanevaRepository.resetDatabase()
            clearCart()
        }
    }

    // AI Insights (Computed locally, read-only analytics)
    val aiInsights: StateFlow<List<AIInsight>> = combine(
        productsWithStock,
        allCustomers,
        activeStoreId
    ) { products, customers, storeId ->
        val list = mutableListOf<AIInsight>()

        // 1. Ruptures et alertes de stock
        products.forEach { pws ->
            val activeQty = pws.activeStoreQuantity
            val alert = pws.product.minStockAlert
            if (activeQty <= 0) {
                // Trouver si un autre magasin a du surplus
                val surplusStore = pws.stocksByStore.entries
                    .filter { it.key != storeId && it.value > alert * 2 }
                    .maxByOrNull { it.value }

                if (surplusStore != null) {
                    list.add(
                        AIInsight(
                            title = "Rupture critique & Transfert suggéré",
                            category = "TRANSFERT",
                            severity = "URGENT",
                            summary = "${pws.product.name} est en rupture dans ${storeId} (0 ${pws.product.unit}), mais disponible en excédent dans ${surplusStore.key} (${surplusStore.value} ${pws.product.unit}).",
                            recommendation = "Initier un transfert de ${surplusStore.value / 2} ${pws.product.unit} depuis ${surplusStore.key} vers ${storeId} pour restaurer la disponibilité immédiate.",
                            targetStore = storeId
                        )
                    )
                } else {
                    list.add(
                        AIInsight(
                            title = "Rupture générale de stock",
                            category = "RUPTURE",
                            severity = "URGENT",
                            summary = "${pws.product.name} est totalement épuisé dans le magasin ${storeId}.",
                            recommendation = "Commander un réapprovisionnement fournisseur prioritaire (seuil alerte : $alert ${pws.product.unit}).",
                            targetStore = storeId
                        )
                    )
                }
            } else if (activeQty <= alert) {
                list.add(
                    AIInsight(
                        title = "Stock faible dans $storeId",
                        category = "RUPTURE",
                        severity = "WARNING",
                        summary = "${pws.product.name} atteint le seuil critique ($activeQty / $alert ${pws.product.unit}).",
                        recommendation = "Surveiller les ventes du jour et prévoir une demande de réassort.",
                        targetStore = storeId
                    )
                )
            }
        }

        // 2. Risque de créances / Dettes
        customers.forEach { cust ->
            if (cust.creditLimit > 0) {
                val ratio = (cust.currentDebt / cust.creditLimit) * 100
                if (ratio >= 80.0) {
                    list.add(
                        AIInsight(
                            title = "Plafond crédit proche de la saturation",
                            category = "CREDIT",
                            severity = "WARNING",
                            summary = "${cust.name} a utilisé ${String.format("%.0f", ratio)}% de sa limite autorisée (Dette: ${String.format("%,.0f", cust.currentDebt)} Ar / Max: ${String.format("%,.0f", cust.creditLimit)} Ar).",
                            recommendation = "Bloquer les nouvelles ventes à crédit avant encaissement d'un acompte minimal."
                        )
                    )
                }
            }
        }

        if (list.isEmpty()) {
            list.add(
                AIInsight(
                    title = "Stocks & Encaissements optimaux",
                    category = "OPPORTUNITE",
                    severity = "INFO",
                    summary = "Toutes les métriques opérationnelles du magasin $storeId sont dans les tolérances recommandées.",
                    recommendation = "Continuer le suivi régulier et synchroniser l'Outbox à la fin du service."
                )
            )
        }

        list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
