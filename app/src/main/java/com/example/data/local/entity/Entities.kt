package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "products",
    indices = [Index(value = ["code"], unique = true)]
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val name: String,
    val category: String,
    val purchasePrice: Double, // Donnée confidentielle - masquée pour les vendeurs
    val sellingPrice: Double,
    val minStockAlert: Int = 5,
    val unit: String = "Pcs",
    val barcode: String = "",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "store_stocks",
    primaryKeys = ["storeId", "productId"],
    indices = [Index(value = ["productId"])]
)
data class StoreStockEntity(
    val storeId: String, // "A1", "A2", "A3", "A4", "A5"
    val productId: Long,
    val quantity: Int, // Règle stricte: doit toujours être >= 0
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String,
    val address: String,
    val creditLimit: Double,
    val currentDebt: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sales",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["saleNumber"], unique = true),
        Index(value = ["storeId"]),
        Index(value = ["customerId"])
    ]
)
data class SaleEntity(
    @PrimaryKey val id: String, // UUID v4 unique
    val saleNumber: String,
    val storeId: String, // A1..A5
    val customerId: Long? = null,
    val customerName: String? = null,
    val sellerId: String,
    val sellerName: String,
    val saleType: String, // "CASH", "CREDIT"
    val totalAmount: Double,
    val paidAmount: Double,
    val remainingAmount: Double,
    val paymentMethod: String,
    val status: String = "COMPLETED", // "COMPLETED", "CANCELLED"
    val idempotencyKey: String, // Déduplication stricte lors des retries
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sale_items",
    indices = [Index(value = ["saleId"])]
)
data class SaleItemEntity(
    @PrimaryKey val id: String,
    val saleId: String,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val unitPrice: Double,
    val quantity: Int,
    val total: Double
)

@Entity(
    tableName = "stock_movements",
    indices = [Index(value = ["productId"]), Index(value = ["referenceId"])]
)
data class StockMovementEntity(
    @PrimaryKey val id: String,
    val movementNumber: String,
    val productId: Long,
    val productName: String,
    val fromStoreId: String? = null,
    val toStoreId: String? = null,
    val quantity: Int,
    val movementType: String, // "VENTE", "TRANSFERT", "ENTREE", "AJUSTEMENT"
    val referenceId: String,
    val reason: String,
    val performedBy: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "debt_payments",
    indices = [Index(value = ["customerId"]), Index(value = ["idempotencyKey"], unique = true)]
)
data class DebtPaymentEntity(
    @PrimaryKey val id: String,
    val customerId: Long,
    val customerName: String,
    val amountPaid: Double,
    val paymentMethod: String,
    val note: String,
    val receiptNumber: String,
    val idempotencyKey: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "pending_sync_operations",
    indices = [Index(value = ["idempotencyKey"], unique = true), Index(value = ["status"])]
)
data class PendingSyncOperationEntity(
    @PrimaryKey val mutationId: String,
    val entityType: String, // "SALE", "TRANSFER", "CUSTOMER", "DEBT_PAYMENT", "STOCK_ADJUSTMENT"
    val action: String, // "CREATE", "UPDATE"
    val payloadJson: String,
    val idempotencyKey: String,
    val status: String = "PENDING", // "PENDING", "SYNCING", "SYNCED", "FAILED"
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null
)

@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val id: Int = 1,
    val lastCursor: Long = 0L,
    val stagingApiUrl: String = "",
    val isSimulatedOnline: Boolean = true,
    val lastSyncTimestamp: Long = 0L
)

@Entity(tableName = "user_preferences")
data class UserPreferenceEntity(
    @PrimaryKey val id: Int = 1,
    val activeRole: String = "ADMIN", // "ADMIN", "GERANT", "VENDEUR"
    val activeStoreId: String = "A1", // A1..A5
    val operatorName: String = "Harilala R. (Admin)",
    val activeTransferIdempotencyKey: String? = null
)

@Entity(
    tableName = "transfer_requests",
    indices = [Index(value = ["status"])]
)
data class TransferRequestEntity(
    @PrimaryKey val idempotencyKey: String,
    val productId: Long,
    val productName: String,
    val fromStoreId: String,
    val toStoreId: String,
    val quantity: Int,
    val operatorName: String,
    val reason: String,
    val status: String,
    val createdAt: Long,
    val completedAt: Long?
)
