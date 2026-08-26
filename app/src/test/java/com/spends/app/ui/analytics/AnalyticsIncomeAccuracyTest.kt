package com.spends.app.ui.analytics

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.spends.app.core.money.AppCurrency
import com.spends.app.core.money.Money
import com.spends.app.core.period.PeriodRange
import com.spends.app.core.period.PeriodSelection
import com.spends.app.core.period.PeriodSelectionStore
import com.spends.app.core.period.PeriodType
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.repo.AllocationInput
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.repo.RecurringRepository
import com.spends.app.data.repo.TransactionInput
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Before
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Income analytics against a ledger shaped like a real one.
 *
 * The fixture is synthetic, but its **shape** is taken from a seven-year production export: lakh-scale
 * totals, one income category dwarfing the rest, amounts carrying paise, and — the case that actually
 * matters — categories that hold **both** income and expense transactions. In that export two categories
 * did: money came in under the same name it also went out under. A breakdown that split by the
 * category's `usage` flag instead of each transaction's `kind` would put the wrong figure in both donuts
 * and look entirely plausible doing it.
 *
 * These run the REAL DAO queries against a real in-memory database, so the SQL is what is under test,
 * not a re-implementation of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnalyticsIncomeAccuracyTest {

    /**
     * `viewModelScope` dispatches on `Dispatchers.Main`, which nothing pumps inside a plain `runBlocking`
     * — the ViewModel's `stateIn` flow then never emits and a collector waits forever. Swapping in an
     * unconfined test dispatcher makes the flow run inline, which is what lets these tests observe state
     * at all. (`AnalyticsScreenRenderTest` does not need this: `runComposeUiTest` installs its own.)
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Before fun installTestMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    @After fun restoreCurrency() {
        Money.displayCurrency = AppCurrency.DEFAULT
    }

    // A fixed mid-month instant, so every row lands inside one window regardless of when this runs.
    private val day = System.currentTimeMillis()

    private data class Row(val kind: TxnKind, val category: String, val minor: Long)

    /**
     * Mirrors the production shape: SALARY dominant, several near-zero income sources, and BUSINESS and
     * INTEREST appearing on both sides of the ledger.
     */
    private val ledger = listOf(
        // Income: one source at ~95% of the total, the rest small enough to round to 0%.
        Row(TxnKind.INCOME, "Salary", 3_600_000_00),
        Row(TxnKind.INCOME, "Provident Fund", 150_000_00),
        Row(TxnKind.INCOME, "Business", 25_000_00),
        Row(TxnKind.INCOME, "Cashback", 12_345_00),
        Row(TxnKind.INCOME, "Interest", 1_250_00),
        // Expenses: larger than income in total (a real ledger's net can be, and was, negative), with
        // paise on several rows and BOTH dual-kind names repeated from the income side.
        Row(TxnKind.EXPENSE, "Bills", 2_000_000_50),
        Row(TxnKind.EXPENSE, "Household", 1_200_000_00),
        Row(TxnKind.EXPENSE, "Health", 800_000_25),
        Row(TxnKind.EXPENSE, "Interest", 112_456_50),
        Row(TxnKind.EXPENSE, "Business", 44_680_59),
        Row(TxnKind.EXPENSE, "Junk Food", 7_00), // the smallest expense in the reference export was ₹7
    )

    private val expectedIncome = ledger.filter { it.kind == TxnKind.INCOME }.sumOf { it.minor }
    private val expectedExpense = ledger.filter { it.kind == TxnKind.EXPENSE }.sumOf { it.minor }

    private fun settingsRepository(): SettingsRepository {
        val f = File.createTempFile("income-accuracy", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create { f }
        return SettingsRepository(store)
    }

    private fun seed(): SpendsDatabase = seedRows(ledger)

    private fun seedRows(rows: List<Row>): SpendsDatabase {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, SpendsDatabase::class.java)
            .allowMainThreadQueries().build()
        runBlocking {
            val catDao = db.categoryDao()
            val ids = mutableMapOf<String, Long>()
            rows.forEach { r ->
                ids.getOrPut(r.category) {
                    catDao.insert(
                        CategoryEntity(
                            name = r.category, iconKey = "tag", colorHex = "#123456",
                            // Deliberately stamped from whichever kind is seen FIRST, so the dual-kind
                            // categories carry a usage that contradicts half their transactions. That is
                            // the real situation, and the breakdown must not consult this field.
                            usage = if (r.kind == TxnKind.INCOME) CategoryUsage.INCOME else CategoryUsage.EXPENSE,
                        ),
                    )
                }
            }
            ExpenseRepository(db).createAll(
                rows.map { r ->
                    TransactionInput(
                        amountMinor = r.minor, kind = r.kind, occurredAt = day,
                        merchantRaw = null, note = null,
                        allocations = listOf(AllocationInput(ids[r.category]!!, r.minor)),
                    )
                },
            )
        }
        return db
    }

    private fun stateFrom(db: SpendsDatabase): AnalyticsUiState = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val expenses = ExpenseRepository(db)
        val store = PeriodSelectionStore(context)
        store.set(PeriodSelection(type = PeriodType.MONTH, range = PeriodRange.ALL))
        AnalyticsViewModel(
            expenseRepository = expenses,
            recurringRepository = RecurringRepository(db, expenses),
            settingsRepository = settingsRepository(),
            periodSelectionStore = store,
            paymentMethodRepository = PaymentMethodRepository(db.paymentMethodDao(), db.expenseDao(), db),
        ).state.first { !it.loading }
    }

    /** Expenses only — the "no income this period" case, 16 of 72 months in the reference export. */
    private fun expenseOnlyDb(): SpendsDatabase = seedRows(ledger.filter { it.kind == TxnKind.EXPENSE })

    /** One salary payment and nothing else — what most real months' income actually looks like. */
    private fun singleIncomeDb(): SpendsDatabase = seedRows(
        listOf(Row(TxnKind.INCOME, "Salary", 139_250_00), Row(TxnKind.EXPENSE, "Bills", 5_000_00)),
    )

    private fun <T> withState(block: (AnalyticsUiState) -> T): T {
        val db = seed()
        return try {
            block(stateFrom(db))
        } finally {
            db.close()
        }
    }

    // ---- totals ----

    @Test fun the_headline_totals_are_the_sum_of_their_kind() = withState { s ->
        assertThat(s.incomeMinor).isEqualTo(expectedIncome)
        assertThat(s.expenseMinor).isEqualTo(expectedExpense)
        assertThat(s.netMinor).isEqualTo(expectedIncome - expectedExpense)
    }

    @Test fun a_ledger_that_spends_more_than_it_earns_reports_a_negative_net() = withState { s ->
        // The reference export's net was several lakh in the red; the sign has to survive at that scale
        // rather than being clamped or absolute-valued somewhere on the way to the tile.
        assertThat(s.netMinor).isEqualTo(-36_854_984L)
        assertThat(s.netMinor).isLessThan(0L)
    }

    // ---- the dual-kind case ----

    @Test fun a_category_on_both_sides_reports_each_side_separately() = withState { s ->
        assertThat(s.incomeCategories.single { it.name == "Business" }.amountMinor).isEqualTo(25_000_00)
        assertThat(s.categories.single { it.name == "Business" }.amountMinor).isEqualTo(44_680_59)

        // ...and the same for a category whose stored `usage` says INCOME while it also has expenses.
        assertThat(s.incomeCategories.single { it.name == "Interest" }.amountMinor).isEqualTo(1_250_00)
        assertThat(s.categories.single { it.name == "Interest" }.amountMinor).isEqualTo(112_456_50)
    }

    @Test fun the_breakdown_follows_transaction_kind_not_the_category_usage_flag() = withState { s ->
        // "Business" is stamped usage=INCOME (first row seen) yet carries the larger expense. If the query
        // ever started filtering on usage, one of these two would silently vanish from its donut.
        assertThat(s.incomeCategories.map { it.name }).contains("Business")
        assertThat(s.categories.map { it.name }).contains("Business")
    }

    // ---- reconciliation ----

    @Test fun each_donut_centre_equals_the_sum_of_its_own_wedges() = withState { s ->
        Lens.entries.forEach { lens ->
            assertThat(s.categorisedFor(lens)).isEqualTo(s.slicesFor(lens).sumOf { it.amountMinor })
        }
    }

    @Test fun fully_categorised_totals_match_the_headline_figures() = withState { s ->
        // Every fixture row has exactly one allocation, so nothing is uncategorised and the donut centre
        // must equal the tile above it. A drift here means allocations and amounts have come apart.
        assertThat(s.categorisedIncomeMinor).isEqualTo(s.incomeMinor)
        assertThat(s.categorisedSpendMinor).isEqualTo(s.expenseMinor)
    }

    @Test fun slices_are_ordered_largest_first_on_both_sides() = withState { s ->
        Lens.entries.forEach { lens ->
            val amounts = s.slicesFor(lens).map { it.amountMinor }
            assertThat(amounts).isInOrder(compareByDescending<Long> { it })
        }
    }

    // ---- a dominant category, which is what real income looks like ----

    @Test fun one_dominant_income_source_does_not_crowd_the_others_out_of_the_legend() = withState { s ->
        // Salary is ~95% of income. The small sources round to 0% but must still be listed with their
        // real amounts — dropping them would hide income the user actually received.
        val salary = s.incomeCategories.single { it.name == "Salary" }
        assertThat(salary.percent).isAtLeast(90)
        val interest = s.incomeCategories.single { it.name == "Interest" }
        assertThat(interest.percent).isEqualTo(0)
        assertThat(interest.amountMinor).isEqualTo(1_250_00)
        assertThat(s.incomeCategories).hasSize(5)
        assertThat(s.categories).hasSize(6)
    }

    // ---- currency is a rendering choice, never a change of value ----

    @Test fun switching_currency_changes_the_rendering_and_not_one_stored_figure() = withState { s ->
        val income = s.incomeMinor
        val expense = s.expenseMinor

        // Lakh-scale figures are where the two grouping conventions visibly disagree, which is exactly
        // why they are asserted here rather than on a three-digit number.
        Money.displayCurrency = AppCurrency.INR
        assertThat(Money.format(income)).isEqualTo("₹37,88,595.00")
        assertThat(Money.format(expense)).isEqualTo("₹41,57,144.84")
        assertThat(Money.format(s.netMinor)).isEqualTo("-₹3,68,549.84")

        Money.displayCurrency = AppCurrency.MYR
        assertThat(Money.format(income)).isEqualTo("RM3,788,595.00")
        assertThat(Money.format(expense)).isEqualTo("RM4,157,144.84")
        assertThat(Money.format(s.netMinor)).isEqualTo("-RM368,549.84")

        // The figures themselves are untouched by any of that.
        assertThat(s.incomeMinor).isEqualTo(income)
        assertThat(s.expenseMinor).isEqualTo(expense)
        assertThat(s.netMinor).isEqualTo(income - expense)
    }

    // ---- states the income view reaches that the spending view never did ----

    /**
     * A period with expenses but **no income at all**.
     *
     * In a seven-year reference export this was 16 months out of 72 — nearly a quarter. The spending
     * donut has effectively never been empty (there is always some spending), so "no wedges" is a state
     * this feature introduces rather than inherits. It must render as an empty ring with a zero centre,
     * not divide by zero and not report the whole period as having nothing to chart.
     */
    @Test fun a_period_with_no_income_at_all_is_still_a_chartable_period() {
        val db = expenseOnlyDb()
        try {
            val s = stateFrom(db)
            assertThat(s.isEmpty).isFalse() // there ARE expenses; the page must still render
            assertThat(s.incomeMinor).isEqualTo(0L)
            assertThat(s.incomeCategories).isEmpty()
            assertThat(s.categorisedIncomeMinor).isEqualTo(0L)
            // The donut centre and its (absent) wedges still reconcile at zero.
            assertThat(s.categorisedFor(Lens.INCOME)).isEqualTo(s.slicesFor(Lens.INCOME).sumOf { it.amountMinor })
            // Bars exist and are all zero, rather than being an empty list the chart cannot scale.
            assertThat(s.barsFor(Lens.INCOME)).isNotEmpty()
            assertThat(s.barsFor(Lens.INCOME).all { it == 0f }).isTrue()
            assertThat(s.netMinor).isEqualTo(-s.expenseMinor)
        } finally {
            db.close()
        }
    }

    /**
     * The common real case: a month whose income is one salary payment. The donut is then a single wedge
     * that must sweep the whole ring at exactly 100%, not 99% with a gap or 100% of nothing.
     */
    @Test fun a_single_income_source_is_one_full_hundred_percent_wedge() {
        val db = singleIncomeDb()
        try {
            val s = stateFrom(db)
            assertThat(s.incomeCategories).hasSize(1)
            val only = s.incomeCategories.single()
            assertThat(only.name).isEqualTo("Salary")
            assertThat(only.percent).isEqualTo(100)
            assertThat(only.amountMinor).isEqualTo(139_250_00)
            assertThat(s.categorisedIncomeMinor).isEqualTo(139_250_00)
            assertThat(s.incomeMinor).isEqualTo(139_250_00)
        } finally {
            db.close()
        }
    }

    @Test fun paise_survive_a_format_and_parse_round_trip_in_every_currency() = withState { s ->
        // 112_456_50 and 44_680_59 both carry paise; a rounding slip anywhere in formatting would show up
        // as a value that no longer parses back to what was stored.
        val amounts = s.categories.map { it.amountMinor } + s.incomeCategories.map { it.amountMinor }
        AppCurrency.entries.forEach { currency ->
            amounts.forEach { minor ->
                assertThat(Money.parseToMinor(Money.format(minor, currency))).isEqualTo(minor)
            }
        }
    }
}
