package com.example.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.AuthUiState
import com.example.ui.FanevaViewModel
import com.example.ui.screens.*

enum class FanevaScreen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val isBottomNav: Boolean
) {
    DASHBOARD("dashboard", "Tableau de bord", Icons.Filled.Dashboard, Icons.Outlined.Dashboard, true),
    POS("pos", "Caisse POS", Icons.Filled.PointOfSale, Icons.Outlined.PointOfSale, true),
    INVENTORY("inventory", "Stocks A1-A5", Icons.Filled.Inventory2, Icons.Outlined.Inventory2, true),
    CUSTOMERS("customers", "Clients & Dettes", Icons.Filled.People, Icons.Outlined.People, true),
    SETTINGS("settings", "Paramètres", Icons.Filled.Settings, Icons.Outlined.Settings, true),
    TRANSFERS("transfers", "Transferts", Icons.Filled.SwapHoriz, Icons.Outlined.SwapHoriz, false),
    SALES_HISTORY("history", "Historique", Icons.Filled.ReceiptLong, Icons.Outlined.ReceiptLong, false),
    SYNC_OUTBOX("outbox", "File Synchro", Icons.Filled.Sync, Icons.Outlined.Sync, false),
    AI_INSIGHTS("ai", "FANEVA IA", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome, false)
}

@Composable
fun FanevaApp(viewModel: FanevaViewModel, modifier: Modifier = Modifier) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    when (val state = authState) {
        AuthUiState.Loading -> CircularProgressIndicator(modifier = modifier.testTag("auth_loading"))
        AuthUiState.SignedOut -> LoginScreen(
            onAdminLogin = { code, callback -> viewModel.authenticateAdmin(code, callback) },
            onSellerLogin = { username, password, callback -> viewModel.authenticateSeller(username, password, callback) },
            modifier = modifier.testTag("login_screen")
        )
        is AuthUiState.SignedIn -> AuthenticatedFanevaApp(viewModel, modifier)
    }
}

@Composable
private fun AuthenticatedFanevaApp(viewModel: FanevaViewModel, modifier: Modifier) {
    var currentScreen by remember { mutableStateOf(FanevaScreen.DASHBOARD) }

    if (currentScreen != FanevaScreen.DASHBOARD) {
        BackHandler { currentScreen = FanevaScreen.DASHBOARD }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(modifier = Modifier.testTag("faneva_bottom_nav")) {
                FanevaScreen.entries.filter { it.isBottomNav }.forEach { screen ->
                    val isSelected = currentScreen == screen
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentScreen = screen },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.title
                            )
                        },
                        label = { Text(screen.title) },
                        modifier = Modifier.testTag("nav_item_${screen.route}")
                    )
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        val screenModifier = Modifier.padding(innerPadding)
        when (currentScreen) {
            FanevaScreen.DASHBOARD -> DashboardScreen(viewModel, { currentScreen = FanevaScreen.POS },
                { currentScreen = FanevaScreen.SALES_HISTORY }, { currentScreen = FanevaScreen.INVENTORY },
                { currentScreen = FanevaScreen.TRANSFERS }, { currentScreen = FanevaScreen.CUSTOMERS },
                { currentScreen = FanevaScreen.SYNC_OUTBOX }, { currentScreen = FanevaScreen.AI_INSIGHTS }, screenModifier)
            FanevaScreen.POS -> PosScreen(viewModel, { currentScreen = FanevaScreen.SALES_HISTORY }, screenModifier)
            FanevaScreen.INVENTORY -> InventoryScreen(viewModel, { currentScreen = FanevaScreen.TRANSFERS }, screenModifier)
            FanevaScreen.CUSTOMERS -> CustomersDebtScreen(viewModel, screenModifier)
            FanevaScreen.SETTINGS -> SettingsScreen(viewModel, { viewModel.logout() }, screenModifier)
            FanevaScreen.TRANSFERS -> StoreTransfersScreen(viewModel, { currentScreen = FanevaScreen.DASHBOARD }, screenModifier)
            FanevaScreen.SALES_HISTORY -> SalesHistoryScreen(viewModel, { currentScreen = FanevaScreen.POS }, screenModifier)
            FanevaScreen.SYNC_OUTBOX -> SyncOutboxScreen(viewModel, { currentScreen = FanevaScreen.DASHBOARD }, screenModifier)
            FanevaScreen.AI_INSIGHTS -> FanevaAiScreen(viewModel, { currentScreen = FanevaScreen.DASHBOARD }, screenModifier)
        }
    }
}
