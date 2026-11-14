package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.*
import com.example.data.local.entity.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS auth_sessions (
                sessionId TEXT NOT NULL,
                userId TEXT NOT NULL,
                role TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                expiresAt INTEGER NOT NULL,
                status TEXT NOT NULL,
                lastSeenAt INTEGER NOT NULL,
                PRIMARY KEY(sessionId)
            )
        """.trimIndent())
        database.execSQL("CREATE INDEX IF NOT EXISTS index_auth_sessions_userId ON auth_sessions(userId)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_auth_sessions_expiresAt ON auth_sessions(expiresAt)")
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS auth_account_store_access (
                accountId TEXT NOT NULL,
                storeId TEXT NOT NULL,
                grantedAt INTEGER NOT NULL,
                grantedBy TEXT NOT NULL,
                PRIMARY KEY(accountId, storeId)
            )
        """.trimIndent())
        database.execSQL("CREATE INDEX IF NOT EXISTS index_auth_account_store_access_storeId ON auth_account_store_access(storeId)")
        // Historical accounts are not granted store access implicitly. The owner must explicitly assign stores.
    }
}

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS admin_recovery_credentials (
                id INTEGER NOT NULL,
                salt TEXT NOT NULL,
                codeHash TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                failedAttempts INTEGER NOT NULL,
                consumedAt INTEGER,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
        // Retire the legacy hidden administrator that depended on the public hardcoded code.
        database.execSQL(
            "UPDATE auth_accounts SET isActive = 0, accountStatus = 'DISABLED' WHERE username = '__admin__'"
        )
    }
}

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE auth_accounts ADD COLUMN accountStatus TEXT NOT NULL DEFAULT 'ACTIVE'")
        database.execSQL("UPDATE auth_accounts SET accountStatus = CASE WHEN isActive = 1 THEN 'ACTIVE' ELSE 'DISABLED' END")
        database.execSQL("ALTER TABLE auth_accounts ADD COLUMN activationTokenHash TEXT")
        database.execSQL("ALTER TABLE auth_accounts ADD COLUMN activationExpiresAt INTEGER")
        database.execSQL("ALTER TABLE auth_accounts ADD COLUMN activationFailedAttempts INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE auth_accounts ADD COLUMN activatedAt INTEGER")
        database.execSQL("ALTER TABLE auth_accounts ADD COLUMN mustChangePassword INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [
        ProductEntity::class,
        StoreStockEntity::class,
        CustomerEntity::class,
        SaleEntity::class,
        SaleItemEntity::class,
        StockMovementEntity::class,
        DebtPaymentEntity::class,
        PendingSyncOperationEntity::class,
        SyncCursorEntity::class,
        UserPreferenceEntity::class,
        AuthAccountEntity::class,
        TransferRequestEntity::class,
        AdminRecoveryCredentialEntity::class,
        AuthSessionEntity::class,
        AuthAccountStoreAccessEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class FanevaDatabase : RoomDatabase() {

    abstract fun productDao(): ProductDao
    abstract fun storeStockDao(): StoreStockDao
    abstract fun customerDao(): CustomerDao
    abstract fun saleDao(): SaleDao
    abstract fun stockMovementDao(): StockMovementDao
    abstract fun debtPaymentDao(): DebtPaymentDao
    abstract fun pendingSyncDao(): PendingSyncDao
    abstract fun syncCursorDao(): SyncCursorDao
    abstract fun userPreferenceDao(): UserPreferenceDao
    abstract fun authDao(): AuthDao
    abstract fun transferRequestDao(): TransferRequestDao
    abstract fun adminRecoveryDao(): AdminRecoveryDao
    abstract fun securitySessionDao(): SecuritySessionDao

    companion object {
        @Volatile
        private var INSTANCE: FanevaDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): FanevaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FanevaDatabase::class.java,
                    "faneva_v2_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .addCallback(FanevaDatabaseCallback(scope))
                    .build()
                INSTANCE = instance
                instance
            }
        }

    val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE user_preferences ADD COLUMN activeTransferIdempotencyKey TEXT"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS transfer_requests (
                    idempotencyKey TEXT NOT NULL,
                    productId INTEGER NOT NULL,
                    productName TEXT NOT NULL,
                    fromStoreId TEXT NOT NULL,
                    toStoreId TEXT NOT NULL,
                    quantity INTEGER NOT NULL,
                    operatorName TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    status TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    completedAt INTEGER,
                    PRIMARY KEY(idempotencyKey)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_transfer_requests_status " +
                "ON transfer_requests(status)"
            )
        }
    }



        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS auth_accounts (
                        id TEXT NOT NULL,
                        username TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        role TEXT NOT NULL,
                        passwordSalt TEXT NOT NULL,
                        passwordHash TEXT NOT NULL,
                        isActive INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )""".trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_auth_accounts_username ON auth_accounts(username)")
            }
        }

        private class FanevaDatabaseCallback(
            private val scope: CoroutineScope
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    scope.launch(Dispatchers.IO) {
                        populateInitialDemoData(database)
                    }
                }
            }
        }

        suspend fun populateInitialDemoData(db: FanevaDatabase) {
            val productDao = db.productDao()
            val stockDao = db.storeStockDao()
            val customerDao = db.customerDao()
            val userPrefDao = db.userPreferenceDao()
            val syncCursorDao = db.syncCursorDao()

            // La démo ne peut être créée que dans une base réellement vide.
            // Cette garde protège les données utilisateur même si cette méthode est rappelée.
            if (productDao.countAll() > 0 || customerDao.countAll() > 0 ||
                db.saleDao().countAll() > 0 || db.stockMovementDao().countAll() > 0 ||
                db.debtPaymentDao().countAll() > 0 || db.pendingSyncDao().countAll() > 0) {
                return
            }

            // 1. Initial Preferences & Cursor
            userPrefDao.savePreferences(
                UserPreferenceEntity(
                    id = 1,
                    activeRole = "ADMIN",
                    activeStoreId = "A1",
                    operatorName = "Harilala R. (Admin Principal)"
                )
            )

            syncCursorDao.saveCursor(
                SyncCursorEntity(
                    id = 1,
                    lastCursor = 1042L,
                    stagingApiUrl = "",
                    isSimulatedOnline = true,
                    lastSyncTimestamp = System.currentTimeMillis() - 15 * 60 * 1000
                )
            )

            // 2. Initial Sample Products
            val sampleProducts = listOf(
                ProductEntity(
                    id = 1,
                    code = "PRD-CIM-01",
                    name = "Ciment CPJ 42.5 (Sac 50kg)",
                    category = "Matériaux",
                    purchasePrice = 32000.0, // Confidentiel
                    sellingPrice = 38500.0,
                    minStockAlert = 20,
                    unit = "Sac",
                    barcode = "600123450001"
                ),
                ProductEntity(
                    id = 2,
                    code = "PRD-FER-12",
                    name = "Fer à béton Haute Adhérence 12mm",
                    category = "Matériaux",
                    purchasePrice = 24000.0,
                    sellingPrice = 29000.0,
                    minStockAlert = 15,
                    unit = "Barre 12m",
                    barcode = "600123450002"
                ),
                ProductEntity(
                    id = 3,
                    code = "PRD-DIS-230",
                    name = "Disque à tronçonner diamant 230mm",
                    category = "Outillage",
                    purchasePrice = 18000.0,
                    sellingPrice = 24500.0,
                    minStockAlert = 8,
                    unit = "Pcs",
                    barcode = "600123450003"
                ),
                ProductEntity(
                    id = 4,
                    code = "PRD-DISJ-16",
                    name = "Disjoncteur Différentiel 16A Schneider",
                    category = "Électricité",
                    purchasePrice = 14500.0,
                    sellingPrice = 21000.0,
                    minStockAlert = 10,
                    unit = "Pcs",
                    barcode = "600123450004"
                ),
                ProductEntity(
                    id = 5,
                    code = "PRD-TUY-PVC",
                    name = "Tuyau Évacuation PVC Ø100 4m",
                    category = "Plomberie",
                    purchasePrice = 16000.0,
                    sellingPrice = 22500.0,
                    minStockAlert = 12,
                    unit = "Barre",
                    barcode = "600123450005"
                ),
                ProductEntity(
                    id = 6,
                    code = "PRD-PNT-GLY",
                    name = "Peinture Glycéro Blanche 5L Seigneurie",
                    category = "Peinture",
                    purchasePrice = 75000.0,
                    sellingPrice = 98000.0,
                    minStockAlert = 6,
                    unit = "Pot 5L",
                    barcode = "600123450006"
                ),
                ProductEntity(
                    id = 7,
                    code = "PRD-VIS-450",
                    name = "Boîte Vis Bois Agglo 4x50 (500 pcs)",
                    category = "Quincaillerie",
                    purchasePrice = 11000.0,
                    sellingPrice = 16500.0,
                    minStockAlert = 5,
                    unit = "Boîte",
                    barcode = "600123450007"
                ),
                ProductEntity(
                    id = 8,
                    code = "PRD-ROB-MEL",
                    name = "Mitigeur Lavabo Chromé Laiton",
                    category = "Plomberie",
                    purchasePrice = 48000.0,
                    sellingPrice = 69000.0,
                    minStockAlert = 4,
                    unit = "Pcs",
                    barcode = "600123450008"
                )
            )
            productDao.insertAll(sampleProducts)

            // 3. Multi-Store Stock distribution (A1..A5)
            // A1: Central, A2: Depot Nord, A3: Marche, A4: Showroom Sud, A5: Comptoir Express
            val initialStocks = listOf(
                // Ciment
                StoreStockEntity("A1", 1, 140),
                StoreStockEntity("A2", 1, 280),
                StoreStockEntity("A3", 1, 4), // Rupture proche
                StoreStockEntity("A4", 1, 25),
                StoreStockEntity("A5", 1, 10),

                // Fer à béton
                StoreStockEntity("A1", 2, 85),
                StoreStockEntity("A2", 2, 190),
                StoreStockEntity("A3", 2, 2), // Alerte
                StoreStockEntity("A4", 2, 15),
                StoreStockEntity("A5", 2, 8),

                // Disque 230mm
                StoreStockEntity("A1", 3, 32),
                StoreStockEntity("A2", 3, 40),
                StoreStockEntity("A3", 3, 14),
                StoreStockEntity("A4", 3, 9),
                StoreStockEntity("A5", 3, 5),

                // Disjoncteur
                StoreStockEntity("A1", 4, 45),
                StoreStockEntity("A2", 4, 60),
                StoreStockEntity("A3", 4, 18),
                StoreStockEntity("A4", 4, 22),
                StoreStockEntity("A5", 4, 7),

                // Tuyau PVC
                StoreStockEntity("A1", 5, 50),
                StoreStockEntity("A2", 5, 110),
                StoreStockEntity("A3", 5, 8),
                StoreStockEntity("A4", 5, 14),
                StoreStockEntity("A5", 5, 3),

                // Peinture 5L
                StoreStockEntity("A1", 6, 18),
                StoreStockEntity("A2", 6, 35),
                StoreStockEntity("A3", 6, 1), // Rupture urgente
                StoreStockEntity("A4", 6, 8),
                StoreStockEntity("A5", 6, 2),

                // Boîte Vis
                StoreStockEntity("A1", 7, 28),
                StoreStockEntity("A2", 7, 50),
                StoreStockEntity("A3", 7, 12),
                StoreStockEntity("A4", 7, 10),
                StoreStockEntity("A5", 7, 6),

                // Mitigeur
                StoreStockEntity("A1", 8, 12),
                StoreStockEntity("A2", 8, 20),
                StoreStockEntity("A3", 8, 3),
                StoreStockEntity("A4", 8, 9),
                StoreStockEntity("A5", 8, 1)
            )
            stockDao.insertAll(initialStocks)

            // 4. Sample Customers with credit balance
            val sampleCustomers = listOf(
                CustomerEntity(
                    id = 1,
                    name = "Entreprise BTP Tsara Trano",
                    phone = "+261 34 11 222 33",
                    address = "Zone Industrielle Tanjombato",
                    creditLimit = 3500000.0,
                    currentDebt = 1250000.0
                ),
                CustomerEntity(
                    id = 2,
                    name = "Maître Artisan Rakotondrabe",
                    phone = "+261 32 44 555 66",
                    address = "Marché Isotry Stand 42",
                    creditLimit = 800000.0,
                    currentDebt = 320000.0
                ),
                CustomerEntity(
                    id = 3,
                    name = "Société Immobilière Iarivo",
                    phone = "+261 33 77 888 99",
                    address = "Ambatobe Résidence",
                    creditLimit = 5000000.0,
                    currentDebt = 0.0
                ),
                CustomerEntity(
                    id = 4,
                    name = "Atelier Menuiserie Moderne",
                    phone = "+261 34 99 000 11",
                    address = "Anosizato Est",
                    creditLimit = 1200000.0,
                    currentDebt = 650000.0
                )
            )
            customerDao.insertAll(sampleCustomers)

            // 5. Initial Sample Completed Sale
            val saleDao = db.saleDao()
            val saleId = UUID.randomUUID().toString()
            val sale = SaleEntity(
                id = saleId,
                saleNumber = "VNT-2026-0001",
                storeId = "A1",
                customerId = 1,
                customerName = "Entreprise BTP Tsara Trano",
                sellerId = "OP-01",
                sellerName = "Soa K. (Vendeuse)",
                saleType = "CREDIT",
                totalAmount = 385000.0,
                paidAmount = 100000.0,
                remainingAmount = 285000.0,
                paymentMethod = "ESPECES",
                status = "COMPLETED",
                idempotencyKey = "IDEMP-INIT-VNT-0001",
                createdAt = System.currentTimeMillis() - 2 * 3600 * 1000
            )
            saleDao.insertSale(sale)
            saleDao.insertSaleItems(
                listOf(
                    SaleItemEntity(
                        id = UUID.randomUUID().toString(),
                        saleId = saleId,
                        productId = 1,
                        productCode = "PRD-CIM-01",
                        productName = "Ciment CPJ 42.5 (Sac 50kg)",
                        unitPrice = 38500.0,
                        quantity = 10,
                        total = 385000.0
                    )
                )
            )
        }
    }
}
