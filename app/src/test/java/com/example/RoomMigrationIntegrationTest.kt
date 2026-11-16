package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
import com.example.data.local.MIGRATION_3_4
import com.example.data.local.MIGRATION_4_5
import com.example.data.local.MIGRATION_5_6
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomMigrationIntegrationTest {

    private fun loadVersion2Schema(): JSONObject {
        val stream = javaClass.classLoader
            ?.getResourceAsStream(
                "com/example/data/local/FanevaDatabase/2.json"
            ) ?: error("Schema 2.json introuvable dans test resources")

        val json = stream.bufferedReader().use { it.readText() }
        return JSONObject(json).getJSONObject("database")
    }

    private fun createVersion2Database(
        context: Context,
        dbName: String
    ) {
        val schema = loadVersion2Schema()

        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val entities = schema.getJSONArray("entities")

                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val tableName = entity.getString("tableName")
                    val createTable = entity.getString("createSql")
                        .replace("\${TABLE_NAME}", tableName)

                    db.execSQL(createTable)

                    val indices = entity.optJSONArray("indices")
                        ?: JSONArray()

                    for (j in 0 until indices.length()) {
                        val indexSql = indices.getJSONObject(j)
                            .getString("createSql")
                            .replace("\${TABLE_NAME}", tableName)

                        db.execSQL(indexSql)
                    }
                }

                val setupQueries = schema.getJSONArray("setupQueries")
                for (i in 0 until setupQueries.length()) {
                    db.execSQL(setupQueries.getString(i))
                }
            }

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
            ) {
                throw AssertionError(
                    "Unexpected upgrade while creating v2 database"
                )
            }
        }

        val configuration = SupportSQLiteOpenHelper.Configuration
            .builder(context)
            .name(dbName)
            .callback(callback)
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory()
            .create(configuration)

        try {
            val db = helper.writableDatabase
            assertEquals(2, db.version)

            db.execSQL(
                """
                INSERT INTO auth_accounts
                (id, username, displayName, role, passwordSalt,
                 passwordHash, isActive, createdAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "legacy-admin",
                    "admin01",
                    "Admin Legacy",
                    "ADMIN",
                    "salt-admin",
                    "hash-admin",
                    1,
                    1000L
                )
            )

            db.execSQL(
                """
                INSERT INTO auth_accounts
                (id, username, displayName, role, passwordSalt,
                 passwordHash, isActive, createdAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "legacy-seller",
                    "seller01",
                    "Seller Legacy",
                    "VENDEUR",
                    "salt-seller",
                    "hash-seller",
                    0,
                    2000L
                )
            )

            db.execSQL(
                """
                INSERT INTO user_preferences
                (id, activeRole, activeStoreId, operatorName)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    1,
                    "ADMIN",
                    "A1",
                    "Admin Legacy"
                )
            )

            // Business fixtures: verify that the full V2 domain survives the 2 -> 7 upgrade.
            db.execSQL(
                """
                INSERT INTO products
                (id, code, name, category, purchasePrice, sellingPrice,
                 minStockAlert, unit, barcode, isActive, createdAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(101L, "TEST-001", "Produit Test", "Quincaillerie",
                    12000.0, 15000.0, 3, "Pcs", "TESTBAR001", 1, 3000L)
            )
            db.execSQL(
                """
                INSERT INTO store_stocks (storeId, productId, quantity, lastUpdated)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>("A1", 101L, 17, 3100L)
            )
            db.execSQL(
                """
                INSERT INTO customers
                (id, name, phone, address, creditLimit, currentDebt, createdAt)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(201L, "Client Test", "+261000000001", "Adresse Test",
                    200000.0, 45000.0, 3200L)
            )
            db.execSQL(
                """
                INSERT INTO sales
                (id, saleNumber, storeId, customerId, customerName, sellerId,
                 sellerName, saleType, totalAmount, paidAmount, remainingAmount,
                 paymentMethod, status, idempotencyKey, createdAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>("sale-test-001", "TEST-VNT-001", "A1", 201L,
                    "Client Test", "seller-test", "Vendeur Test", "CREDIT",
                    60000.0, 15000.0, 45000.0, "CASH", "COMPLETED",
                    "sale-idem-test-001", 3300L)
            )
            db.execSQL(
                """
                INSERT INTO sale_items
                (id, saleId, productId, productCode, productName,
                 unitPrice, quantity, total)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>("sale-item-test-001", "sale-test-001", 101L,
                    "TEST-001", "Produit Test", 15000.0, 4, 60000.0)
            )
            db.execSQL(
                """
                INSERT INTO stock_movements
                (id, movementNumber, productId, productName, fromStoreId,
                 toStoreId, quantity, movementType, referenceId, reason,
                 performedBy, createdAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>("movement-test-001", "TEST-MVT-001", 101L,
                    "Produit Test", "A1", null, 4, "VENTE",
                    "sale-test-001", "Mouvement test", "Admin Test", 3400L)
            )
            db.execSQL(
                """
                INSERT INTO debt_payments
                (id, customerId, customerName, amountPaid, paymentMethod,
                 note, receiptNumber, idempotencyKey, createdAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>("payment-test-001", 201L, "Client Test", 5000.0,
                    "CASH", "Paiement test", "TEST-REC-001",
                    "payment-idem-test-001", 3500L)
            )
            db.execSQL(
                """
                INSERT INTO pending_sync_operations
                (mutationId, entityType, action, payloadJson, idempotencyKey,
                 status, retryCount, lastError, createdAt, syncedAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>("mutation-test-001", "SALE", "CREATE",
                    "{\"saleId\":\"sale-test-001\"}", "mutation-idem-test-001",
                    "PENDING", 0, null, 3600L, null)
            )
        } finally {
            helper.close()
        }
    }

    @Test
    fun roomAutomaticallyMigratesVersion2ToVersion7AndPreservesBusinessData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "room_migration_integration_test.db"

        context.deleteDatabase(dbName)
        createVersion2Database(context, dbName)

        val roomDb = Room.databaseBuilder(
            context,
            FanevaDatabase::class.java,
            dbName
        )
            .addMigrations(
                FanevaDatabase.MIGRATION_1_2,
                FanevaDatabase.MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                FanevaDatabase.MIGRATION_6_7
            )
            .build()

        try {
            // Opening the database triggers Room migrations and schema validation.
            val db = roomDb.openHelper.writableDatabase
            assertEquals("Database version", 7, db.version)

            db.query(
                "SELECT code, name, purchasePrice, sellingPrice FROM products WHERE id = 101"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("TEST-001", cursor.getString(0))
                assertEquals("Produit Test", cursor.getString(1))
                assertEquals(12000.0, cursor.getDouble(2), 0.0)
                assertEquals(15000.0, cursor.getDouble(3), 0.0)
            }
            db.query(
                "SELECT storeId, productId, quantity FROM store_stocks WHERE productId = 101"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("A1", cursor.getString(0))
                assertEquals(101L, cursor.getLong(1))
                assertEquals(17, cursor.getInt(2))
            }
            db.query(
                "SELECT name, creditLimit, currentDebt FROM customers WHERE id = 201"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Client Test", cursor.getString(0))
                assertEquals(200000.0, cursor.getDouble(1), 0.0)
                assertEquals(45000.0, cursor.getDouble(2), 0.0)
            }
            db.query(
                "SELECT saleNumber, totalAmount, paidAmount, remainingAmount FROM sales WHERE id = 'sale-test-001'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("TEST-VNT-001", cursor.getString(0))
                assertEquals(60000.0, cursor.getDouble(1), 0.0)
                assertEquals(15000.0, cursor.getDouble(2), 0.0)
                assertEquals(45000.0, cursor.getDouble(3), 0.0)
            }
            db.query(
                "SELECT productCode, quantity, total, purchasePriceSnapshot FROM sale_items WHERE id = 'sale-item-test-001'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("TEST-001", cursor.getString(0))
                assertEquals(4, cursor.getInt(1))
                assertEquals(60000.0, cursor.getDouble(2), 0.0)
                assertTrue("Legacy purchase-price snapshot must remain unknown", cursor.isNull(3))
            }
            db.query(
                "SELECT movementNumber, quantity, movementType FROM stock_movements WHERE id = 'movement-test-001'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("TEST-MVT-001", cursor.getString(0))
                assertEquals(4, cursor.getInt(1))
                assertEquals("VENTE", cursor.getString(2))
            }
            db.query(
                "SELECT receiptNumber, amountPaid FROM debt_payments WHERE id = 'payment-test-001'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("TEST-REC-001", cursor.getString(0))
                assertEquals(5000.0, cursor.getDouble(1), 0.0)
            }
            db.query(
                "SELECT mutationId, status, payloadJson FROM pending_sync_operations WHERE mutationId = 'mutation-test-001'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("mutation-test-001", cursor.getString(0))
                assertEquals("PENDING", cursor.getString(1))
                assertEquals("{\"saleId\":\"sale-test-001\"}", cursor.getString(2))
            }

            val accountColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(auth_accounts)").use { cursor ->
                while (cursor.moveToNext()) {
                    accountColumns.add(cursor.getString(1))
                }
            }

            assertTrue(accountColumns.contains("accountStatus"))
            assertTrue(accountColumns.contains("activationTokenHash"))
            assertTrue(accountColumns.contains("activationExpiresAt"))
            assertTrue(accountColumns.contains("activationFailedAttempts"))
            assertTrue(accountColumns.contains("activatedAt"))
            assertTrue(accountColumns.contains("mustChangePassword"))

            db.query(
                """
                SELECT id, username, role, isActive, accountStatus,
                       activationTokenHash, activationExpiresAt,
                       activationFailedAttempts, activatedAt,
                       mustChangePassword
                FROM auth_accounts
                ORDER BY id
                """.trimIndent()
            ).use { cursor ->
                assertEquals(2, cursor.count)
                assertTrue(cursor.moveToFirst())

                assertEquals("legacy-admin", cursor.getString(0))
                assertEquals("admin01", cursor.getString(1))
                assertEquals("ADMIN", cursor.getString(2))
                assertEquals(1, cursor.getInt(3))
                assertEquals("ACTIVE", cursor.getString(4))
                assertTrue(cursor.isNull(5))
                assertTrue(cursor.isNull(6))
                assertEquals(0, cursor.getInt(7))
                assertTrue(cursor.isNull(8))
                assertEquals(0, cursor.getInt(9))

                assertTrue(cursor.moveToNext())
                assertEquals("legacy-seller", cursor.getString(0))
                assertEquals("seller01", cursor.getString(1))
                assertEquals("VENDEUR", cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals("DISABLED", cursor.getString(4))
                assertTrue(cursor.isNull(5))
                assertTrue(cursor.isNull(6))
                assertEquals(0, cursor.getInt(7))
                assertTrue(cursor.isNull(8))
                assertEquals(0, cursor.getInt(9))
            }

            db.query(
                """
                SELECT activeRole, activeStoreId, operatorName,
                       activeTransferIdempotencyKey
                FROM user_preferences
                WHERE id = 1
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ADMIN", cursor.getString(0))
                assertEquals("A1", cursor.getString(1))
                assertEquals("Admin Legacy", cursor.getString(2))
                assertTrue(cursor.isNull(3))
            }

            db.query(
                """
                SELECT name
                FROM sqlite_master
                WHERE type = 'table' AND name = 'admin_recovery_credentials'
                """.trimIndent()
            ).use { cursor ->
                assertTrue("admin_recovery_credentials table missing", cursor.moveToFirst())
            }

            db.query(
                """
                SELECT accountId, storeId, grantedBy
                FROM auth_account_store_access
                ORDER BY accountId
                """.trimIndent()
            ).use { cursor ->
                assertEquals("Only active legacy account receives least-privilege access", 1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy-admin", cursor.getString(0))
                assertEquals("A1", cursor.getString(1))
                assertEquals("MIGRATION_V5_6", cursor.getString(2))
            }

            db.query(
                """
                SELECT name
                FROM sqlite_master
                WHERE type = 'table' AND name = 'auth_sessions'
                """.trimIndent()
            ).use { cursor ->
                assertTrue("auth_sessions table missing", cursor.moveToFirst())
            }

            db.query(
                """
                SELECT name
                FROM sqlite_master
                WHERE type = 'table' AND name = 'transfer_requests'
                """.trimIndent()
            ).use { cursor ->
                assertTrue(
                    "transfer_requests table missing",
                    cursor.moveToFirst()
                )
            }

            db.query(
                """
                SELECT name
                FROM sqlite_master
                WHERE type = 'index'
                  AND name = 'index_transfer_requests_status'
                """.trimIndent()
            ).use { cursor ->
                assertTrue(
                    "transfer_requests index missing",
                    cursor.moveToFirst()
                )
            }
        } finally {
            roomDb.close()
            context.deleteDatabase(dbName)
        }
    }
}
