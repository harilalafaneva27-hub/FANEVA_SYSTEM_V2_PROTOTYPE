package com.example

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.MIGRATION_3_4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthMigrationTest {

    @Test
    fun `migration 3 to 4 preserves legacy accounts and applies defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "auth_migration_3_4_test.db"

        context.deleteDatabase(dbName)

        val callback = object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE auth_accounts (
                        id TEXT NOT NULL,
                        username TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        role TEXT NOT NULL,
                        passwordSalt TEXT NOT NULL,
                        passwordHash TEXT NOT NULL,
                        isActive INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE UNIQUE INDEX index_auth_accounts_username
                    ON auth_accounts(username)
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    INSERT INTO auth_accounts
                    (id, username, displayName, role, passwordSalt,
                     passwordHash, isActive, createdAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any>(
                        "admin-legacy",
                        "admin01",
                        "Admin Legacy",
                        "ADMIN",
                        "salt-admin-123",
                        "hash-admin-456",
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
                        "seller-legacy",
                        "vendeur01",
                        "Vendeur Legacy",
                        "VENDEUR",
                        "salt-seller-123",
                        "hash-seller-456",
                        0,
                        2000L
                    )
                )
            }

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
            ) {
                throw AssertionError("Unexpected automatic upgrade")
            }
        }

        val configuration = SupportSQLiteOpenHelper.Configuration
            .builder(context)
            .name(dbName)
            .callback(callback)
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)

        try {
            val db = helper.writableDatabase

            assertEquals(3, db.version)

            MIGRATION_3_4.migrate(db)

            val cursor = db.query(
                """
                SELECT id, username, displayName, role, passwordSalt,
                       passwordHash, isActive, createdAt, accountStatus,
                       activationTokenHash, activationExpiresAt,
                       activationFailedAttempts, activatedAt,
                       mustChangePassword
                FROM auth_accounts
                ORDER BY id
                """.trimIndent()
            )

            cursor.use {
                assertEquals(2, it.count)
                assertEquals(true, it.moveToFirst())

                // Legacy ADMIN account
                assertEquals("admin-legacy", it.getString(0))
                assertEquals("admin01", it.getString(1))
                assertEquals("Admin Legacy", it.getString(2))
                assertEquals("ADMIN", it.getString(3))
                assertEquals("salt-admin-123", it.getString(4))
                assertEquals("hash-admin-456", it.getString(5))
                assertEquals(1, it.getInt(6))
                assertEquals(1000L, it.getLong(7))
                assertEquals("ACTIVE", it.getString(8))
                assertNull(it.getString(9))
                assertEquals(true, it.isNull(10))
                assertEquals(0, it.getInt(11))
                assertEquals(true, it.isNull(12))
                assertEquals(0, it.getInt(13))

                // Legacy inactive seller account
                assertEquals(true, it.moveToNext())
                assertEquals("seller-legacy", it.getString(0))
                assertEquals("vendeur01", it.getString(1))
                assertEquals("Vendeur Legacy", it.getString(2))
                assertEquals("VENDEUR", it.getString(3))
                assertEquals("salt-seller-123", it.getString(4))
                assertEquals("hash-seller-456", it.getString(5))
                assertEquals(0, it.getInt(6))
                assertEquals(2000L, it.getLong(7))
                assertEquals("DISABLED", it.getString(8))
                assertNull(it.getString(9))
                assertEquals(true, it.isNull(10))
                assertEquals(0, it.getInt(11))
                assertEquals(true, it.isNull(12))
                assertEquals(0, it.getInt(13))
            }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }
}
