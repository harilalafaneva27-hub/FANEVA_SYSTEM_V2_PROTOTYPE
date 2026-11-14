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
        } finally {
            helper.close()
        }
    }

    @Test
    fun roomAutomaticallyMigratesVersion2ToVersion4AndPreservesData() {
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
                MIGRATION_4_5
            )
            .build()

        try {
            // Opening the database triggers Room migrations and schema validation.
            val db = roomDb.openHelper.writableDatabase
            assertEquals("Database version", 5, db.version)

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
