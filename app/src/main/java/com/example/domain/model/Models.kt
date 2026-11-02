package com.example.domain.model

import java.util.UUID

enum class UserRole(
    val code: String,
    val title: String,
    val description: String,
    val canViewCostAndMargin: Boolean,
    val canManageInventory: Boolean,
    val canTransferStock: Boolean,
    val canConfigureSettings: Boolean
) {
    ADMIN(
        code = "ADMIN",
        title = "Administrateur Système",
        description = "Accès complet aux coûts d'achat, marges, magasins et paramètres",
        canViewCostAndMargin = true,
        canManageInventory = true,
        canTransferStock = true,
        canConfigureSettings = true
    ),
    GERANT(
        code = "GERANT",
        title = "Gérant / Superviseur",
        description = "Gestion des stocks A1-A5, transferts, validation crédits et marges",
        canViewCostAndMargin = true,
        canManageInventory = true,
        canTransferStock = true,
        canConfigureSettings = false
    ),
    VENDEUR(
        code = "VENDEUR",
        title = "Vendeur / Caissier",
        description = "Vente comptant et crédit, consultation du prix public uniquement",
        canViewCostAndMargin = false, // RÈGLE CRITIQUE : Jamais de prix d'achat ni de marge visible pour le vendeur
        canManageInventory = false,
        canTransferStock = false,
        canConfigureSettings = false
    )
}

data class UserSession(
    val id: String,
    val name: String,
    val email: String,
    val role: UserRole,
    val defaultStoreId: String = "A1"
)

enum class StoreId(val code: String, val displayName: String, val location: String) {
    A1("A1", "Magasin Central (A1)", "Analakely - Dépôt Principal"),
    A2("A2", "Dépôt Nord (A2)", "Ankorondrano - Plateforme Logistique"),
    A3("A3", "Boutique Marché (A3)", "Isotry - Comptoir de Détail"),
    A4("A4", "Showroom Sud (A4)", "Tanjombato - Hall d'Exposition"),
    A5("A5", "Comptoir Express (A5)", "Ivato - Point Relais Rapide");

    companion object {
        fun fromCode(code: String): StoreId = entries.find { it.code == code } ?: A1
    }
}

enum class SaleType(val label: String) {
    CASH("Vente Comptant"),
    CREDIT("Vente à Crédit")
}

enum class PaymentMethod(val label: String) {
    ESPECES("Espèces"),
    MVOLA("Mvola"),
    AIRTEL_MONEY("Airtel Money"),
    ORANGE_MONEY("Orange Money"),
    CHEQUE("Chèque"),
    VIREMENT("Virement bancaire")
}

data class CartItem(
    val productId: Long,
    val productCode: String,
    val productName: String,
    val unit: String,
    val unitPrice: Double,
    val purchasePrice: Double,
    val quantity: Int,
    val availableInActiveStore: Int
) {
    val total: Double get() = unitPrice * quantity
}

data class SaleValidationResult(
    val isSuccess: Boolean,
    val saleId: String? = null,
    val saleNumber: String? = null,
    val errorMessage: String? = null
)

data class TransferValidationResult(
    val isSuccess: Boolean,
    val transferId: String? = null,
    val errorMessage: String? = null
)

data class AIInsight(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val category: String, // "RUPTURE", "TRANSFERT", "CREDIT", "OPPORTUNITE"
    val severity: String, // "URGENT", "WARNING", "INFO"
    val summary: String,
    val recommendation: String,
    val targetStore: String? = null
)
