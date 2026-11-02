package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.StoreId
import com.example.domain.model.UserRole
import com.example.ui.FanevaViewModel
import com.example.ui.components.MetricCard
import com.example.ui.components.RoleBadge
import com.example.ui.components.StoreSelectorChipRow
import com.example.ui.components.SyncStatusBadge
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: FanevaViewModel,
    onNavigateToPos: () -> Unit,
    onNavigateToSalesHistory: () -> Unit,
    onNavigateToInventory: () -> Unit,
    onNavigateToTransfers: () -> Unit,
    onNavigateToDebts: () -> Unit,
    onNavigateToSync: () -> Unit,
    onNavigateToAi: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeStoreId by viewModel.activeStoreId.collectAsStateWithLifecycle()
    val currentRole by viewModel.currentRole.collectAsStateWithLifecycle()
    val operatorName by viewModel.operatorName.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val allSales by viewModel.allSales.collectAsStateWithLifecycle()
    val allCustomers by viewModel.allCustomers.collectAsStateWithLifecycle()
    val productsWithStock by viewModel.productsWithStock.collectAsStateWithLifecycle()

    // Calculated metrics
    val storeSales = allSales.filter { it.storeId == activeStoreId }
    val totalRevenue = storeSales.sumOf { it.totalAmount }
    val totalDebt = allCustomers.sumOf { it.currentDebt }
    val lowStockCount = productsWithStock.count { it.activeStoreQuantity <= it.product.minStockAlert }
    val storeName = StoreId.fromCode(activeStoreId).displayName

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("FANEVA SYSTEM V2", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            RoleBadge(role = currentRole)
                        }
                        Text(
                            text = operatorName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    SyncStatusBadge(
                        isOnline = isOnline,
                        pendingCount = pendingCount,
                        onClick = onNavigateToSync
                    )
                    IconButton(onClick = onNavigateToAi) {
                        Badge(containerColor = FanevaCyan) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "FANEVA IA", tint = Color.Black)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Multi-Store Selector (A1..A5)
            item {
                StoreSelectorChipRow(
                    selectedStoreId = activeStoreId,
                    onStoreSelected = { viewModel.switchActiveStore(it) }
                )
            }

            // Quick Status Banner
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Magasin actif : $storeName",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Stocks et encaissements en temps réel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        Button(
                            onClick = onNavigateToPos,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("dashboard_pos_button")
                        ) {
                            Icon(Icons.Default.PointOfSale, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Caisse POS", fontSize = 13.sp)
                        }
                    }
                }
            }

            // Metrics Grid (2x2)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricCard(
                            title = "Ventes ($activeStoreId)",
                            value = "${String.format("%,.0f", totalRevenue)} Ar",
                            subtitle = "${storeSales.size} transaction(s)",
                            icon = Icons.Default.TrendingUp,
                            accentColor = FanevaEmerald,
                            onClick = onNavigateToSalesHistory,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            title = "Créances Clients",
                            value = "${String.format("%,.0f", totalDebt)} Ar",
                            subtitle = "${allCustomers.count { it.currentDebt > 0 }} client(s) en dette",
                            icon = Icons.Default.AccountBalanceWallet,
                            accentColor = FanevaAmber,
                            onClick = onNavigateToDebts,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricCard(
                            title = "Alertes Stock ($activeStoreId)",
                            value = "$lowStockCount produit(s)",
                            subtitle = if (lowStockCount > 0) "Besoin réassort" else "Stock optimal",
                            icon = Icons.Default.WarningAmber,
                            accentColor = if (lowStockCount > 0) FanevaRed else FanevaEmerald,
                            onClick = onNavigateToInventory,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            title = "File Synchro",
                            value = "$pendingCount en attente",
                            subtitle = if (isOnline) "En ligne" else "Hors-ligne sécurisé",
                            icon = Icons.Default.Sync,
                            accentColor = if (pendingCount > 0) FanevaAmber else FanevaCyan,
                            onClick = onNavigateToSync,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Quick Operations Row
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Actions Rapides",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onNavigateToTransfers,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Transfert A1-A5", fontSize = 12.sp, maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = onNavigateToDebts,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Règlement dette", fontSize = 12.sp, maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = onNavigateToAi,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = FanevaCyan)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("FANEVA IA", fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }

            // Recent Transactions Section
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Dernières Ventes (${storeSales.take(5).size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (storeSales.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Aucune vente enregistrée dans $storeName",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(storeSales.take(5), key = { it.id }) { sale ->
                    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    val formattedDate = dateFormat.format(Date(sale.createdAt))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                                        .background(
                                            if (sale.saleType == "CASH") FanevaEmerald.copy(alpha = 0.15f)
                                            else FanevaAmber.copy(alpha = 0.15f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (sale.saleType == "CASH") Icons.Default.AttachMoney else Icons.Default.CreditScore,
                                        contentDescription = null,
                                        tint = if (sale.saleType == "CASH") FanevaEmeraldDark else FanevaAmberDark,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = sale.saleNumber,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = if (sale.saleType == "CREDIT") "${sale.customerName ?: "Client"} • Crédit" else "Vente Comptant (${sale.paymentMethod})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = formattedDate,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${String.format("%,.0f", sale.totalAmount)} Ar",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                if (sale.remainingAmount > 0) {
                                    Text(
                                        text = "Reste: ${String.format("%,.0f", sale.remainingAmount)} Ar",
                                        fontSize = 11.sp,
                                        color = FanevaRed,
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
