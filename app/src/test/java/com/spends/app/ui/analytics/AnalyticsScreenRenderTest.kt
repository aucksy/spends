package com.spends.app.ui.analytics

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spends.app.core.period.PeriodSelectionStore
import com.spends.app.core.time.DateUtils
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
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Opens the Analytics screen for real on the JVM, on **both** sides of the new Spending/Income toggle.
 *
 * **Why this file exists.** v1.63.0 shipped green and still closed the app the instant a screen opened,
 * because nothing in CI had ever opened one. The income view is exactly that shape of risk: it is a
 * branch that does not compose at all until somebody taps the toggle, so a compiling build proves
 * nothing about it. The `rememberSaveable` holding the lens is the specific thing worth pinning — an
 * enum has to survive Compose's saveable registry, and a type it refuses throws at composition time.
 *
 * A real in-memory Room database is used rather than mocks, so the income-by-category SQL runs for
 * real: a `kind = 'INCOME'` filter that quietly matched nothing would leave the donut empty, and no
 * amount of mocking would reveal that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnalyticsScreenRenderTest {

    /**
     * Wait for [text] to appear.
     *
     * `waitForIdle()` alone is not enough here: it settles COMPOSITION, but the first real state arrives
     * from a Room `Flow` on the query executor, so the screen is legitimately still on its loading/empty
     * state when composition first goes idle. Without this the test would assert against a screen that
     * simply hadn't received its data yet — and would "fail" for a reason that has nothing to do with the
     * code under test.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Tap a lens segment on the Spending/Income toggle.
     *
     * Matched by click action, not by text alone: "Income" legitimately appears twice on this screen —
     * once as the summary card's total, once as this toggle — and only the toggle is clickable. Picking
     * by position instead would silently start tapping the wrong thing the day the layout is reordered.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.tapLens(label: String) {
        onAllNodesWithText(label).filterToOne(hasClickAction()).performClick()
    }

    private fun settingsRepository(): SettingsRepository {
        val file = File.createTempFile("analytics-render-test", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create { file }
        return SettingsRepository(store)
    }

    /** An in-memory database seeded with one income and one expense inside the current month. */
    private fun seededDb(): SpendsDatabase {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, SpendsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            val categoryDao = db.categoryDao()
            val salaryId = categoryDao.insert(
                CategoryEntity(name = "Salary", iconKey = "wallet", colorHex = "#22C55E", usage = CategoryUsage.INCOME),
            )
            val foodId = categoryDao.insert(
                CategoryEntity(name = "Food", iconKey = "food", colorHex = "#EF4444", usage = CategoryUsage.EXPENSE),
            )
            val repo = ExpenseRepository(db)
            val today = DateUtils.nowMillis()
            repo.create(
                TransactionInput(
                    amountMinor = 900_00, kind = TxnKind.INCOME, occurredAt = today,
                    merchantRaw = "Employer", note = null,
                    allocations = listOf(AllocationInput(salaryId, 900_00)),
                ),
            )
            repo.create(
                TransactionInput(
                    amountMinor = 250_00, kind = TxnKind.EXPENSE, occurredAt = today,
                    merchantRaw = "Cafe", note = null,
                    allocations = listOf(AllocationInput(foodId, 250_00)),
                ),
            )
        }
        return db
    }

    private fun viewModelWith(db: SpendsDatabase): AnalyticsViewModel {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val expenses = ExpenseRepository(db)
        return AnalyticsViewModel(
            expenseRepository = expenses,
            recurringRepository = RecurringRepository(db, expenses),
            settingsRepository = settingsRepository(),
            periodSelectionStore = PeriodSelectionStore(context),
            paymentMethodRepository = PaymentMethodRepository(db.paymentMethodDao(), db.expenseDao(), db),
        )
    }

    /** Isolates a crash in the ViewModel's construction/flow wiring from a crash in composition. */
    @Test
    fun the_view_model_can_be_constructed() {
        val db = seededDb()
        try {
            viewModelWith(db)
        } finally {
            db.close()
        }
    }

    /**
     * The default view every existing user lands on — spending, exactly as before this change.
     *
     * The section headings are asserted in CAPITALS because `SectionLabel` uppercases its text before
     * handing it to `Text`, so that is what reaches the semantics tree.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun the_screen_opens_on_the_spending_view() = runComposeUiTest {
        val db = seededDb()
        try {
            setContent {
                AnalyticsScreen(
                    onOpenRecurring = {}, onOpenCategory = { _, _, _, _, _, _ -> }, onOpenSettings = {},
                    viewModel = viewModelWith(db),
                )
            }
            awaitText("SPENDING BY CATEGORY")
            onNodeWithText("SPEND OVER TIME").assertExists()
        } finally {
            db.close()
        }
    }

    /**
     * The whole point of the change: tap Income and the page must actually switch — headers, donut and
     * bars together. This is the branch that does not exist until the toggle is used, and it also
     * exercises the `rememberSaveable` enum that holds the lens.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tapping_income_switches_both_charts() = runComposeUiTest {
        val db = seededDb()
        try {
            setContent {
                AnalyticsScreen(
                    onOpenRecurring = {}, onOpenCategory = { _, _, _, _, _, _ -> }, onOpenSettings = {},
                    viewModel = viewModelWith(db),
                )
            }
            awaitText("SPENDING BY CATEGORY")
            tapLens("Income")
            awaitText("INCOME BY CATEGORY")
            onNodeWithText("INCOME OVER TIME").assertExists()
            // ...and the spending headings are gone, rather than both being on screen at once.
            onNodeWithText("SPENDING BY CATEGORY").assertDoesNotExist()
            onNodeWithText("SPEND OVER TIME").assertDoesNotExist()
        } finally {
            db.close()
        }
    }

    /**
     * The income donut has to be populated by the income-by-category query, not merely present. The
     * seeded salary is the only income in the window, so its legend row is what proves the query ran and
     * matched — an empty list would render the "No categorised income" copy instead.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun the_income_view_lists_the_income_category() = runComposeUiTest {
        val db = seededDb()
        try {
            setContent {
                AnalyticsScreen(
                    onOpenRecurring = {}, onOpenCategory = { _, _, _, _, _, _ -> }, onOpenSettings = {},
                    viewModel = viewModelWith(db),
                )
            }
            awaitText("SPENDING BY CATEGORY")
            tapLens("Income")
            awaitText("Salary")
            onNodeWithText("EARNED").assertExists()
            onNodeWithText("No categorised income this period.").assertDoesNotExist()
        } finally {
            db.close()
        }
    }
}
