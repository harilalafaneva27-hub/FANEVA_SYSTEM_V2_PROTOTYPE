package com.example

import android.content.Context
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.ProductEntity
import com.example.data.repository.AdminSetupResult
import com.example.data.repository.AuthRepository
import com.example.data.repository.FanevaRepository
import com.example.data.repository.SyncBatchRequest
import com.example.data.repository.SyncBatchResponse
import com.example.data.repository.SyncHttpClient
import com.example.data.repository.SyncRepositoryImpl
import com.example.ui.FanevaViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FanevaViewModelLogoutTest {
    private lateinit var database: FanevaDatabase
    private lateinit var authRepository: AuthRepository
    private lateinit var viewModel: FanevaViewModel
    private lateinit var viewModelStore: ViewModelStore

    @Before
    fun setup() {
        // Keep ViewModelScope deterministic without a runTest virtual-time scheduler.
        // Authentication performs PBKDF2 on Dispatchers.Default and resumes Main
        // after the real worker completes.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FanevaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        authRepository = AuthRepository(
            database.authDao(),
            database.adminRecoveryDao(),
            database.securitySessionDao()
        )
        val repository = FanevaRepository(database)
        val syncRepository = SyncRepositoryImpl(
            database = database,
            authRepository = authRepository,
            pendingSyncDao = database.pendingSyncDao(),
            syncCursorDao = database.syncCursorDao(),
            httpClient = object : SyncHttpClient {
                override suspend fun push(
                    url: String,
                    request: SyncBatchRequest
                ): SyncBatchResponse = error("Network must not be used by this test")
            }
        )
        viewModel = FanevaViewModel(repository, syncRepository, authRepository)
        viewModelStore = ViewModelStore().also { it.put("faneva-viewmodel-logout-test", viewModel) }
    }

    @After
    fun tearDown() {
        // Cancel ViewModelScope collectors (including the periodic session clock)
        // before closing the in-memory Room database.
        viewModelStore.clear()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun realViewModelLogoutPurgesProtectedReadStateAndRevokesSession() = runBlocking {
        try {
            val setup = authRepository.initializeFirstAdmin(
                "viewmodelowner",
                "ViewModel Owner",
                "StrongPassphrase-2026!".toCharArray()
            )
            assertTrue(setup is AdminSetupResult.Created)

            val productId = database.productDao().insertProduct(
                ProductEntity(
                    code = "VM-LOGOUT-01",
                    name = "Protected ViewModel product",
                    category = "Audit",
                    purchasePrice = 100.0,
                    sellingPrice = 150.0
                )
            )

            val loginResult = CompletableDeferred<String?>()
            viewModel.authenticateAdmin(
                "viewmodelowner",
                "StrongPassphrase-2026!"
            ) { loginResult.complete(it) }
            assertNull(withTimeout(10_000L) { loginResult.await() })

            val visibleProducts = withTimeout(10_000L) {
                viewModel.allProducts.first { products -> products.any { it.id == productId } }
            }
            assertEquals("Protected ViewModel product", visibleProducts.single().name)

            viewModel.logout()

            // authState is changed synchronously by logout(); the derived StateFlows
            // below are updated by combine/stateIn collectors and must be observed
            // after their propagation rather than read in the same call stack.
            assertTrue(viewModel.authState.value is com.example.ui.AuthUiState.SignedOut)
            withTimeout(5_000L) { viewModel.allProducts.first { it.isEmpty() } }
            withTimeout(5_000L) { viewModel.allStocks.first { it.isEmpty() } }
            withTimeout(5_000L) { viewModel.allCustomers.first { it.isEmpty() } }
            withTimeout(5_000L) { viewModel.posCustomers.first { it.isEmpty() } }
            withTimeout(5_000L) { viewModel.allSales.first { it.isEmpty() } }
            withTimeout(5_000L) { viewModel.allMovements.first { it.isEmpty() } }
            withTimeout(5_000L) { viewModel.productsWithStock.first { it.isEmpty() } }
            withTimeout(5_000L) { viewModel.pendingOperations.first { it.isEmpty() } }
            withTimeout(5_000L) { viewModel.pendingCount.first { it == 0 } }
            withTimeout(5_000L) { viewModel.syncCursor.first { it == null } }

            assertNull(database.securitySessionDao().getCurrentActiveSession())
        } finally {
            // Stop the periodic ViewModel clock before runBlocking completes.
            viewModelStore.clear()
        }
    }
}
