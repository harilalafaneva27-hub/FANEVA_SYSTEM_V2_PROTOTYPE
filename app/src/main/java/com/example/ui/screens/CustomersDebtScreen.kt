package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.data.local.entity.CustomerEntity
import com.example.domain.model.PaymentMethod
import com.example.domain.validation.MgaAmountValidator
import com.example.domain.validation.CreditExposureCalculator
import com.example.ui.FanevaViewModel
import com.example.ui.components.RoleBadge
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomersDebtScreen(
    viewModel: FanevaViewModel,
    modifier: Modifier = Modifier
) {
    val currentRole by viewModel.currentRole.collectAsStateWithLifecycle()
    val allCustomers by viewModel.allCustomers.collectAsStateWithLifecycle()
    val allSales by viewModel.allSales.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCustomerForPayment by remember { mutableStateOf<CustomerEntity?>(null) }
    var showAddCustomerDialog by remember { mutableStateOf(false) }

    val filteredCustomers = remember(allCustomers, searchQuery) {
        allCustomers.filter {
            searchQuery.isBlank() ||
                    it.name.contains(searchQuery, ignoreCase = true) ||
                    it.phone.contains(searchQuery, ignoreCase = true)
        }
    }

    val knownCustomerIds = remember(allCustomers) {
        allCustomers.map { it.id }.toSet()
    }
    val portfolioPending = remember(allSales, knownCustomerIds) {
        CreditExposureCalculator.calculatePortfolioPendingExposureBreakdown(
            allSales,
            knownCustomerIds
        )
    }
    val portfolioOfficialDebt = remember(allCustomers) {
        var total = 0.0
        for (customer in allCustomers) {
            if (!MgaAmountValidator.isValidNonNegative(customer.currentDebt)) {
                return@remember null
            }
            total += customer.currentDebt
            if (!MgaAmountValidator.isValidNonNegative(total)) {
                return@remember null
            }
        }
        total
    }
    val portfolioTotalExposure = if (
        portfolioOfficialDebt != null && portfolioPending != null
    ) {
        CreditExposureCalculator.calculateProjectedExposure(
            portfolioOfficialDebt,
            portfolioPending.total,
            0.0
        )
    } else null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Clients & Suivi des Dettes", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            RoleBadge(role = currentRole)
                        }
                        Text("Gestion des créances et règlements", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddCustomerDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_customer_fab")
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = "Nouveau Client")
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Portfolio Summary Card
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Exposition Globale du Portefeuille",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Dette officielle : " +
                                (portfolioOfficialDebt?.let { "${String.format("%,.0f", it)} Ar" } ?: "Indisponible"),
                            modifier = Modifier.testTag("portfolio_official_debt"),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "En attente de synchronisation : " +
                                (portfolioPending?.pendingSync?.let { "${String.format("%,.0f", it)} Ar" } ?: "Indisponible"),
                            modifier = Modifier.testTag("portfolio_pending_sync"),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "En réconciliation : " +
                                (portfolioPending?.reconciliationRequired?.let { "${String.format("%,.0f", it)} Ar" } ?: "Indisponible"),
                            modifier = Modifier.testTag("portfolio_reconciliation"),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Exposition des clients non référencés : " +
                                (portfolioPending?.nonReferencedCustomerExposure?.let {
                                    "${String.format("%,.0f", it)} Ar"
                                } ?: "Indisponible"),
                            modifier = Modifier.testTag("portfolio_non_referenced"),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Exposition totale : " +
                                (portfolioTotalExposure?.let { "${String.format("%,.0f", it)} Ar" } ?: "Indisponible"),
                            modifier = Modifier.testTag("portfolio_total_exposure"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = if (portfolioTotalExposure == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else if (portfolioTotalExposure > 0) {
                                FanevaRed
                            } else {
                                FanevaEmeraldDark
                            }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(FanevaAmber.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AccountBalance, contentDescription = null, tint = FanevaAmberDark)
                    }
                }
            }

            // Search Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Rechercher un client (nom, téléphone)...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // Customer List
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredCustomers, key = { it.id }) { customer ->
                    val pendingBreakdown = remember(allSales, customer.id) {
                        CreditExposureCalculator.calculatePendingExposureBreakdown(
                            allSales,
                            customer.id
                        )
                    }
                    val customerOfficialDebtValid =
                        MgaAmountValidator.isValidNonNegative(customer.currentDebt)
                    val customerCreditLimitValid =
                        MgaAmountValidator.isValidNonNegative(customer.creditLimit)

                    val customerTotalExposure = if (
                        customerOfficialDebtValid && pendingBreakdown != null
                    ) {
                        CreditExposureCalculator.calculateProjectedExposure(
                            customer.currentDebt,
                            pendingBreakdown.total,
                            0.0
                        )
                    } else null

                    val ratio = if (
                        customerTotalExposure != null &&
                        customerCreditLimitValid &&
                        customer.creditLimit > 0
                    ) {
                        (customerTotalExposure / customer.creditLimit).toFloat()
                    } else null

                    val isHighRisk = ratio != null && ratio >= 0.8f

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("customer_card_${customer.id}")
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    customer.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    "${customer.phone} • ${customer.address}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    "Dette officielle : " +
                                        (if (customerOfficialDebtValid)
                                            "${String.format("%,.0f", customer.currentDebt)} Ar"
                                        else "Indisponible"),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "PENDING_SYNC : " +
                                        (pendingBreakdown?.pendingSync?.let {
                                            "${String.format("%,.0f", it)} Ar"
                                        } ?: "Indisponible"),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "RECONCILIATION_REQUIRED : " +
                                        (pendingBreakdown?.reconciliationRequired?.let {
                                            "${String.format("%,.0f", it)} Ar"
                                        } ?: "Indisponible"),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Exposition totale : " +
                                        (customerTotalExposure?.let {
                                            "${String.format("%,.0f", it)} Ar"
                                        } ?: "Indisponible"),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (customerTotalExposure == null) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else if (customerTotalExposure > 0) {
                                        FanevaRed
                                    } else {
                                        FanevaEmeraldDark
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Credit Limit Progress Bar
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Plafond autorisé : " +
                                        (if (customerCreditLimitValid)
                                            "${String.format("%,.0f", customer.creditLimit)} Ar"
                                        else "Indisponible"),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    ratio?.let { "${(it * 100).toInt()}% utilisé" }
                                        ?: "Ratio indisponible",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isHighRisk) FanevaRed
                                        else MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { (ratio ?: 0f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = if (ratio == null) MaterialTheme.colorScheme.surfaceVariant
                                    else if (isHighRisk) FanevaRed
                                    else MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Action button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(
                                    onClick = { selectedCustomerForPayment = customer },
                                    enabled = customerOfficialDebtValid && customer.currentDebt > 0.0,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Encaisser un règlement", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Payment Settlement Dialog
    if (selectedCustomerForPayment != null) {
        val customer = selectedCustomerForPayment!!
        var paymentAmountText by remember {
            mutableStateOf(
                customer.currentDebt
                    .takeIf { MgaAmountValidator.isValidNonNegative(it) }
                    ?.toLong()
                    ?.toString()
                    ?: ""
            )
        }
        var paymentMethod by remember { mutableStateOf(PaymentMethod.ESPECES) }
        var note by remember { mutableStateOf("Règlement partiel / total") }
        var isProcessing by remember { mutableStateOf(false) }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { if (!isProcessing) selectedCustomerForPayment = null },
            title = { Text("Encaissement de Dette", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Client : ${customer.name}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Dette actuelle due : ${String.format("%,.0f", customer.currentDebt)} Ar", color = FanevaRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)

                    OutlinedTextField(
                        value = paymentAmountText,
                        onValueChange = { paymentAmountText = it },
                        label = { Text("Montant réglé (Ar)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Mode de règlement :", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(PaymentMethod.ESPECES, PaymentMethod.MVOLA, PaymentMethod.AIRTEL_MONEY).forEach { m ->
                            FilterChip(
                                selected = paymentMethod == m,
                                onClick = { paymentMethod = m },
                                label = { Text(m.label, fontSize = 11.sp) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Référence / Note") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMsg != null) {
                        Text(errorMsg ?: "", color = FanevaRed, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amount = MgaAmountValidator.parseNonNegativeInteger(paymentAmountText)
                        if (amount == null || !MgaAmountValidator.isValidPositive(amount)) {
                            errorMsg = "Saisissez un montant MGA entier strictement positif"
                            return@Button
                        }
                        if (!MgaAmountValidator.isValidNonNegative(customer.currentDebt)) {
                            errorMsg = "La dette enregistrée n'est pas un montant MGA entier valide"
                            return@Button
                        }
                        if (amount > customer.currentDebt) {
                            errorMsg = "Le montant ne peut pas excéder la dette restante"
                            return@Button
                        }

                        isProcessing = true
                        viewModel.recordDebtPayment(
                            customerId = customer.id,
                            amount = amount,
                            method = paymentMethod,
                            note = note,
                            onResult = { result ->
                                isProcessing = false
                                if (result.isSuccess) {
                                    selectedCustomerForPayment = null
                                } else {
                                    errorMsg = result.exceptionOrNull()?.message ?: "Erreur d'encaissement"
                                }
                            }
                        )
                    },
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = FanevaEmerald)
                ) {
                    Text("Valider le reçu", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedCustomerForPayment = null }, enabled = !isProcessing) {
                    Text("Annuler")
                }
            }
        )
    }

    // Add Customer Dialog
    if (showAddCustomerDialog) {
        var name by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("+261 ") }
        var address by remember { mutableStateOf("") }
        var creditLimitText by remember { mutableStateOf("1000000") }
        var error by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showAddCustomerDialog = false },
            title = { Text("Nouveau Compte Client", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nom ou Raison Sociale") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Numéro Téléphone") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Adresse / Localisation") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = creditLimitText,
                        onValueChange = { creditLimitText = it },
                        label = { Text("Plafond de crédit autorisé (Ar)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error != null) {
                        Text(error ?: "", color = FanevaRed, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val limit = MgaAmountValidator.parseNonNegativeInteger(creditLimitText)
                        if (limit == null) {
                            error = "Saisissez un plafond MGA entier positif ou nul"
                            return@Button
                        }
                        if (name.isBlank()) {
                            error = "Le nom du client est requis"
                            return@Button
                        }
                        viewModel.addNewCustomer(
                            name = name,
                            phone = phone,
                            address = address,
                            creditLimit = limit,
                            onComplete = {
                                showAddCustomerDialog = false
                            }
                        )
                    }
                ) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCustomerDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }
}
