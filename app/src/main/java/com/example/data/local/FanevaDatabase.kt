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

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS server_id_mappings (
                entityType TEXT NOT NULL,
                localId TEXT NOT NULL,
                serverId TEXT NOT NULL,
                serverVersion INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(entityType, localId)
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_server_id_mappings_serverId ON server_id_mappings(serverId)"
        )
    }
}

val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_event_inbox (
                cursor INTEGER NOT NULL,
                eventId TEXT NOT NULL,
                storeId TEXT NOT NULL,
                entityType TEXT NOT NULL,
                entityId TEXT NOT NULL,
                eventType TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(cursor)
            )
            """.trimIndent()
        )
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_event_inbox_eventId ON sync_event_inbox(eventId)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_event_inbox_entityType_entityId ON sync_event_inbox(entityType, entityId)")
    }
}


val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {

    private fun validateMgaColumns(db: SupportSQLiteDatabase, table: String, columns: List<String>) {
        columns.forEach { column ->
            val cursor = db.query(
                "SELECT COUNT(*) FROM $table WHERE $column < 0 OR $column > 9007199254740991 OR $column != CAST($column AS INTEGER)"
            )
            cursor.use {
                check(it.moveToFirst() && it.getLong(0) == 0L) {
                    "Room migration 6->7 refused: $table.$column contains non-integer, negative, or unsafe MGA."
                }
            }
        }
    }

    override fun migrate(db: SupportSQLiteDatabase) {
        validateMgaColumns(db, "products", listOf("purchasePrice", "sellingPrice"))
        validateMgaColumns(db, "customers", listOf("creditLimit", "currentDebt"))
        validateMgaColumns(db, "sales", listOf("totalAmount", "paidAmount", "remainingAmount"))
        validateMgaColumns(db, "sale_items", listOf("unitPrice", "total"))
        validateMgaColumns(db, "debt_payments", listOf("amountPaid"))

        db.execSQL("""CREATE TABLE products_new (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, code TEXT NOT NULL, name TEXT NOT NULL,
            category TEXT NOT NULL, purchasePrice INTEGER NOT NULL, sellingPrice INTEGER NOT NULL,
            minStockAlert INTEGER NOT NULL, unit TEXT NOT NULL, barcode TEXT NOT NULL,
            isActive INTEGER NOT NULL, createdAt INTEGER NOT NULL)""")
        db.execSQL("""INSERT INTO products_new SELECT id, code, name, category,
            CAST(purchasePrice AS INTEGER), CAST(sellingPrice AS INTEGER), minStockAlert,
            unit, barcode, isActive, createdAt FROM products""")
        db.execSQL("DROP TABLE products")
        db.execSQL("ALTER TABLE products_new RENAME TO products")
        db.execSQL("CREATE UNIQUE INDEX index_products_code ON products(code)")

        db.execSQL("""CREATE TABLE customers_new (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL,
            address TEXT NOT NULL, creditLimit INTEGER NOT NULL, currentDebt INTEGER NOT NULL,
            createdAt INTEGER NOT NULL)""")
        db.execSQL("""INSERT INTO customers_new SELECT id, name, phone, address,
            CAST(creditLimit AS INTEGER), CAST(currentDebt AS INTEGER), createdAt FROM customers""")
        db.execSQL("DROP TABLE customers")
        db.execSQL("ALTER TABLE customers_new RENAME TO customers")

        db.execSQL("""CREATE TABLE sales_new (
            id TEXT NOT NULL PRIMARY KEY, saleNumber TEXT NOT NULL, storeId TEXT NOT NULL,
            customerId INTEGER, customerName TEXT, sellerId TEXT NOT NULL, sellerName TEXT NOT NULL,
            saleType TEXT NOT NULL, totalAmount INTEGER NOT NULL, paidAmount INTEGER NOT NULL,
            remainingAmount INTEGER NOT NULL, paymentMethod TEXT NOT NULL, status TEXT NOT NULL,
            idempotencyKey TEXT NOT NULL, createdAt INTEGER NOT NULL)""")
        db.execSQL("""INSERT INTO sales_new SELECT id, saleNumber, storeId, customerId, customerName,
            sellerId, sellerName, saleType, CAST(totalAmount AS INTEGER), CAST(paidAmount AS INTEGER),
            CAST(remainingAmount AS INTEGER), paymentMethod, status, idempotencyKey, createdAt FROM sales""")
        db.execSQL("DROP TABLE sales")
        db.execSQL("ALTER TABLE sales_new RENAME TO sales")
        db.execSQL("CREATE UNIQUE INDEX index_sales_idempotencyKey ON sales(idempotencyKey)")
        db.execSQL("CREATE UNIQUE INDEX index_sales_saleNumber ON sales(saleNumber)")
        db.execSQL("CREATE INDEX index_sales_storeId ON sales(storeId)")
        db.execSQL("CREATE INDEX index_sales_customerId ON sales(customerId)")

        db.execSQL("""CREATE TABLE sale_items_new (
            id TEXT NOT NULL PRIMARY KEY, saleId TEXT NOT NULL, productId INTEGER NOT NULL,
            productCode TEXT NOT NULL, productName TEXT NOT NULL, unitPrice INTEGER NOT NULL,
            quantity INTEGER NOT NULL, total INTEGER NOT NULL)""")
        db.execSQL("""INSERT INTO sale_items_new SELECT id, saleId, productId, productCode,
            productName, CAST(unitPrice AS INTEGER), quantity, CAST(total AS INTEGER) FROM sale_items""")
        db.execSQL("DROP TABLE sale_items")
        db.execSQL("ALTER TABLE sale_items_new RENAME TO sale_items")
        db.execSQL("CREATE INDEX index_sale_items_saleId ON sale_items(saleId)")

        db.execSQL("""CREATE TABLE debt_payments_new (
            id TEXT NOT NULL PRIMARY KEY, customerId INTEGER NOT NULL, customerName TEXT NOT NULL,
            amountPaid INTEGER NOT NULL, paymentMethod TEXT NOT NULL, note TEXT NOT NULL,
            receiptNumber TEXT NOT NULL, idempotencyKey TEXT NOT NULL, createdAt INTEGER NOT NULL)""")
        db.execSQL("""INSERT INTO debt_payments_new SELECT id, customerId, customerName,
            CAST(amountPaid AS INTEGER), paymentMethod, note, receiptNumber, idempotencyKey, createdAt
            FROM debt_payments""")
        db.execSQL("DROP TABLE debt_payments")
        db.execSQL("ALTER TABLE debt_payments_new RENAME TO debt_payments")
        db.execSQL("CREATE INDEX index_debt_payments_customerId ON debt_payments(customerId)")
        db.execSQL("CREATE UNIQUE INDEX index_debt_payments_idempotencyKey ON debt_payments(idempotencyKey)")
    }
}

val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS server_stock_versions (
            storeId TEXT NOT NULL, localProductId INTEGER NOT NULL, serverProductId TEXT NOT NULL,
            serverVersion INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
            PRIMARY KEY(storeId, localProductId))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_server_stock_versions_serverProductId ON server_stock_versions(serverProductId)")
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
        ServerIdMappingEntity::class,
        SyncEventInboxEntity::class,
        ServerStockVersionEntity::class
    ],
    version = 8,
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
    abstract fun serverIdMappingDao(): ServerIdMappingDao
    abstract fun syncEventInboxDao(): SyncEventInboxDao
    abstract fun serverStockVersionDao(): ServerStockVersionDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
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
                    purchasePrice = 32000L, // Confidentiel
                    sellingPrice = 38500L,
                    minStockAlert = 20,
                    unit = "Sac",
                    barcode = "600123450001"
                ),
                ProductEntity(
                    id = 2,
                    code = "PRD-FER-12",
                    name = "Fer à béton Haute Adhérence 12mm",
                    category = "Matériaux",
                    purchasePrice = 24000L,
                    sellingPrice = 29000L,
                    minStockAlert = 15,
                    unit = "Barre 12m",
                    barcode = "600123450002"
                ),
                ProductEntity(
                    id = 3,
                    code = "PRD-DIS-230",
                    name = "Disque à tronçonner diamant 230mm",
                    category = "Outillage",
                    purchasePrice = 18000L,
                    sellingPrice = 24500L,
                    minStockAlert = 8,
                    unit = "Pcs",
                    barcode = "600123450003"
                ),
                ProductEntity(
                    id = 4,
                    code = "PRD-DISJ-16",
                    name = "Disjoncteur Différentiel 16A Schneider",
                    category = "Électricité",
                    purchasePrice = 14500L,
                    sellingPrice = 21000L,
                    minStockAlert = 10,
                    unit = "Pcs",
                    barcode = "600123450004"
                ),
                ProductEntity(
                    id = 5,
                    code = "PRD-TUY-PVC",
                    name = "Tuyau Évacuation PVC Ø100 4m",
                    category = "Plomberie",
                    purchasePrice = 16000L,
                    sellingPrice = 22500L,
                    minStockAlert = 12,
                    unit = "Barre",
                    barcode = "600123450005"
                ),
                ProductEntity(
                    id = 6,
                    code = "PRD-PNT-GLY",
                    name = "Peinture Glycéro Blanche 5L Seigneurie",
                    category = "Peinture",
                    purchasePrice = 75000L,
                    sellingPrice = 98000L,
                    minStockAlert = 6,
                    unit = "Pot 5L",
                    barcode = "600123450006"
                ),
                ProductEntity(
                    id = 7,
                    code = "PRD-VIS-450",
                    name = "Boîte Vis Bois Agglo 4x50 (500 pcs)",
                    category = "Quincaillerie",
                    purchasePrice = 11000L,
                    sellingPrice = 16500L,
                    minStockAlert = 5,
                    unit = "Boîte",
                    barcode = "600123450007"
                ),
                ProductEntity(
                    id = 8,
                    code = "PRD-ROB-MEL",
                    name = "Mitigeur Lavabo Chromé Laiton",
                    category = "Plomberie",
                    purchasePrice = 48000L,
                    sellingPrice = 69000L,
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
                    creditLimit = 3500000L,
                    currentDebt = 1250000L
                ),
                CustomerEntity(
                    id = 2,
                    name = "Maître Artisan Rakotondrabe",
                    phone = "+261 32 44 555 66",
                    address = "Marché Isotry Stand 42",
                    creditLimit = 800000L,
                    currentDebt = 320000L
                ),
                CustomerEntity(
                    id = 3,
                    name = "Société Immobilière Iarivo",
                    phone = "+261 33 77 888 99",
                    address = "Ambatobe Résidence",
                    creditLimit = 5000000L,
                    currentDebt = 0L
                ),
                CustomerEntity(
                    id = 4,
                    name = "Atelier Menuiserie Moderne",
                    phone = "+261 34 99 000 11",
                    address = "Anosizato Est",
                    creditLimit = 1200000L,
                    currentDebt = 650000L
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
                totalAmount = 385000L,
                paidAmount = 100000L,
                remainingAmount = 285000L,
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
                        unitPrice = 38500L,
                        quantity = 10,
                        total = 385000L
                    )
                )
            )
        }
    }
}
