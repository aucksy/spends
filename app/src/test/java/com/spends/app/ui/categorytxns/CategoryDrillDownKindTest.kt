package com.spends.app.ui.categorytxns

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.spends.app.core.period.PeriodRange
import com.spends.app.core.period.PeriodSelection
import com.spends.app.core.period.PeriodSelectionStore
import com.spends.app.core.period.PeriodType
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.repo.AllocationInput
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.repo.TransactionInput
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A category that holds BOTH income and expense must total only the side that was tapped.
 *
 * Two of the owner's real categories do exactly this — money comes in under the same name it also goes
 * out under. The Analytics donuts always split them correctly, because `observeCategorySpend` and
 * `observeCategoryIncome` each filter on kind. The drill-down did not: it summed every row in the
 * category regardless of direction, so tapping a ₹25,000 income wedge opened a screen headed with that
 * income plus the same category's spending. The wedge and the page it opened disagreed about what the
 * category was worth, with nothing on either to explain why.
 *
 * The fixture is shaped like the real case rather than minimally: one category, both directions, inside
 * one period, with the two totals deliberately different so a filter that silently did nothing could not
 * pass by coincidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CategoryDrillDownKindTest {

    private var db: SpendsDatabase? = null

    /**
     * `viewModelScope` posts to `Dispatchers.Main`, which under a plain unit test has no implementation —
     * the ViewModel's `stateIn` flow then never emits and a collector waits forever. An unconfined test
     * dispatcher makes it run inline, which is what lets these tests observe state at all. Same reason,
     * and same shape, as `AnalyticsIncomeAccuracyTest`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Before fun installTestMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    @After fun tearDown() {
        db?.let { if (it.isOpen) it.close() }
    }

    private fun settingsRepository(): SettingsRepository {
        val file = File.createTempFile("drilldown-kind-test", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create { file }
        return SettingsRepository(store)
    }

    /**
     * One category, "Business", holding both directions inside the current period.
     *
     * Income 25,000 + 5,000 = 30,000. Expense 4,000 + 1,000 = 5,000. Every figure is distinct, and the
     * unfiltered sum (35,000) matches neither side — so a broken filter cannot look correct.
     */
    private fun seededDb(): Pair<SpendsDatabase, Long> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(context, SpendsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db = database
        var businessId = -1L
        runBlocking {
            businessId = database.categoryDao().insert(
                CategoryEntity(
                    name = "Business",
                    iconKey = "work",
                    colorHex = "#3B82F6",
                    usage = CategoryUsage.BOTH,
                ),
            )
            val repo = ExpenseRepository(database)
            val today = DateUtils.nowMillis()
            fun input(minor: Long, kind: TxnKind, who: String) = TransactionInput(
                amountMinor = minor,
                kind = kind,
                occurredAt = today,
                merchantRaw = who,
                note = null,
                allocations = listOf(AllocationInput(businessId, minor)),
            )
            repo.create(input(25_000_00, TxnKind.INCOME, "Client A"))
            repo.create(input(5_000_00, TxnKind.INCOME, "Client B"))
            repo.create(input(4_000_00, TxnKind.EXPENSE, "Hosting"))
            repo.create(input(1_000_00, TxnKind.EXPENSE, "Stationery"))
        }
        return database to businessId
    }

    private fun viewModelFor(database: SpendsDatabase, categoryId: Long, kind: String?): CategoryTransactionsViewModel {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val handle = SavedStateHandle(
            buildMap {
                put(Routes.ARG_CATEGORY_ID, categoryId)
                put(Routes.ARG_CATEGORY_NAME, "Business")
                if (kind != null) put(Routes.ARG_KIND, kind)
            },
        )
        return CategoryTransactionsViewModel(
            savedStateHandle = handle,
            expenseRepository = ExpenseRepository(database),
            settingsRepository = settingsRepository(),
            periodSelectionStore = PeriodSelectionStore(context),
            paymentMethodRepository = PaymentMethodRepository(
                database.paymentMethodDao(),
                database.expenseDao(),
                database,
            ),
        )
    }

    /**
     * Read the settled state over an ALL-time window.
     *
     * The period is pinned deliberately. These tests are about KIND, and nothing else — leaving the
     * default cycle in place would make them depend on where today happens to fall relative to the salary
     * day, so a correct filter could still go red in the last week of a month. Period slicing has its own
     * coverage; this file must fail for exactly one reason.
     */
    private suspend fun loadedState(vm: CategoryTransactionsViewModel): CategoryTxnsUiState {
        vm.setPeriod(PeriodSelection(type = PeriodType.MONTH, range = PeriodRange.ALL))
        return vm.state.first { !it.loading }
    }

    @Test fun opening_from_the_income_wedge_totals_only_income() = runTest {
        val (database, businessId) = seededDb()
        val state = loadedState(viewModelFor(database, businessId, TxnKind.INCOME.name))

        // 25,000 + 5,000. NOT 35,000, which is what the screen used to show.
        assertThat(state.totalMinor).isEqualTo(30_000_00)
        assertThat(state.count).isEqualTo(2)
        assertThat(state.rows.map { it.kind }.toSet()).containsExactly(TxnKind.INCOME)
    }

    @Test fun opening_from_the_spending_wedge_totals_only_spending() = runTest {
        val (database, businessId) = seededDb()
        val state = loadedState(viewModelFor(database, businessId, TxnKind.EXPENSE.name))

        assertThat(state.totalMinor).isEqualTo(5_000_00)
        assertThat(state.count).isEqualTo(2)
        assertThat(state.rows.map { it.kind }.toSet()).containsExactly(TxnKind.EXPENSE)
    }

    @Test fun the_two_sides_never_add_up_to_the_old_mixed_total() = runTest {
        val (database, businessId) = seededDb()
        val income = loadedState(viewModelFor(database, businessId, TxnKind.INCOME.name)).totalMinor
        database.close()

        val (database2, businessId2) = seededDb()
        val expense = loadedState(viewModelFor(database2, businessId2, TxnKind.EXPENSE.name)).totalMinor

        // The bug's signature: one screen showing the sum of both. Each side must be strictly less.
        assertThat(income + expense).isEqualTo(35_000_00)
        assertThat(income).isLessThan(35_000_00)
        assertThat(expense).isLessThan(35_000_00)
    }

    @Test fun a_link_with_no_kind_still_works_and_means_spending() = runTest {
        // Back-stack entries and any deep link minted before this argument existed carry no kind. They
        // must resolve, and resolve to what they used to mean, rather than crashing or showing income.
        val (database, businessId) = seededDb()
        val state = loadedState(viewModelFor(database, businessId, null))

        assertThat(state.totalMinor).isEqualTo(5_000_00)
        assertThat(state.lensKind).isEqualTo(TxnKind.EXPENSE)
    }

    @Test fun an_unreadable_kind_falls_back_to_spending_rather_than_crashing() = runTest {
        val (database, businessId) = seededDb()
        val state = loadedState(viewModelFor(database, businessId, "NONSENSE"))

        assertThat(state.totalMinor).isEqualTo(5_000_00)
        assertThat(state.lensKind).isEqualTo(TxnKind.EXPENSE)
    }

    @Test fun the_screen_carries_the_side_it_is_showing_so_it_can_say_so() = runTest {
        // Two same-named screens with different totals are a worse confusion than the original bug unless
        // each says which side it is. The app bar renders this.
        val (database, businessId) = seededDb()
        assertThat(loadedState(viewModelFor(database, businessId, TxnKind.INCOME.name)).lensKind)
            .isEqualTo(TxnKind.INCOME)
    }

    @Test fun the_monthly_average_follows_the_lens_too() = runTest {
        // The average is computed from the same list, so it must have narrowed with it. If it had not,
        // the headline and the bar beneath it would be measuring different things — which is the exact
        // confusion the v1.67.0 redesign of this screen existed to remove.
        val (database, businessId) = seededDb()
        val income = loadedState(viewModelFor(database, businessId, TxnKind.INCOME.name))
        database.close()

        val (database2, businessId2) = seededDb()
        val expense = loadedState(viewModelFor(database2, businessId2, TxnKind.EXPENSE.name))

        assertThat(income.monthlyAverageMinor).isGreaterThan(expense.monthlyAverageMinor)
        assertThat(income.monthlyAverageMinor).isAtMost(30_000_00)
        assertThat(expense.monthlyAverageMinor).isAtMost(5_000_00)
    }
}
