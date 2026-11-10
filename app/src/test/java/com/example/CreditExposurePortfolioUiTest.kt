package com.example

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
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
import com.example.ui.theme.FanevaTheme
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

        viewModel = FanevaViewModel(
            fanevaRepository = FanevaRepository(database),
            syncRepository = FakeSyncRepository(),
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
            runCatching {
                val text = composeRule
                    .onNodeWithTag("portfolio_total_exposure")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .joinToString(" ") { it.text }

                text.contains(expectedTotal)
            }.getOrDefault(false)
        }

        composeRule.onNodeWithTag("portfolio_official_debt")
            .assertTextContains("$expectedOfficial Ar")

        composeRule.onNodeWithTag("portfolio_pending_sync")
            .assertTextContains("$expectedPending Ar")

        composeRule.onNodeWithTag("portfolio_reconciliation")
            .assertTextContains("$expectedReconciliation Ar")

        composeRule.onNodeWithTag("portfolio_non_referenced")
            .assertTextContains("$expectedOrphan Ar")

        composeRule.onNodeWithTag("portfolio_total_exposure")
            .assertTextContains("$expectedTotal Ar")

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
        override val pendingOperations: Flow<List<PendingSyncOperationEntity>> =
            flowOf(emptyList())

        override val pendingCount: Flow<Int> = flowOf(0)
        override val syncCursor: Flow<SyncCursorEntity?> = flowOf(null)
        override val isOnlineFlow: Flow<Boolean> = flowOf(true)

        override suspend fun triggerSync(): SyncPushResult =
            SyncPushResult.Success(0, 0)

        override suspend fun purgeSyncedOperations() = Unit
        override suspend fun setSimulatedNetwork(isOnline: Boolean) = Unit
        override suspend fun setStagingUrl(url: String) = Unit
    }
}
