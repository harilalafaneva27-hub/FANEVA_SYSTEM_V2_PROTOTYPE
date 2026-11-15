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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
        // runTest reuses this Main scheduler, so ViewModel coroutines and the test
        // share one deterministic virtual-time scheduler.
        Dispatchers.setMain(StandardTestDispatcher())
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
    fun realViewModelLogoutImmediatelyPurgesProtectedReadStateAndRevokesSession() = runTest {
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

        assertTrue(viewModel.allProducts.value.isEmpty())
        assertTrue(viewModel.allStocks.value.isEmpty())
        assertTrue(viewModel.allCustomers.value.isEmpty())
        assertTrue(viewModel.posCustomers.value.isEmpty())
        assertTrue(viewModel.allSales.value.isEmpty())
        assertTrue(viewModel.allMovements.value.isEmpty())
        assertTrue(viewModel.productsWithStock.value.isEmpty())
        assertTrue(viewModel.pendingOperations.value.isEmpty())
        assertEquals(0, viewModel.pendingCount.value)
        assertNull(viewModel.syncCursor.value)

        assertNull(database.securitySessionDao().getCurrentActiveSession())
    }
}
