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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.SaleEntity
import com.example.domain.model.StoreId
import com.example.ui.FanevaViewModel
import com.example.ui.components.RoleBadge
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesHistoryScreen(
    viewModel: FanevaViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentRole by viewModel.currentRole.collectAsStateWithLifecycle()
    val allSales by viewModel.allSales.collectAsStateWithLifecycle()

    var selectedStoreFilter by remember { mutableStateOf<String?>(null) }
    var selectedTypeFilter by remember { mutableStateOf<String?>(null) }
    var selectedSaleForDetails by remember { mutableStateOf<SaleEntity?>(null) }

    val filteredSales = remember(allSales, selectedStoreFilter, selectedTypeFilter) {
        allSales.filter { sale ->
            val matchStore = selectedStoreFilter == null || sale.storeId == selectedStoreFilter
            val matchType = selectedTypeFilter == null || sale.saleType == selectedTypeFilter
            matchStore && matchType
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Historique des Ventes", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            RoleBadge(role = currentRole)
                        }
                        Text("Traçabilité des transactions et clés d'idempotence", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Filters Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedStoreFilter == null,
                    onClick = { selectedStoreFilter = null },
                    label = { Text("Tous Magasins") }
                )
                StoreId.entries.forEach { store ->
                    FilterChip(
                        selected = selectedStoreFilter == store.code,
                        onClick = { selectedStoreFilter = store.code },
                        label = { Text(store.code) }
                    )
                }

                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))

                FilterChip(
                    selected = selectedTypeFilter == null,
                    onClick = { selectedTypeFilter = null },
                    label = { Text("Tous Types") }
                )
                FilterChip(
                    selected = selectedTypeFilter == "CASH",
                    onClick = { selectedTypeFilter = "CASH" },
                    label = { Text("Comptant") }
                )
                FilterChip(
                    selected = selectedTypeFilter == "CREDIT",
                    onClick = { selectedTypeFilter = "CREDIT" },
                    label = { Text("À Crédit") }
                )
            }

            // Sales list
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (filteredSales.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Aucune vente correspondant aux filtres", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(filteredSales, key = { it.id }) { sale ->
                        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                        val formattedDate = dateFormat.format(Date(sale.createdAt))
                        val isCash = sale.saleType == "CASH"

                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedSaleForDetails = sale }
                                .testTag("sale_card_${sale.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(if (isCash) FanevaEmerald.copy(alpha = 0.15f) else FanevaAmber.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isCash) Icons.Default.AttachMoney else Icons.Default.CreditCard,
                                            contentDescription = null,
                                            tint = if (isCash) FanevaEmeraldDark else FanevaAmberDark
                                        )
                                    }
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(sale.saleNumber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Text(sale.storeId, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                            }
                                        }
                                        Text(
                                            text = if (isCash) "Vente Comptant (${sale.paymentMethod})" else "Crédit • ${sale.customerName ?: "Client"}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "$formattedDate • Opérateur: ${sale.sellerName}",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${String.format("%,.0f", sale.totalAmount)} Ar",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (sale.remainingAmount > 0) {
                                        Text(
                                            text = "Dû : ${String.format("%,.0f", sale.remainingAmount)} Ar",
                                            fontSize = 11.sp,
                                            color = FanevaRed,
                                            fontWeight = FontWeight.Bold
                                        )
                                    } else {
                                        Text(
                                            text = "Soldé",
                                            fontSize = 11.sp,
                                            color = FanevaEmeraldDark,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Sale Details & Receipt Modal
    if (selectedSaleForDetails != null) {
        val sale = selectedSaleForDetails!!
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val dateFormatted = dateFormat.format(Date(sale.createdAt))

        AlertDialog(
            onDismissRequest = { selectedSaleForDetails = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Receipt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Reçu Vente ${sale.saleNumber}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Magasin : ${StoreId.fromCode(sale.storeId).displayName}", fontSize = 12.sp)
                    Text("Date : $dateFormatted", fontSize = 12.sp)
                    Text("Vendeur : ${sale.sellerName}", fontSize = 12.sp)
                    if (sale.saleType == "CREDIT") {
                        Text("Client : ${sale.customerName ?: "Inconnu"}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Type : ${if (sale.saleType == "CASH") "Comptant" else "À Crédit"} (${sale.paymentMethod})", fontSize = 12.sp)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Montant Total :", fontWeight = FontWeight.Bold)
                        Text("${String.format("%,.0f", sale.totalAmount)} Ar", fontWeight = FontWeight.Bold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Payé immédiatement :")
                        Text("${String.format("%,.0f", sale.paidAmount)} Ar")
                    }
                    if (sale.remainingAmount > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Créance / Reste à payer :", color = FanevaRed, fontWeight = FontWeight.Bold)
                            Text("${String.format("%,.0f", sale.remainingAmount)} Ar", color = FanevaRed, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text("Clé d'Idempotence (UUID déduplication) :", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(sale.idempotencyKey, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            },
            confirmButton = {
                Button(onClick = { selectedSaleForDetails = null }) {
                    Text("Fermer")
                }
            }
        )
    }
}
