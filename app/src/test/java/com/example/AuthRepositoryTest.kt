package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
import com.example.data.repository.AdminSetupResult
import com.example.data.repository.AuthRepository
import com.example.data.repository.AuthResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
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
        authRepository = AuthRepository(database.authDao(), database.adminRecoveryDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun firstAdminMustBeExplicitlyCreatedAndRecoveryCodeIsSingleUse() = runBlocking {
        assertTrue(authRepository.isAdminSetupRequired())
        val setup = authRepository.initializeFirstAdmin(
            "owner01", "Owner Admin", "StrongPassphrase-2026!".toCharArray()
        )
        assertTrue(setup is AdminSetupResult.Created)
        val code = (setup as AdminSetupResult.Created).oneTimeRecoveryCode
        assertEquals(64, code.length)
        assertFalse(authRepository.isAdminSetupRequired())

        val duplicate = authRepository.initializeFirstAdmin(
            "owner02", "Second Admin", "AnotherStrongPass-2026!".toCharArray()
        )
        assertEquals(AdminSetupResult.AlreadyInitialized, duplicate)

        val login = authRepository.authenticateAdmin(
            "owner01", "StrongPassphrase-2026!".toCharArray()
        )
        assertTrue(login is AuthResult.Success)

        val recovery = authRepository.recoverAdminPassword(
            code.toCharArray(), "NewStrongPassphrase-2026!".toCharArray()
        )
        assertTrue(recovery.isSuccess)

        assertTrue(
            authRepository.authenticateAdmin(
                "owner01", "NewStrongPassphrase-2026!".toCharArray()
            ) is AuthResult.Success
        )
        assertTrue(
            authRepository.recoverAdminPassword(
                code.toCharArray(), "AnotherNewPassphrase-2026!".toCharArray()
            ).isFailure
        )
    }

    @Test
    fun invalidRecoveryCodeIsRejectedAndCountsFailedAttempts() = runBlocking {
        val setup = authRepository.initializeFirstAdmin(
            "owner01", "Owner Admin", "StrongPassphrase-2026!".toCharArray()
        ) as AdminSetupResult.Created

        repeat(5) {
            assertTrue(
                authRepository.recoverAdminPassword(
                    "incorrect-recovery-code".toCharArray(),
                    "NewStrongPassphrase-2026!".toCharArray()
                ).isFailure
            )
        }
        assertEquals(5, database.adminRecoveryDao().getCredential()!!.failedAttempts)
        assertTrue(
            authRepository.recoverAdminPassword(
                setup.oneTimeRecoveryCode.toCharArray(),
                "NewStrongPassphrase-2026!".toCharArray()
            ).isFailure
        )
    }

    @Test
    fun inactiveAccountStatusCannotAuthenticate() = runBlocking {
        authRepository.initializeFirstAdmin(
            "owner01", "Owner Admin", "StrongPassphrase-2026!".toCharArray()
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE auth_accounts SET accountStatus = 'DISABLED' WHERE username = 'owner01'"
        )
        assertTrue(
            authRepository.authenticateAdmin(
                "owner01", "StrongPassphrase-2026!".toCharArray()
            ) is AuthResult.Failure
        )
    }

    @Test
    fun sellerCreationRequiresStrongPasswordAndSellerAuthenticates() = runBlocking {
        authRepository.initializeFirstAdmin(
            "owner01", "Owner Admin", "StrongPassphrase-2026!".toCharArray()
        )
        assertTrue(
            authRepository.createSeller(
                "vendeur01", "Vendeur Un", "SellerStrongPass-2026!".toCharArray()
            ).isSuccess
        )
        assertTrue(
            authRepository.authenticateSeller(
                "vendeur01", "SellerStrongPass-2026!".toCharArray()
            ) is AuthResult.Success
        )
        assertTrue(
            authRepository.createSeller(
                "vendeur02", "Vendeur Deux", "short".toCharArray()
            ).isFailure
        )
    }
}
