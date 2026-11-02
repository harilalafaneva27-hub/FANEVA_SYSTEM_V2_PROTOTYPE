package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
import com.example.data.repository.AuthRepository
import com.example.data.repository.AuthResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthRepositoryTest {
    private lateinit var database: FanevaDatabase
    private lateinit var authRepository: AuthRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FanevaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        authRepository = AuthRepository(database.authDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `initial admin code authenticates and wrong code is rejected`() = runBlocking {
        authRepository.ensureInitialAdmin()

        val success = authRepository.authenticateAdmin("290493".toCharArray())
        assertTrue(success is AuthResult.Success)
        assertEquals("ADMIN", (success as AuthResult.Success).session.role.code)

        val failure = authRepository.authenticateAdmin("000000".toCharArray())
        assertTrue(failure is AuthResult.Failure)
    }

    @Test
    fun `admin can create seller and seller authenticates with its identity`() = runBlocking {
        authRepository.ensureInitialAdmin()
        val created = authRepository.createSeller("vendeur01", "Soa K.", "secret1".toCharArray())
        assertTrue(created.isSuccess)

        val sellerLogin = authRepository.authenticateSeller("vendeur01", "secret1".toCharArray())
        assertTrue(sellerLogin is AuthResult.Success)
        val session = (sellerLogin as AuthResult.Success).session
        assertEquals("VENDEUR", session.role.code)
        assertEquals("Soa K.", session.name)
        assertEquals("vendeur01", session.email)

        val wrongPassword = authRepository.authenticateSeller("vendeur01", "wrong!!".toCharArray())
        assertTrue(wrongPassword is AuthResult.Failure)
    }

    @Test
    fun `seller creation rejects duplicate and reserved admin identifier`() = runBlocking {
        authRepository.ensureInitialAdmin()
        assertTrue(authRepository.createSeller("vendeur01", "Soa K.", "secret1".toCharArray()).isSuccess)
        assertFalse(authRepository.createSeller("vendeur01", "Another", "secret2".toCharArray()).isSuccess)
        assertFalse(authRepository.createSeller("__admin__", "Fake Admin", "secret2".toCharArray()).isSuccess)
    }

    @Test
    fun `stored account has a salt and no clear text password`() = runBlocking {
        authRepository.ensureInitialAdmin()
        authRepository.createSeller("vendeur02", "Mamy T.", "secret2".toCharArray())
        val accounts = database.authDao().findActiveByUsername("vendeur02")!!
        assertTrue(accounts.passwordSalt.isNotBlank())
        assertTrue(accounts.passwordHash.isNotBlank())
        assertFalse(accounts.passwordHash.contains("secret2"))
    }
}
