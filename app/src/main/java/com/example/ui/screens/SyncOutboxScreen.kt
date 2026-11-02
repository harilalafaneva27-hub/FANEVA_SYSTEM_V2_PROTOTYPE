package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.PendingSyncOperationEntity
import com.example.ui.FanevaViewModel
import com.example.ui.components.RoleBadge
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncOutboxScreen(
    viewModel: FanevaViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentRole by viewModel.currentRole.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val pendingOperations by viewModel.pendingOperations.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val syncCursor by viewModel.syncCursor.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()

    var expandedOpId by remember { mutableStateOf<String?>(null) }
    val lastSyncTime = syncCursor?.lastSyncTimestamp ?: 0L
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    val formattedLastSync = if (lastSyncTime > 0) dateFormat.format(Date(lastSyncTime)) else "Aucune synchronisation"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Synchronisation & Outbox", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            RoleBadge(role = currentRole)
                        }
                        Text("Architecture Hors-Ligne & File d'attente locale", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Connectivity Status Banner
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isOnline) MaterialTheme.colorScheme.surface else FanevaAmber.copy(alpha = 0.15f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(if (isOnline) FanevaEmerald else FanevaAmber)
                                )
                                Text(
                                    text = if (isOnline) "Connexion Staging Active" else "Mode Hors-Ligne (Données locales protégées)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = if (isOnline) MaterialTheme.colorScheme.onSurface else FanevaAmberDark
                                )
                            }
                            Switch(
                                checked = isOnline,
                                onCheckedChange = { viewModel.toggleNetwork(it) },
                                modifier = Modifier.testTag("toggle_network_switch")
                            )
                        }

                        Text(
                            text = "Curseur de Synchronisation : #${syncCursor?.lastCursor ?: 0} • Dernier sync : $formattedLastSync",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "URL Staging Worker : ${syncCursor?.stagingApiUrl ?: ""}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (syncMessage != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            ) {
                                Text(
                                    text = syncMessage ?: "",
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }

                        // Trigger Manual Sync Action
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.triggerManualSync { } },
                                enabled = !isSyncing,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("manual_sync_button")
                            ) {
                                if (isSyncing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Synchronisation...")
                                } else {
                                    Icon(Icons.Default.CloudSync, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Synchroniser maintenant", fontWeight = FontWeight.Bold)
                                }
                            }

                            OutlinedButton(
                                onClick = { viewModel.purgeSyncedOutbox() },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Purger synchro")
                            }
                        }
                    }
                }
            }

            // Section title: Pending Mutations in Outbox
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "File d'attente Outbox (${pendingOperations.size} opérations)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (pendingCount > 0) FanevaAmber.copy(alpha = 0.2f) else FanevaEmerald.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (pendingCount > 0) "$pendingCount en attente d'envoi" else "File propre",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (pendingCount > 0) FanevaAmberDark else FanevaEmeraldDark,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (pendingOperations.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Aucune opération en attente dans la file locale",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(pendingOperations, key = { it.mutationId }) { op ->
                    val isExpanded = expandedOpId == op.mutationId
                    val isPending = op.status == "PENDING"
                    val isSynced = op.status == "SYNCED"

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedOpId = if (isExpanded) null else op.mutationId }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isPending) FanevaAmber
                                                else if (isSynced) FanevaEmerald
                                                else FanevaRed
                                            )
                                    )
                                    Text(
                                        text = "${op.action} ${op.entityType}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isPending) FanevaAmber.copy(alpha = 0.15f)
                                    else if (isSynced) FanevaEmerald.copy(alpha = 0.15f)
                                    else FanevaRed.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = op.status,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPending) FanevaAmberDark else if (isSynced) FanevaEmeraldDark else FanevaRed,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Mutation ID: ${op.mutationId.take(8)}... • Idempotency: ${op.idempotencyKey.take(12)}...",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            AnimatedVisibility(visible = isExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp)
                                ) {
                                    HorizontalDivider()
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("Payload JSON :", fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = op.payloadJson,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(8.dp)
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
}
