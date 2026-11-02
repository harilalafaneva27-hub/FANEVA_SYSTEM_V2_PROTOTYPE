package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT COUNT(*) FROM products")
    suspend fun countAll(): Int

    @Query("SELECT * FROM products WHERE isActive = 1 ORDER BY name ASC")
    fun getAllProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    suspend fun getProductById(id: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE code = :code LIMIT 1")
    suspend fun getProductByCode(code: String): ProductEntity?

    @Query("SELECT * FROM products WHERE isActive = 1 AND (name LIKE '%' || :query || '%' OR code LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%') ORDER BY name ASC")
    fun searchProducts(query: String): Flow<List<ProductEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: ProductEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<ProductEntity>)

    @Update
    suspend fun updateProduct(product: ProductEntity)

    @Query("UPDATE products SET isActive = 0 WHERE id = :id")
    suspend fun softDeleteProduct(id: Long)
}

@Dao
interface StoreStockDao {
    @Query("SELECT * FROM store_stocks WHERE productId = :productId AND storeId = :storeId LIMIT 1")
    suspend fun getStock(productId: Long, storeId: String): StoreStockEntity?

    @Query("SELECT * FROM store_stocks WHERE productId = :productId AND storeId = :storeId LIMIT 1")
    fun getStockFlow(productId: Long, storeId: String): Flow<StoreStockEntity?>

    @Query("SELECT * FROM store_stocks WHERE storeId = :storeId")
    fun getStocksByStore(storeId: String): Flow<List<StoreStockEntity>>

    @Query("SELECT * FROM store_stocks")
    fun getAllStocks(): Flow<List<StoreStockEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateStock(stock: StoreStockEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stocks: List<StoreStockEntity>)

    @Query("UPDATE store_stocks SET quantity = :newQty, lastUpdated = :now WHERE productId = :productId AND storeId = :storeId AND :newQty >= 0 AND quantity >= 0")
    suspend fun updateQuantity(productId: Long, storeId: String, newQty: Int, now: Long = System.currentTimeMillis()): Int
}

@Dao
interface CustomerDao {
    @Query("SELECT COUNT(*) FROM customers")
    suspend fun countAll(): Int

    @Query("SELECT * FROM customers ORDER BY name ASC")
    fun getAllCustomers(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    suspend fun getCustomerById(id: Long): CustomerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: CustomerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(customers: List<CustomerEntity>)

    @Update
    suspend fun updateCustomer(customer: CustomerEntity)

    @Query("UPDATE customers SET currentDebt = :newDebt WHERE id = :customerId")
    suspend fun updateDebt(customerId: Long, newDebt: Double)
}

@Dao
interface SaleDao {
    @Query("SELECT COUNT(*) FROM sales")
    suspend fun countAll(): Int

    @Query("SELECT * FROM sales ORDER BY createdAt DESC")
    fun getAllSales(): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sales WHERE storeId = :storeId ORDER BY createdAt DESC")
    fun getSalesByStore(storeId: String): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sales WHERE id = :id LIMIT 1")
    suspend fun getSaleById(id: String): SaleEntity?

    @Query("SELECT * FROM sales WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun getSaleByIdempotencyKey(idempotencyKey: String): SaleEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSale(sale: SaleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSaleItems(items: List<SaleItemEntity>)

    @Query("SELECT * FROM sale_items WHERE saleId = :saleId")
    suspend fun getSaleItems(saleId: String): List<SaleItemEntity>

    @Query("SELECT * FROM sale_items WHERE saleId = :saleId")
    fun getSaleItemsFlow(saleId: String): Flow<List<SaleItemEntity>>
}

@Dao
interface StockMovementDao {
    @Query("SELECT COUNT(*) FROM stock_movements")
    suspend fun countAll(): Int

    @Query("SELECT * FROM stock_movements ORDER BY createdAt DESC")
    fun getAllMovements(): Flow<List<StockMovementEntity>>

    @Query("SELECT * FROM stock_movements WHERE fromStoreId = :storeId OR toStoreId = :storeId ORDER BY createdAt DESC")
    fun getMovementsByStore(storeId: String): Flow<List<StockMovementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovement(movement: StockMovementEntity)
}

@Dao
interface DebtPaymentDao {
    @Query("SELECT COUNT(*) FROM debt_payments")
    suspend fun countAll(): Int

    @Query("SELECT * FROM debt_payments ORDER BY createdAt DESC")
    fun getAllPayments(): Flow<List<DebtPaymentEntity>>

    @Query("SELECT * FROM debt_payments ORDER BY createdAt DESC")
    suspend fun getAllPaymentsSnapshot(): List<DebtPaymentEntity>

    @Query("SELECT * FROM debt_payments WHERE customerId = :customerId ORDER BY createdAt DESC")
    fun getPaymentsForCustomer(customerId: Long): Flow<List<DebtPaymentEntity>>

    @Query("SELECT * FROM debt_payments WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun getPaymentByIdempotencyKey(idempotencyKey: String): DebtPaymentEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPayment(payment: DebtPaymentEntity)
}

@Dao
interface PendingSyncDao {
    @Query("SELECT COUNT(*) FROM pending_sync_operations")
    suspend fun countAll(): Int

    @Query("SELECT * FROM pending_sync_operations ORDER BY createdAt ASC")
    fun getAllPendingOperations(): Flow<List<PendingSyncOperationEntity>>

    @Query("SELECT * FROM pending_sync_operations ORDER BY createdAt ASC")
    suspend fun getPendingOperationsSnapshot(): List<PendingSyncOperationEntity>

    @Query("SELECT COUNT(*) FROM pending_sync_operations WHERE status IN ('PENDING', 'SYNCING', 'FAILED')")
    fun getPendingCount(): Flow<Int>

    @Query("SELECT * FROM pending_sync_operations WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun getByIdempotencyKey(idempotencyKey: String): PendingSyncOperationEntity?

    @Query("UPDATE pending_sync_operations SET status = 'PENDING', lastError = 'Reprise après interruption locale' WHERE status = 'SYNCING'")
    suspend fun recoverAbandonedSyncingOperations(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(op: PendingSyncOperationEntity)

    @Update
    suspend fun updateOperation(op: PendingSyncOperationEntity)

    @Query("DELETE FROM pending_sync_operations WHERE mutationId = :mutationId")
    suspend fun deleteOperation(mutationId: String)

    @Query("DELETE FROM pending_sync_operations WHERE status = 'SYNCED'")
    suspend fun purgeSyncedOperations()
}

@Dao
interface SyncCursorDao {
    @Query("SELECT * FROM sync_cursor WHERE id = 1 LIMIT 1")
    fun getCursorFlow(): Flow<SyncCursorEntity?>

    @Query("SELECT * FROM sync_cursor WHERE id = 1 LIMIT 1")
    suspend fun getCursor(): SyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCursor(cursor: SyncCursorEntity)
}

@Dao
interface UserPreferenceDao {
    @Query("SELECT * FROM user_preferences WHERE id = 1 LIMIT 1")
    fun getPreferencesFlow(): Flow<UserPreferenceEntity?>

    @Query("SELECT * FROM user_preferences WHERE id = 1 LIMIT 1")
    suspend fun getPreferences(): UserPreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePreferences(pref: UserPreferenceEntity)

    @Query("UPDATE user_preferences SET activeTransferIdempotencyKey = NULL WHERE id = 1 AND activeTransferIdempotencyKey = :key")
    suspend fun clearActiveTransferKey(key: String): Int
}

@Dao
interface TransferRequestDao {
    @Query("SELECT * FROM transfer_requests WHERE idempotencyKey = :key LIMIT 1")
    suspend fun getByIdempotencyKey(key: String): TransferRequestEntity?

    @Query("SELECT * FROM transfer_requests WHERE status = 'PREPARED' ORDER BY createdAt ASC")
    suspend fun getPreparedRequests(): List<TransferRequestEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRequest(request: TransferRequestEntity)

    @Update
    suspend fun updateRequest(request: TransferRequestEntity): Int
}
