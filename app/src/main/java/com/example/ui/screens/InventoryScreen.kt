package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.StoreId
import com.example.domain.model.UserRole
import com.example.ui.FanevaViewModel
import com.example.ui.components.RoleBadge
import com.example.ui.components.StoreSelectorChipRow
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    viewModel: FanevaViewModel,
    onNavigateToTransfers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeStoreId by viewModel.activeStoreId.collectAsStateWithLifecycle()
    val currentRole by viewModel.currentRole.collectAsStateWithLifecycle()
    val productsWithStock by viewModel.productsWithStock.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showAddProductDialog by remember { mutableStateOf(false) }
    var productToEdit by remember { mutableStateOf<com.example.data.local.entity.ProductEntity?>(null) }
    var stockToAdjust by remember { mutableStateOf<com.example.ui.ProductWithStock?>(null) }
    var productToArchive by remember { mutableStateOf<com.example.data.local.entity.ProductEntity?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var expandedProductId by remember { mutableStateOf<Long?>(null) }

    val categories = remember(productsWithStock) {
        listOf("Tous") + productsWithStock.map { it.product.category }.distinct()
    }

    val filteredProducts = remember(productsWithStock, searchQuery, selectedCategory) {
        productsWithStock.filter { pws ->
            val matchQuery = searchQuery.isBlank() ||
                    pws.product.name.contains(searchQuery, ignoreCase = true) ||
                    pws.product.code.contains(searchQuery, ignoreCase = true)
            val matchCat = selectedCategory == null || selectedCategory == "Tous" || pws.product.category == selectedCategory
            matchQuery && matchCat
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Gestion des Stocks & Produits", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            RoleBadge(role = currentRole)
                        }
                        Text(
                            text = if (currentRole.canViewCostAndMargin) "Mode Gestionnaire : Prix d'achat & Marges visibles"
                            else "Mode Vendeur : Prix public uniquement (Marges protégées)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToTransfers) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "Transferts")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            if (currentRole.canManageInventory) {
                FloatingActionButton(
                    onClick = { showAddProductDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("add_product_fab")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Ajouter un produit")
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
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Rechercher un produit...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Category Chips
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

            // Product List
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredProducts, key = { it.product.id }) { pws ->
                    val isExpanded = expandedProductId == pws.product.id
                    val totalAllStores = pws.stocksByStore.values.sum()
                    val activeStock = pws.activeStoreQuantity
                    val isLow = activeStock <= pws.product.minStockAlert

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedProductId = if (isExpanded) null else pws.product.id }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(pws.product.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(pws.product.category, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(pws.product.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (activeStock == 0) FanevaRed.copy(alpha = 0.12f)
                                    else if (isLow) FanevaAmber.copy(alpha = 0.15f)
                                    else FanevaEmerald.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = "$activeStock ${pws.product.unit} ($activeStoreId)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (activeStock == 0) FanevaRed else if (isLow) FanevaAmberDark else FanevaEmeraldDark,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Pricing & Confidentiality Control
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Prix de Vente Public : ${String.format("%,.0f", pws.product.sellingPrice)} Ar",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 13.sp
                                    )

                                    // RÈGLE CRITIQUE : Seul l'Admin ou Gérant voit le prix d'achat et la marge
                                    if (currentRole.canViewCostAndMargin) {
                                        val margin = pws.product.sellingPrice - pws.product.purchasePrice
                                        val marginPct = if (pws.product.purchasePrice > 0) (margin / pws.product.purchasePrice) * 100 else 0.0
                                        Text(
                                            text = "Achat : ${String.format("%,.0f", pws.product.purchasePrice)} Ar • Marge : +${String.format("%,.0f", margin)} Ar (+${String.format("%.0f", marginPct)}%)",
                                            fontSize = 11.sp,
                                            color = FanevaEmeraldDark,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    } else {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.padding(top = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(
                                                text = "Coût d'achat & Marge masqués (Profil Vendeur)",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Détails stocks",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Expanded Multi-Store breakdown (A1..A5)
                            AnimatedVisibility(visible = isExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp)
                                ) {
                                    HorizontalDivider()
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Répartition du stock dans tous les magasins :",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        StoreId.entries.forEach { store ->
                                            val qty = pws.stocksByStore[store.code] ?: 0
                                            val isCurrent = store.code == activeStoreId
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                                modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                                            ) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    modifier = Modifier.padding(vertical = 6.dp)
                                                ) {
                                                    Text(store.code, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                    Text(
                                                        text = "$qty",
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (qty == 0) FanevaRed else MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Stock cumulé réseau (A1-A5) : $totalAllStores ${pws.product.unit}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    if (currentRole.canManageInventory) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = { productToEdit = pws.product }, modifier = Modifier.weight(1f)) {
                                                Icon(Icons.Default.Edit, contentDescription = null)
                                                Spacer(Modifier.width(4.dp)); Text("Modifier")
                                            }
                                            OutlinedButton(onClick = { stockToAdjust = pws }, modifier = Modifier.weight(1f)) {
                                                Icon(Icons.Default.Inventory, contentDescription = null)
                                                Spacer(Modifier.width(4.dp)); Text("Ajuster stock")
                                            }
                                        }
                                        if (currentRole == UserRole.ADMIN) {
                                            TextButton(
                                                onClick = { productToArchive = pws.product },
                                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                                                Spacer(Modifier.width(4.dp)); Text("Archiver le produit")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    if (actionMessage != null) {
        AlertDialog(onDismissRequest = { actionMessage = null }, title = { Text("Gestion du stock") },
            text = { Text(actionMessage.orEmpty()) },
            confirmButton = { TextButton(onClick = { actionMessage = null }) { Text("OK") } })
    }

    productToEdit?.let { product ->
        var name by remember(product.id) { mutableStateOf(product.name) }
        var category by remember(product.id) { mutableStateOf(product.category) }
        var purchase by remember(product.id) { mutableStateOf(product.purchasePrice.toString()) }
        var selling by remember(product.id) { mutableStateOf(product.sellingPrice.toString()) }
        var unit by remember(product.id) { mutableStateOf(product.unit) }
        var alert by remember(product.id) { mutableStateOf(product.minStockAlert.toString()) }
        var error by remember(product.id) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { productToEdit = null }, title = { Text("Modifier le produit") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("Code SKU : ${product.code}", style = MaterialTheme.typography.labelMedium) }
                    item { OutlinedTextField(name, { name = it }, label = { Text("Nom du produit") }, singleLine = true) }
                    item { OutlinedTextField(category, { category = it }, label = { Text("Catégorie") }, singleLine = true) }
                    item { OutlinedTextField(purchase, { purchase = it }, label = { Text("Prix d'achat (Ar)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) }
                    item { OutlinedTextField(selling, { selling = it }, label = { Text("Prix de vente (Ar)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) }
                    item { OutlinedTextField(unit, { unit = it }, label = { Text("Unité") }, singleLine = true) }
                    item { OutlinedTextField(alert, { alert = it }, label = { Text("Seuil d'alerte") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
                    if (error != null) item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { Button(onClick = {
                val p = purchase.toDoubleOrNull(); val s = selling.toDoubleOrNull(); val a = alert.toIntOrNull()
                if (name.isBlank() || category.isBlank() || unit.isBlank() || p == null || s == null || a == null) {
                    error = "Vérifiez les champs obligatoires et les nombres."
                } else viewModel.updateProductDetails(product.id, name, category, p, s, a, unit) { ok, message ->
                    if (ok) productToEdit = null else error = message
                }
            }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = { productToEdit = null }) { Text("Annuler") } })
    }

    stockToAdjust?.let { item ->
        var quantity by remember(item.product.id, activeStoreId) { mutableStateOf(item.activeStoreQuantity.toString()) }
        var reason by remember(item.product.id, activeStoreId) { mutableStateOf("") }
        var error by remember(item.product.id, activeStoreId) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { stockToAdjust = null }, title = { Text("Ajuster le stock — $activeStoreId") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Produit : ${item.product.name}")
                    Text("Stock actuel : ${item.activeStoreQuantity} ${item.product.unit}")
                    OutlinedTextField(value = quantity, onValueChange = { quantity = it.filter(Char::isDigit) },
                        label = { Text("Nouvelle quantité réelle") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("Motif obligatoire") }, minLines = 2)
                    if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { Button(onClick = {
                val qty = quantity.toIntOrNull()
                if (qty == null || reason.isBlank()) error = "Saisissez une quantité valide et un motif."
                else viewModel.adjustActiveStoreStock(item.product.id, qty, reason) { ok, message ->
                    if (ok) stockToAdjust = null else error = message
                }
            }) { Text("Valider l'ajustement") } },
            dismissButton = { TextButton(onClick = { stockToAdjust = null }) { Text("Annuler") } })
    }

    productToArchive?.let { product ->
        AlertDialog(onDismissRequest = { productToArchive = null }, title = { Text("Archiver le produit ?") },
            text = { Text("Le produit « ${product.name} » sera masqué du catalogue actif. Les historiques seront conservés.") },
            confirmButton = { Button(onClick = {
                viewModel.archiveProduct(product.id) { ok, message ->
                    if (ok) productToArchive = null else actionMessage = message
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Confirmer l'archivage")
            } },
            dismissButton = { TextButton(onClick = { productToArchive = null }) { Text("Annuler") } })
    }

    // Add Product Dialog (Admin)
    if (showAddProductDialog) {
        var code by remember { mutableStateOf("PRD-${(100..999).random()}") }
        var name by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("Matériaux") }
        var purchasePriceText by remember { mutableStateOf("") }
        var sellingPriceText by remember { mutableStateOf("") }
        var unit by remember { mutableStateOf("Pcs") }
        var minAlertText by remember { mutableStateOf("10") }
        var initialStockA1Text by remember { mutableStateOf("50") }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showAddProductDialog = false },
            title = { Text("Nouveau Produit Réseau", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            label = { Text("Code Référence / SKU") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Désignation du produit") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = it },
                            label = { Text("Catégorie") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = purchasePriceText,
                            onValueChange = { purchasePriceText = it },
                            label = { Text("Prix d'Achat (Confidentiel Ar)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = sellingPriceText,
                            onValueChange = { sellingPriceText = it },
                            label = { Text("Prix de Vente Public (Ar)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = unit,
                                onValueChange = { unit = it },
                                label = { Text("Unité") },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = minAlertText,
                                onValueChange = { minAlertText = it },
                                label = { Text("Seuil alerte") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = initialStockA1Text,
                            onValueChange = { initialStockA1Text = it },
                            label = { Text("Stock initial Dépôt Central (A1)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (errorMsg != null) {
                        item {
                            Text(errorMsg ?: "", color = FanevaRed, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pPrice = purchasePriceText.toDoubleOrNull() ?: 0.0
                        val sPrice = sellingPriceText.toDoubleOrNull() ?: 0.0
                        val alert = minAlertText.toIntOrNull() ?: 5
                        val stockA1 = initialStockA1Text.toIntOrNull() ?: 0

                        if (name.isBlank()) {
                            errorMsg = "Le nom du produit est requis"
                            return@Button
                        }
                        if (sPrice <= 0.0) {
                            errorMsg = "Le prix de vente doit être supérieur à 0"
                            return@Button
                        }

                        val stocks = mapOf(
                            "A1" to stockA1,
                            "A2" to 0,
                            "A3" to 0,
                            "A4" to 0,
                            "A5" to 0
                        )

                        viewModel.addNewProduct(
                            code = code,
                            name = name,
                            category = category,
                            purchasePrice = pPrice,
                            sellingPrice = sPrice,
                            minAlert = alert,
                            unit = unit,
                            initialStocks = stocks,
                            onComplete = {
                                showAddProductDialog = false
                            }
                        )
                    }
                ) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddProductDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }
}
