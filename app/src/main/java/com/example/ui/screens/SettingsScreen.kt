package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.StoreId
import com.example.domain.model.UserRole
import com.example.ui.FanevaViewModel
import com.example.ui.components.RoleBadge
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: FanevaViewModel,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentRole by viewModel.currentRole.collectAsStateWithLifecycle()
    val activeStoreId by viewModel.activeStoreId.collectAsStateWithLifecycle()
    val syncCursor by viewModel.syncCursor.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val sellerAccounts by viewModel.sellerAccounts.collectAsStateWithLifecycle()

    var stagingUrlText by remember { mutableStateOf(syncCursor?.stagingApiUrl ?: "") }
    var showResetDialog by remember { mutableStateOf(false) }
    var saveUrlSuccess by remember { mutableStateOf(false) }
    var sellerUsername by remember { mutableStateOf("") }
    var sellerDisplayName by remember { mutableStateOf("") }
    var sellerPassword by remember { mutableStateOf("") }
    var sellerMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(syncCursor?.stagingApiUrl) {
        syncCursor?.stagingApiUrl?.let { stagingUrlText = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Paramètres & Démonstration", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            RoleBadge(role = currentRole)
                        }
                        Text("Configuration Staging & Rôles utilisateurs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            // 1. Authenticated profile
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Session authentifiée", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(currentRole.title, fontWeight = FontWeight.SemiBold)
                            RoleBadge(role = currentRole)
                        }
                        Text("Les préférences locales ne constituent pas une preuve d'identité.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onLogout, modifier = Modifier.testTag("logout_button")) { Text("Se déconnecter") }
                    }
                }
            }

            // 2. Admin-only seller management
            if (viewModel.isAdminAuthenticated()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Gestion des vendeurs", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("Les mots de passe ne sont jamais affichés.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(sellerUsername, { sellerUsername = it; sellerMessage = null }, label = { Text("Identifiant unique") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("seller_create_username"))
                            OutlinedTextField(sellerDisplayName, { sellerDisplayName = it; sellerMessage = null }, label = { Text("Nom affiché") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("seller_create_name"))
                            OutlinedTextField(sellerPassword, { sellerPassword = it; sellerMessage = null }, label = { Text("Mot de passe") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("seller_create_password"))
                            Button(onClick = {
                                viewModel.createSeller(sellerUsername, sellerDisplayName, sellerPassword) { message ->
                                    sellerMessage = message ?: "Compte vendeur créé."
                                    if (message == null) { sellerUsername = ""; sellerDisplayName = ""; sellerPassword = "" }
                                }
                            }, modifier = Modifier.fillMaxWidth().testTag("seller_create_button")) { Text("Créer le compte vendeur") }
                            sellerMessage?.let { Text(it, color = if (it.startsWith("Compte")) FanevaEmeraldDark else MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                            sellerAccounts.forEach { seller ->
                                Text("• ${seller.username} — ${seller.displayName}", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // 3. Default Store Selector
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Magasin Actif Par Défaut",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        StoreId.entries.forEach { store ->
                            val isSelected = activeStoreId == store.code
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.switchActiveStore(store.code) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(store.displayName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(store.location, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                RadioButton(selected = isSelected, onClick = { viewModel.switchActiveStore(store.code) })
                            }
                        }
                    }
                }
            }

            // 3. Staging API Configuration for Cloudflare Workers
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Connexion Staging Cloudflare Workers",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "URL configurable pour la future passerelle HTTP sécurisée. Aucun secret ni clé privée n'est embarqué dans l'application.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = stagingUrlText,
                            onValueChange = {
                                stagingUrlText = it
                                saveUrlSuccess = false
                            },
                            label = { Text("URL API Staging Worker") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("staging_url_input"),
                            singleLine = true
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (saveUrlSuccess) "URL enregistrée !" else "",
                                color = FanevaEmeraldDark,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Button(
                                onClick = {
                                    viewModel.updateStagingUrl(stagingUrlText)
                                    saveUrlSuccess = true
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("save_staging_url_button")
                            ) {
                                Text("Enregistrer l'URL")
                            }
                        }
                    }
                }
            }

            // 4. Reliability & Architecture Guidelines
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Garanties de Fiabilité FANEVA V2 :", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("• Règle de stock : Zéro quantité négative autorisée", fontSize = 11.sp)
                        Text("• Idempotence : Chaque vente et mouvement dispose d'un UUID unique", fontSize = 11.sp)
                        Text("• Vente à crédit : Cohérence atomique stock et compte client", fontSize = 11.sp)
                        Text("• File Outbox : Persistance locale des opérations en attente", fontSize = 11.sp)
                        Text("• Curseur : Aucun avancement sans confirmation serveur (ACK)", fontSize = 11.sp)
                        Text("• Synchronisation : succès uniquement après réponse HTTP et ACK valides", fontSize = 11.sp)
                        Text("• Rôles : simulation locale uniquement, sans garantie de sécurité", fontSize = 11.sp)
                    }
                }
            }

            // 5. Reset Demo Data Button
            item {
                Button(
                    onClick = { showResetDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = FanevaRed.copy(alpha = 0.15f), contentColor = FanevaRed),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Réinitialiser les données de démonstration")
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Réinitialisation") },
            text = { Text("Voulez-vous recharger le jeu de données fictives initial (produits, stocks A1-A5, clients et dettes) ?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetDemoData()
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = FanevaRed)
                ) {
                    Text("Réinitialiser")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }
}
