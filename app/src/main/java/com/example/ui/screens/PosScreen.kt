package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.PaymentMethod
import com.example.domain.model.SaleType
import com.example.domain.model.SaleValidationResult
import com.example.domain.model.StoreId
import com.example.ui.FanevaViewModel
import com.example.ui.components.RoleBadge
import com.example.ui.components.StoreSelectorChipRow
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosScreen(
    viewModel: FanevaViewModel,
    onNavigateToHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeStoreId by viewModel.activeStoreId.collectAsStateWithLifecycle()
    val currentRole by viewModel.currentRole.collectAsStateWithLifecycle()
    val productsWithStock by viewModel.productsWithStock.collectAsStateWithLifecycle()
    val cartItems by viewModel.cartItems.collectAsStateWithLifecycle()
    val allCustomers by viewModel.allCustomers.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showCheckoutDialog by remember { mutableStateOf(false) }
    var showCartSheet by remember { mutableStateOf(false) }
    var lastSaleSuccess by remember { mutableStateOf<SaleValidationResult?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Categories
    val categories = remember(productsWithStock) {
        listOf("Tous") + productsWithStock.map { it.product.category }.distinct()
    }

    // Filtered products
    val filteredProducts = remember(productsWithStock, searchQuery, selectedCategory) {
        productsWithStock.filter { pws ->
            val matchQuery = searchQuery.isBlank() ||
                    pws.product.name.contains(searchQuery, ignoreCase = true) ||
                    pws.product.code.contains(searchQuery, ignoreCase = true)
            val matchCat = selectedCategory == null || selectedCategory == "Tous" || pws.product.category == selectedCategory
            matchQuery && matchCat
        }
    }

    val totalCartAmount = cartItems.sumOf { it.total }
    val totalCartCount = cartItems.sumOf { it.quantity }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Caisse & Point de Vente", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            RoleBadge(role = currentRole)
                        }
                        Text(
                            text = "Magasin actif : ${StoreId.fromCode(activeStoreId).displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = "Historique des ventes")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            if (cartItems.isNotEmpty()) {
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "$totalCartCount article(s) au panier",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${String.format("%,.0f", totalCartAmount)} Ar",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showCartSheet = true },
                                modifier = Modifier.testTag("view_cart_button")
                            ) {
                                Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Panier ($totalCartCount)")
                            }
                            Button(
                                onClick = { showCheckoutDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = FanevaEmerald),
                                modifier = Modifier.testTag("checkout_button")
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Encaisser", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Store selector
            StoreSelectorChipRow(
                selectedStoreId = activeStoreId,
                onStoreSelected = { viewModel.switchActiveStore(it) }
            )

            // Search input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("pos_search_field"),
                placeholder = { Text("Rechercher un produit (nom, code, catégorie)...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Effacer")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Category filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                categories.forEach { cat ->
                    val isSelected = (selectedCategory == null && cat == "Tous") || selectedCategory == cat
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = if (cat == "Tous") null else cat },
                        label = { Text(cat, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }

            // Products Catalog List
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredProducts, key = { it.product.id }) { item ->
                    val inStock = item.activeStoreQuantity
                    val cartItem = cartItems.find { it.productId == item.product.id }
                    val quantityInCart = cartItem?.quantity ?: 0
                    val isOutOfStock = inStock <= 0

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("product_item_${item.product.code}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = item.product.code,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = item.product.category,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = item.product.name,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "${String.format("%,.0f", item.product.sellingPrice)} Ar / ${item.product.unit}",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 13.sp
                                    )
                                    // Stock badge in active store
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isOutOfStock) FanevaRed.copy(alpha = 0.12f)
                                        else if (inStock <= item.product.minStockAlert) FanevaAmber.copy(alpha = 0.15f)
                                        else FanevaEmerald.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = if (isOutOfStock) "Rupture ($activeStoreId)" else "Stock: $inStock (${item.product.unit})",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isOutOfStock) FanevaRed else if (inStock <= item.product.minStockAlert) FanevaAmberDark else FanevaEmeraldDark,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            // Add / Adjust in Cart
                            if (quantityInCart == 0) {
                                Button(
                                    onClick = { viewModel.addToCart(item.product) },
                                    enabled = !isOutOfStock,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.testTag("add_to_cart_${item.product.code}")
                                ) {
                                    Icon(Icons.Default.AddShoppingCart, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Ajouter", fontSize = 12.sp)
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = { viewModel.updateCartQuantity(item.product.id, quantityInCart - 1) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Moins", tint = FanevaRed)
                                    }
                                    Text(
                                        text = "$quantityInCart",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                    IconButton(
                                        onClick = { viewModel.updateCartQuantity(item.product.id, quantityInCart + 1) },
                                        enabled = quantityInCart < inStock,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.AddCircleOutline, contentDescription = "Plus", tint = FanevaEmerald)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Cart Details Dialog
    if (showCartSheet) {
        AlertDialog(
            onDismissRequest = { showCartSheet = false },
            title = { Text("Contenu du Panier ($activeStoreId)", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    cartItems.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.productName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("${item.quantity} x ${String.format("%,.0f", item.unitPrice)} Ar = ${String.format("%,.0f", item.total)} Ar", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { viewModel.removeFromCart(item.productId) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = FanevaRed, modifier = Modifier.size(18.dp))
                            }
                        }
                        HorizontalDivider()
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total Général :", fontWeight = FontWeight.Bold)
                        Text("${String.format("%,.0f", totalCartAmount)} Ar", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showCartSheet = false
                    showCheckoutDialog = true
                }) {
                    Text("Passer à l'encaissement")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCartSheet = false }) {
                    Text("Fermer")
                }
            }
        )
    }

    // Checkout Modal Dialog (Comptant vs Crédit)
    if (showCheckoutDialog) {
        var selectedSaleType by remember { mutableStateOf(SaleType.CASH) }
        var selectedPaymentMethod by remember { mutableStateOf(PaymentMethod.ESPECES) }
        var selectedCustomerId by remember { mutableStateOf<Long?>(allCustomers.firstOrNull()?.id) }
        var upfrontPaidText by remember { mutableStateOf("") }
        var isProcessing by remember { mutableStateOf(false) }
        var checkoutError by remember { mutableStateOf<String?>(null) }

        val selectedCustomer = allCustomers.find { it.id == selectedCustomerId }

        AlertDialog(
            onDismissRequest = { if (!isProcessing) showCheckoutDialog = false },
            title = {
                Text("Validation Encaissement", fontWeight = FontWeight.Bold)
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(
                            text = "Montant Total : ${String.format("%,.0f", totalCartAmount)} Ar",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Sale Type Selector
                    item {
                        Text("Type de Vente :", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = selectedSaleType == SaleType.CASH,
                                onClick = { selectedSaleType = SaleType.CASH },
                                label = { Text("Comptant") },
                                leadingIcon = { Icon(Icons.Default.AttachMoney, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = selectedSaleType == SaleType.CREDIT,
                                onClick = { selectedSaleType = SaleType.CREDIT },
                                label = { Text("À Crédit") },
                                leadingIcon = { Icon(Icons.Default.CreditCard, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Payment Method (Espèces, Mvola, Airtel Money...)
                    item {
                        Text("Moyen de paiement :", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PaymentMethod.entries.forEach { method ->
                                FilterChip(
                                    selected = selectedPaymentMethod == method,
                                    onClick = { selectedPaymentMethod = method },
                                    label = { Text(method.label, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    // If Credit: Customer Picker & Debt checks
                    if (selectedSaleType == SaleType.CREDIT) {
                        item {
                            Text("Sélection du Client (Obligatoire) :", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                allCustomers.forEach { customer ->
                                    val isSelected = customer.id == selectedCustomerId
                                    Card(
                                        shape = RoundedCornerShape(8.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedCustomerId = customer.id }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(customer.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                                Text("Plafond: ${String.format("%,.0f", customer.creditLimit)} Ar", fontSize = 10.sp)
                                            }
                                            Text(
                                                "Dette: ${String.format("%,.0f", customer.currentDebt)} Ar",
                                                color = if (customer.currentDebt > 0) FanevaRed else FanevaEmerald,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            OutlinedTextField(
                                value = upfrontPaidText,
                                onValueChange = { upfrontPaidText = it },
                                label = { Text("Acompte versé immédiatement (Ar)") },
                                placeholder = { Text("0") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (selectedCustomer != null) {
                            item {
                                val upfront = upfrontPaidText.toDoubleOrNull() ?: 0.0
                                val remainingDebt = (totalCartAmount - upfront).coerceAtLeast(0.0)
                                val newTotalDebt = selectedCustomer.currentDebt + remainingDebt
                                val isOverLimit = newTotalDebt > selectedCustomer.creditLimit

                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isOverLimit) FanevaRed.copy(alpha = 0.1f) else FanevaEmerald.copy(alpha = 0.1f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("Nouvelle dette client : ${String.format("%,.0f", newTotalDebt)} Ar", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        if (isOverLimit) {
                                            Text("Attention : dépassement du plafond autorisé de ${String.format("%,.0f", selectedCustomer.creditLimit)} Ar", color = FanevaRed, fontSize = 11.sp)
                                        } else {
                                            Text("Dans la limite autorisée", color = FanevaEmeraldDark, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (checkoutError != null) {
                        item {
                            Text(
                                text = checkoutError ?: "",
                                color = FanevaRed,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isProcessing = true
                        checkoutError = null
                        val upfront = upfrontPaidText.toDoubleOrNull() ?: 0.0

                        viewModel.executeSale(
                            saleType = selectedSaleType,
                            paymentMethod = selectedPaymentMethod,
                            customerId = if (selectedSaleType == SaleType.CREDIT) selectedCustomerId else null,
                            customerName = if (selectedSaleType == SaleType.CREDIT) selectedCustomer?.name else null,
                            paidAmount = if (selectedSaleType == SaleType.CASH) totalCartAmount else upfront,
                            onResult = { result ->
                                isProcessing = false
                                if (result.isSuccess) {
                                    showCheckoutDialog = false
                                    lastSaleSuccess = result
                                } else {
                                    checkoutError = result.errorMessage
                                }
                            }
                        )
                    },
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = FanevaEmerald),
                    modifier = Modifier.testTag("confirm_sale_button")
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    } else {
                        Text("Confirmer la vente", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCheckoutDialog = false }, enabled = !isProcessing) {
                    Text("Annuler")
                }
            }
        )
    }

    // Success Receipt Dialog
    if (lastSaleSuccess != null) {
        val res = lastSaleSuccess!!
        AlertDialog(
            onDismissRequest = { lastSaleSuccess = null },
            icon = {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = FanevaEmerald, modifier = Modifier.size(48.dp))
            },
            title = {
                Text("Vente Validée avec Succès", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("N° Transaction : ${res.saleNumber ?: ""}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Magasin : ${StoreId.fromCode(activeStoreId).displayName}", fontSize = 12.sp)
                    Text("Le stock a été décrémenté et l'opération a été sécurisée dans la file de synchronisation locale (Outbox).", fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(onClick = { lastSaleSuccess = null }) {
                    Text("Terminer")
                }
            }
        )
    }
}
