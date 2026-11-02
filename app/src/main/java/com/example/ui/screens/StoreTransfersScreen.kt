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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.StoreId
import com.example.domain.model.TransferValidationResult
import com.example.ui.FanevaViewModel
import com.example.ui.components.RoleBadge
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreTransfersScreen(
    viewModel: FanevaViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentRole by viewModel.currentRole.collectAsStateWithLifecycle()
    val productsWithStock by viewModel.productsWithStock.collectAsStateWithLifecycle()
    val allMovements by viewModel.allMovements.collectAsStateWithLifecycle()

    var fromStore by remember { mutableStateOf("A2") }
    var toStore by remember { mutableStateOf("A1") }
    var selectedProductId by remember { mutableStateOf<Long?>(productsWithStock.firstOrNull()?.product?.id) }
    var transferQuantityText by remember { mutableStateOf("10") }
    var reasonText by remember { mutableStateOf("Réassort magasin central") }

    var isProcessing by remember { mutableStateOf(false) }
    var transferResult by remember { mutableStateOf<TransferValidationResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val selectedProduct = productsWithStock.find { it.product.id == selectedProductId }
    val fromStock = selectedProduct?.stocksByStore?.get(fromStore) ?: 0
    val toStock = selectedProduct?.stocksByStore?.get(toStore) ?: 0
    val transferMovements = allMovements.filter { it.movementType == "TRANSFERT" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Transferts Inter-Magasins", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            RoleBadge(role = currentRole)
                        }
                        Text("Gestion des flux de stock A1, A2, A3, A4, A5", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Transfer Form Card
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Initier un Nouveau Transfert",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // From Store & To Store Pickers
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Magasin Source :", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    StoreId.entries.forEach { store ->
                                        FilterChip(
                                            selected = fromStore == store.code,
                                            onClick = { fromStore = store.code },
                                            label = { Text(store.code, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }

                            Icon(Icons.Default.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text("Magasin Destination :", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    StoreId.entries.forEach { store ->
                                        FilterChip(
                                            selected = toStore == store.code,
                                            onClick = { toStore = store.code },
                                            label = { Text(store.code, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }

                        // Product Selection
                        Text("Sélection du Produit à Transférer :", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            productsWithStock.take(5).forEach { pws ->
                                val isSelected = pws.product.id == selectedProductId
                                val availableInSrc = pws.stocksByStore[fromStore] ?: 0

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedProductId = pws.product.id }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(pws.product.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        Text(
                                            text = "Dispo dans $fromStore : $availableInSrc",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (availableInSrc > 0) FanevaEmeraldDark else FanevaRed
                                        )
                                    }
                                }
                            }
                        }

                        // Stock Availability Snapshot
                        if (selectedProduct != null) {
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Source ($fromStore)", fontSize = 11.sp)
                                        Text("$fromStock ${selectedProduct.product.unit}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = FanevaEmeraldDark)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Destination ($toStore)", fontSize = 11.sp)
                                        Text("$toStock ${selectedProduct.product.unit}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                }
                            }
                        }

                        // Transfer Quantity & Reason
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = transferQuantityText,
                                onValueChange = { transferQuantityText = it },
                                label = { Text("Quantité") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = reasonText,
                                onValueChange = { reasonText = it },
                                label = { Text("Motif / Note") },
                                modifier = Modifier.weight(2f)
                            )
                        }

                        if (errorMessage != null) {
                            Text(errorMessage ?: "", color = FanevaRed, fontSize = 12.sp)
                        }

                        // Validation Button
                        Button(
                            onClick = {
                                val qty = transferQuantityText.toIntOrNull() ?: 0
                                errorMessage = null

                                if (selectedProduct == null) {
                                    errorMessage = "Veuillez sélectionner un produit"
                                    return@Button
                                }
                                if (fromStore == toStore) {
                                    errorMessage = "Le magasin d'origine et de destination doivent être différents"
                                    return@Button
                                }
                                if (qty <= 0) {
                                    errorMessage = "La quantité doit être strictement positive"
                                    return@Button
                                }
                                if (fromStock < qty) {
                                    errorMessage = "Stock insuffisant dans $fromStore : seulement $fromStock disponible(s)"
                                    return@Button
                                }

                                isProcessing = true
                                viewModel.executeTransfer(
                                    productId = selectedProduct.product.id,
                                    productName = selectedProduct.product.name,
                                    fromStore = fromStore,
                                    toStore = toStore,
                                    quantity = qty,
                                    reason = reasonText,
                                    onResult = { result ->
                                        isProcessing = false
                                        transferResult = result
                                        if (result.isSuccess) {
                                            transferQuantityText = ""
                                        } else {
                                            errorMessage = result.errorMessage
                                        }
                                    }
                                )
                            },
                            enabled = !isProcessing && currentRole.canTransferStock,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("validate_transfer_button")
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                            } else {
                                Icon(Icons.Default.SwapHoriz, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Valider le Transfert Inter-Magasins", fontWeight = FontWeight.Bold)
                            }
                        }

                        if (!currentRole.canTransferStock) {
                            Text(
                                text = "Action restreinte : Seuls les rôles Admin et Gérant peuvent initier des transferts",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Transfer Movements Log
            item {
                Text(
                    text = "Historique des Transferts (${transferMovements.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (transferMovements.isEmpty()) {
                item {
                    Text(
                        text = "Aucun transfert récent enregistré",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            } else {
                items(transferMovements, key = { it.id }) { mvt ->
                    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    val dateFormatted = dateFormat.format(Date(mvt.createdAt))

                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(FanevaCyan.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = FanevaCyan, modifier = Modifier.size(20.dp))
                                }
                                Column {
                                    Text(mvt.productName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("${mvt.fromStoreId ?: "?"} ➔ ${mvt.toStoreId ?: "?"} • ${mvt.quantity} unité(s)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text("${mvt.reason} • $dateFormatted", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(
                                text = mvt.performedBy,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (transferResult != null && transferResult?.isSuccess == true) {
        AlertDialog(
            onDismissRequest = { transferResult = null },
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = FanevaEmerald, modifier = Modifier.size(44.dp)) },
            title = { Text("Transfert Exécuté") },
            text = {
                Text("Le transfert a été appliqué avec succès. Les stocks de $fromStore et $toStore ont été mis à jour et l'opération a été ajoutée à la file de synchronisation.")
            },
            confirmButton = {
                Button(onClick = { transferResult = null }) {
                    Text("OK")
                }
            }
        )
    }
}
