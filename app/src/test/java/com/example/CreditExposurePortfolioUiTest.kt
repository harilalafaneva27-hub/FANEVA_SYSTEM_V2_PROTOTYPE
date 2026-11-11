package com.example

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.CustomerEntity
import com.example.data.local.entity.SaleEntity
import com.example.data.local.entity.PendingSyncOperationEntity
import com.example.data.local.entity.SyncCursorEntity
import com.example.data.repository.AuthRepository
import com.example.data.repository.FanevaRepository
import com.example.data.repository.SyncPushResult
import com.example.data.repository.SyncRepository
import com.example.ui.FanevaViewModel
import com.example.ui.screens.CustomersDebtScreen
import com.example.ui.screens.SyncOutboxScreen
import com.example.ui.theme.FanevaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CreditExposurePortfolioUiTest {

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private lateinit var database: FanevaDatabase
    private lateinit var viewModel: FanevaViewModel
    private lateinit var fakeSyncRepository: FakeSyncRepository
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        database = Room.inMemoryDatabaseBuilder(
            context,
            FanevaDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()

        val customerId = database.customerDao().insertCustomer(
            CustomerEntity(
                name = "Client connu UI test",
                phone = "000000000",
                address = "Test",
                creditLimit = 10000.0,
                currentDebt = 1000.0
            )
        )

        database.saleDao().insertSale(
            sale(
                id = "known-pending",
                storeId = "A1",
                customerId = customerId,
                total = 500.0,
                paid = 200.0,
                status = "PENDING_SYNC"
            )
        )

        database.saleDao().insertSale(
            sale(
                id = "orphan-pending",
                storeId = "A2",
                customerId = 999001L,
                total = 300.0,
                paid = 100.0,
                status = "PENDING_SYNC"
            )
        )

        database.saleDao().insertSale(
            sale(
                id = "known-reconciliation",
                storeId = "A3",
                customerId = customerId,
                total = 300.0,
                paid = 100.0,
                status = "RECONCILIATION_REQUIRED"
            )
        )

        database.saleDao().insertSale(
            sale(
                id = "orphan-reconciliation",
                storeId = "A5",
                customerId = 999002L,
                total = 200.0,
                paid = 50.0,
                status = "RECONCILIATION_REQUIRED"
            )
        )

        fakeSyncRepository = FakeSyncRepository()
        viewModel = FanevaViewModel(
            fanevaRepository = FanevaRepository(database),
            syncRepository = fakeSyncRepository,
            authRepository = AuthRepository(database.authDao())
        )
        viewModelStore.put("customers-debt-ui-test", viewModel)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        if (::database.isInitialized) {
            database.close()
        }
    }

    @Test
    fun portfolioDisplaysAllFiveLinesAndDoesNotDoubleCountOrphanExposure() {
        val expectedOfficial = String.format(Locale.getDefault(), "%,.0f", 1000.0)
        val expectedPending = String.format(Locale.getDefault(), "%,.0f", 500.0)
        val expectedReconciliation = String.format(Locale.getDefault(), "%,.0f", 350.0)
        val expectedOrphan = String.format(Locale.getDefault(), "%,.0f", 350.0)
        val expectedTotal = String.format(Locale.getDefault(), "%,.0f", 1850.0)

        composeRule.setContent {
            FanevaTheme {
                CustomersDebtScreen(viewModel = viewModel)
            }
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("portfolio_total_exposure")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("portfolio_total_exposure")
            .assertTextContains("Ar", substring = true)

        composeRule.onNodeWithTag("portfolio_official_debt")
            .assertTextContains("$expectedOfficial Ar", substring = true)

        composeRule.onNodeWithTag("portfolio_pending_sync")
            .assertTextContains("$expectedPending Ar", substring = true)

        composeRule.onNodeWithTag("portfolio_reconciliation")
            .assertTextContains("$expectedReconciliation Ar", substring = true)

        composeRule.onNodeWithTag("portfolio_non_referenced")
            .assertTextContains("$expectedOrphan Ar", substring = true)

        composeRule.onNodeWithTag("portfolio_total_exposure")
            .assertTextContains("$expectedTotal Ar", substring = true)

    }

    @Test
    fun manualSyncRethrowsCancellationAndAlwaysStopsSyncIndicator() {
        fakeSyncRepository.cancelNextSync()

        var callbackCalled = false
        viewModel.triggerManualSync {
            callbackCalled = true
        }

        assertFalse("Cancellation must not invoke the result callback", callbackCalled)
        assertFalse("Sync indicator must stop after cancellation", viewModel.isSyncing.value)
        assertTrue(
            "Cancellation must not be converted into an ordinary error message",
            viewModel.syncMessage.value.orEmpty().contains(
                "Synchronisation des mutations vers Cloudflare Staging..."
            )
        )
    }

    @Test
    fun localPersistenceErrorReplacesPreviousSuccessMessageAndStopsSyncIndicator() {
        val persistenceError = SyncPushResult.LocalPersistenceError(
            transactionError = "forced transaction failure",
            recoveryError = "forced recovery failure"
        )
        fakeSyncRepository.enqueue(
            SyncPushResult.Success(2, 11),
            persistenceError
        )

        composeRule.setContent {
            FanevaTheme {
                SyncOutboxScreen(
                    viewModel = viewModel,
                    onNavigateBack = {}
                )
            }
        }

        var callbackResult: SyncPushResult? = null

        viewModel.triggerManualSync { callbackResult = it }

        assertTrue(
            "Initial success message should be visible",
            viewModel.syncMessage.value.orEmpty().contains("2 opération(s) synchronisée(s)")
        )
        assertFalse("Sync indicator should stop after success", viewModel.isSyncing.value)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(
                "2 opération(s) synchronisée(s)",
                substring = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            "2 opération(s) synchronisée(s)",
            substring = true
        ).assertExists()

        viewModel.triggerManualSync { callbackResult = it }

        val errorMessage = viewModel.syncMessage.value.orEmpty()
        assertTrue(errorMessage.contains("Erreur de persistance locale"))
        assertTrue(errorMessage.contains("forced transaction failure"))
        assertTrue(errorMessage.contains("forced recovery failure"))

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(
                "Erreur de persistance locale",
                substring = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            "Erreur de persistance locale",
            substring = true
        ).assertExists()
        composeRule.onNodeWithText(
            "forced transaction failure",
            substring = true
        ).assertExists()
        composeRule.onNodeWithText(
            "forced recovery failure",
            substring = true
        ).assertExists()

        assertFalse(
            "Previous success message must not remain after persistence failure",
            errorMessage.contains("opération(s) synchronisée(s)")
        )
        assertFalse("Sync indicator should stop after persistence error", viewModel.isSyncing.value)
        assertEquals(persistenceError, callbackResult)
    }

    private fun sale(
        id: String,
        storeId: String,
        customerId: Long,
        total: Double,
        paid: Double,
        status: String
    ) = SaleEntity(
        id = id,
        saleNumber = "TEST-$id",
        storeId = storeId,
        customerId = customerId,
        customerName = "Test",
        sellerId = "seller-test",
        sellerName = "Seller Test",
        saleType = "CREDIT",
        totalAmount = total,
        paidAmount = paid,
        remainingAmount = total - paid,
        paymentMethod = "CASH",
        status = status,
        idempotencyKey = "key-$id"
    )

    private class FakeSyncRepository : SyncRepository {
        private val queuedResults = mutableListOf<SyncPushResult>()
        private var cancelNext = false

        fun cancelNextSync() {
            cancelNext = true
        }

        fun enqueue(vararg results: SyncPushResult) {
            queuedResults.addAll(results)
        }

        override val pendingOperations: Flow<List<PendingSyncOperationEntity>> =
            flowOf(emptyList())

        override val pendingCount: Flow<Int> = flowOf(0)
        override val syncCursor: Flow<SyncCursorEntity?> = flowOf(null)
        override val isOnlineFlow: Flow<Boolean> = flowOf(true)

        override suspend fun triggerSync(): SyncPushResult {
            if (cancelNext) {
                cancelNext = false
                throw CancellationException("forced sync cancellation")
            }
            return if (queuedResults.isEmpty()) {
                SyncPushResult.Success(0, 0)
            } else {
                queuedResults.removeAt(0)
            }
        }

        override suspend fun purgeSyncedOperations() = Unit
        override suspend fun setSimulatedNetwork(isOnline: Boolean) = Unit
        override suspend fun setStagingUrl(url: String) = Unit
    }
}
