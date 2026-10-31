package com.spends.app.ui.store

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spends.app.core.category.ColorAssigner
import com.spends.app.core.category.IconAssigner
import com.spends.app.core.period.PeriodRange
import com.spends.app.core.period.PeriodSelection
import com.spends.app.core.period.PeriodSelectionStore
import com.spends.app.core.period.PeriodType
import com.spends.app.core.theme.SpendsTheme
import com.spends.app.core.time.DateUtils
import com.spends.app.data.ai.AiClient
import com.spends.app.data.ai.CurrencyAi
import com.spends.app.data.backup.SecureKeyStore
import com.spends.app.data.capture.CaptureDraftStore
import com.spends.app.data.capture.MerchantKeys
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.db.entity.AllocationEntity
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.ExpenseEntity
import com.spends.app.data.db.entity.MerchantCategoryEntity
import com.spends.app.data.db.entity.PaymentMethodEntity
import com.spends.app.data.db.entity.PendingCaptureEntity
import com.spends.app.data.db.entity.RecurringRuleEntity
import com.spends.app.data.demo.DemoInstrument
import com.spends.app.data.demo.DemoScript
import com.spends.app.data.importer.DedupeKey
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.repo.RecurringRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.DefaultLanding
import com.spends.app.domain.model.Direction
import com.spends.app.domain.model.PaymentMethodType
import com.spends.app.domain.model.ThemeMode
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.addedit.AddEditScreen
import com.spends.app.ui.addedit.AddEditViewModel
import com.spends.app.ui.analytics.AnalyticsScreen
import com.spends.app.ui.analytics.AnalyticsViewModel
import com.spends.app.ui.capture.CaptureSection
import com.spends.app.ui.capture.CaptureViewModel
import com.spends.app.ui.cards.CardsScreen
import com.spends.app.ui.cards.CardsViewModel
import com.spends.app.ui.categorytxns.CategoryTransactionsScreen
import com.spends.app.ui.categorytxns.CategoryTransactionsViewModel
import com.spends.app.ui.components.LocalAmountsHidden
import com.spends.app.ui.navigation.Routes
import com.spends.app.ui.recurring.RecurringScreen
import com.spends.app.ui.recurring.RecurringViewModel
import com.spends.app.ui.transactions.TransactionsScreen
import com.spends.app.ui.transactions.TransactionsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Google Play phone screenshots, rendered from the REAL screens on the JVM and written as PNGs to
 * `app/build/store-screenshots/` (CI uploads that folder as the `store-screenshots` artifact).
 *
 * **Data.** The demo account from [DemoScript] — the same script demo mode uses — written into an in-memory
 * database by [seedDemoAccount], which mirrors `DemoDataSeeder.seed()` minus its guard. The guard refuses any
 * database not literally named `spends-demo.db`, which an in-memory one never is; that guard exists to protect
 * real money and must not be loosened for a test, so the insert loop is repeated here instead.
 *
 * **Size.** 411 x 891 dp at xxhdpi = 1233 x 2673 px, the 20:9 shape of a modern phone. These are not uploaded
 * as they are: `scripts/store/frame_screenshots.py` puts each one in a phone frame on a 9:16 page, and only
 * that page must keep Play's limit (long side at most twice the short side).
 *
 * **Not on screen:** the demo "sample data" strip (no DemoModeWrapper) and the status bar.
 *
 * Each screen has its own test so one broken screen does not lose the others.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreScreenshotsTest {

    // ---------------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------------

    /** Everything one test needs. One [PeriodSelectionStore] per test method — it may only be built once. */
    private class Fixture(
        val db: SpendsDatabase,
        val settings: SettingsRepository,
        val periodStore: PeriodSelectionStore,
        val pendingIds: List<Long>,
        val categoryIds: Map<String, Long>,
    ) {
        val context: Application = ApplicationProvider.getApplicationContext()
        val expenses = ExpenseRepository(db)
        val paymentMethods = PaymentMethodRepository(db.paymentMethodDao(), db.expenseDao(), db)
        val categories = CategoryRepository(db.categoryDao())
        val recurring = RecurringRepository(db, expenses)
        private val aiClient = AiClient(SecureKeyStore(context))
        val capture = SmsCaptureRepository(context, db, expenses, paymentMethods, CurrencyAi(aiClient, settings))
    }

    private fun settingsRepository(): SettingsRepository {
        val file = File.createTempFile("store-shots", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create { file }
        return SettingsRepository(store)
    }

    private fun fixture(smsCaptureOn: Boolean = false): Fixture {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, SpendsDatabase::class.java)
            .addCallback(SpendsDatabase.SEED_CALLBACK)
            .allowMainThreadQueries()
            .build()
        val settings = settingsRepository()
        val today = LocalDate.now(DateUtils.ZONE)
        val seeded = runBlocking {
            applyDemoSettings(settings, today)
            if (smsCaptureOn) settings.setSmsCaptureEnabled(true)
            seedDemoAccount(db, today)
        }
        val store = PeriodSelectionStore(context)
        pickAFullCycle(store, today)
        return Fixture(db, settings, store, seeded.first, seeded.second)
    }

    /** Same preferences `DemoDataSeeder.applyDemoSettings` writes. */
    private suspend fun applyDemoSettings(settings: SettingsRepository, today: LocalDate) {
        settings.setOnboardingComplete(true)
        settings.setSalaryCycleStartDay(DemoScript.SALARY_DAY)
        settings.setDefaultLanding(DefaultLanding.TRANSACTIONS)
        settings.setTrashRetentionDays(30)
        settings.setSmartCycleEnabled(true)
        settings.setSmartCycleResetDay(0)
        val anchor = YearMonth.from(today).minusMonths((DemoScript.RICH_MONTHS - 1).toLong()).atDay(1)
        settings.setCarryForwardEnabled(true)
        settings.setCarryForwardAnchor(anchor.toEpochDay())
        settings.setCarryForwardOpening(1_25_000_00L)
        settings.setAutoBackupEnabled(false)
        settings.setSmsCaptureEnabled(false)
        settings.setNotificationCaptureEnabled(false)
    }

    /**
     * The body of `DemoDataSeeder.seed()` against a fresh in-memory database (so nothing to wipe first).
     * Returns the review-queue row ids in [DemoScript] order, and category name -> id.
     */
    private suspend fun seedDemoAccount(db: SpendsDatabase, today: LocalDate): Pair<List<Long>, Map<String, Long>> {
        val categoryDao = db.categoryDao()
        val expenseDao = db.expenseDao()
        val paymentMethodDao = db.paymentMethodDao()
        val recurringDao = db.recurringDao()
        val pendingCaptureDao = db.pendingCaptureDao()
        val merchantCategoryDao = db.merchantCategoryDao()

        val data = DemoScript.build(today)
        val now = DateUtils.nowMillis()

        // Categories: the Room seed callback's set, plus the demo's custom ones.
        val existing = categoryDao.getAllOnce().associateBy { it.name }.toMutableMap()
        val taken = existing.values.map { it.colorHex }.toMutableSet()
        var order = (existing.values.maxOfOrNull { it.sortOrder } ?: 0) + 1
        suspend fun addCategory(name: String, archived: Boolean) {
            if (existing.containsKey(name)) return
            val entity = CategoryEntity(
                name = name,
                iconKey = IconAssigner.keyFor(name),
                colorHex = ColorAssigner.colorFor(name, taken).also { taken.add(it) },
                isCustom = true,
                isArchived = archived,
                sortOrder = order++,
                usage = CategoryUsage.EXPENSE,
            )
            val id = categoryDao.insert(entity)
            existing[name] = entity.copy(id = id)
        }
        DemoScript.CUSTOM_EXPENSE_CATEGORIES.forEach { addCategory(it, archived = false) }
        addCategory(DemoScript.ARCHIVED_CATEGORY, archived = true)
        val categoryIds: Map<String, Long> = existing.mapValues { it.value.id }
        val fallbackId = categoryIds["Other"] ?: categoryIds.values.first()
        fun categoryId(name: String): Long = categoryIds[name] ?: fallbackId

        // Instruments: two credit cards, a bank and a UPI handle.
        suspend fun addInstrument(type: PaymentMethodType, label: String, institution: String, last4: String?, color: String, billingDay: Int?, dueDay: Int?): Long =
            paymentMethodDao.insert(
                PaymentMethodEntity(
                    type = type,
                    label = label,
                    institution = institution,
                    last4 = last4,
                    colorHex = color,
                    billingDay = billingDay,
                    dueDay = dueDay,
                    reviewed = true,
                    firstSeenAt = now,
                    lastActivityAt = now,
                ),
            )
        val instrumentIds: Map<DemoInstrument, Long> = mapOf(
            DemoInstrument.CARD_HDFC to addInstrument(PaymentMethodType.CREDIT_CARD, "HDFC Millennia", "HDFC Bank", "4821", "#0F766E", 5, 25),
            DemoInstrument.CARD_AXIS to addInstrument(PaymentMethodType.CREDIT_CARD, "Axis Magnus", "Axis Bank", "4409", "#B45309", 18, 8),
            DemoInstrument.BANK_ICICI to addInstrument(PaymentMethodType.BANK_ACCOUNT, "ICICI Savings", "ICICI Bank", "8842", "#1D4ED8", null, null),
            DemoInstrument.UPI_GPAY to addInstrument(PaymentMethodType.UPI, "GPay UPI", "ICICI Bank", null, "#7C3AED", null, null),
        )

        // Recurring rules, with nextRunAt pointed past today exactly as the seeder does.
        val ruleIds = mutableMapOf<String, Long>()
        for (rule in data.recurring) {
            var occurrence = DemoScript.firstOccurrence(rule, today)
            val startDate = occurrence
            var lastRun: LocalDate? = null
            while (!occurrence.isAfter(today)) {
                lastRun = occurrence
                occurrence = DemoScript.stepRecurring(occurrence, rule)
            }
            ruleIds[rule.key] = recurringDao.insert(
                RecurringRuleEntity(
                    amountMinor = rule.amountMinor,
                    kind = rule.kind,
                    categoryId = categoryIds[rule.categoryName] ?: categoryIds.values.first(),
                    merchant = rule.merchant,
                    note = rule.note,
                    frequency = rule.freq,
                    intervalCount = 1,
                    anchorDay = rule.anchorDay,
                    startDate = DateUtils.startOfDayMillis(startDate),
                    nextRunAt = DateUtils.startOfDayMillis(occurrence),
                    lastRunAt = lastRun?.let { DateUtils.startOfDayMillis(it) },
                    active = true,
                    createdAt = now,
                    updatedAt = now,
                    occurrenceLimit = 0,
                    paymentMethodId = rule.instrument?.let { instrumentIds[it] },
                ),
            )
        }

        for (txn in data.txns) {
            val occurredAt = DateUtils.epochMillisFor(txn.date, txn.hour, txn.minute)
            val ruleId = txn.recurringKey?.let { ruleIds[it] }
            val expenseId = expenseDao.insertExpense(
                ExpenseEntity(
                    amountMinor = txn.amountMinor,
                    occurredAt = occurredAt,
                    merchantRaw = txn.merchant,
                    note = txn.note,
                    paymentMethodId = txn.instrument?.let { instrumentIds[it] },
                    source = txn.source,
                    kind = txn.kind,
                    direction = if (txn.kind == TxnKind.INCOME) Direction.CREDIT else Direction.DEBIT,
                    parseConfidence = 100,
                    dedupeHash = ruleId?.let { DedupeKey.forRecurring(it, occurredAt, txn.amountMinor, txn.kind) },
                    createdAt = occurredAt,
                    updatedAt = now,
                    deletedAt = txn.deletedDaysAgo?.let { DateUtils.epochMillisFor(today.minusDays(it.toLong()), 12, 0) },
                    recurringRuleId = ruleId,
                ),
            )
            expenseDao.insertAllocations(
                txn.allocations.map { AllocationEntity(expenseId = expenseId, categoryId = categoryId(it.categoryName), amountMinor = it.amountMinor) },
            )
        }

        val pendingIds = mutableListOf<Long>()
        for (p in data.pending) {
            val occurredAt = DateUtils.epochMillisFor(today.minusDays(p.daysAgo.toLong()), 11, 20)
            pendingIds += pendingCaptureDao.insert(
                PendingCaptureEntity(
                    amountMinor = p.amountMinor,
                    kind = p.kind,
                    occurredAt = occurredAt,
                    merchant = p.merchant,
                    last4 = p.last4,
                    institution = p.institution,
                    categoryId = categoryId(p.categoryName),
                    parseConfidence = p.parseConfidence,
                    dedupeHash = "demo|${p.merchant}|${p.amountMinor}|${p.daysAgo}",
                    receivedAt = occurredAt,
                    createdAt = now,
                    rawBody = p.rawBody,
                    sender = p.sender,
                    sourceApp = p.sourceApp,
                ),
            )
        }

        merchantCategoryDao.insertAll(
            data.learned.mapNotNull { entry ->
                MerchantKeys.normalize(entry.merchant)?.let { key ->
                    MerchantCategoryEntity(merchantKey = key, categoryId = categoryId(entry.categoryName), updatedAt = now, note = entry.note)
                }
            },
        )
        return pendingIds to categoryIds
    }

    /**
     * Show a cycle with a full month of data in it. The demo is paid on the 25th, so on e.g. the 31st the
     * current cycle is six days old and looks empty; in that case show the previous (complete) cycle.
     *
     * `set` is retried until both the in-memory value and the persisted file agree, because the store's own
     * init reads the persisted value asynchronously and could otherwise land after our write.
     */
    private fun pickAFullCycle(store: PeriodSelectionStore, today: LocalDate) {
        val day = DemoScript.SALARY_DAY
        val cycleStart = if (today.dayOfMonth >= day) today.withDayOfMonth(day) else today.minusMonths(1).withDayOfMonth(day)
        val offset = if (ChronoUnit.DAYS.between(cycleStart, today) < 14) -1 else 0
        val wanted = PeriodSelection(type = PeriodType.SALARY_CYCLE, range = PeriodRange.CURRENT, cycleOffset = offset)
        repeat(100) {
            if (store.selection.value != wanted) store.set(wanted)
            if (store.selection.value == wanted && runBlocking { store.current() } == wanted) return
            Thread.sleep(50)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Waiting and capturing
    // ---------------------------------------------------------------------------------------------

    /**
     * Wait until any of [texts] is on screen — real Room data, not the loading state. A miss does not fail
     * the run: the shot is still taken, and the screen's text is written beside it so the miss can be read.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun AndroidComposeUiTest<ComponentActivity>.awaitAny(vararg texts: String) {
        try {
            waitUntil(timeoutMillis = 15_000) {
                texts.any { onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty() }
            }
        } catch (e: ComposeTimeoutException) {
            missed += texts.take(3).joinToString()
        }
    }

    private val missed = mutableListOf<String>()

    /**
     * Draw the window into a bitmap. `captureToImage()` timed out on CI for every screen (it waits for a
     * frame that a Robolectric window never commits), so the decor view is drawn by hand instead.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun AndroidComposeUiTest<ComponentActivity>.shoot(name: String) {
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        var bitmap: Bitmap? = null
        runOnUiThread {
            val view = activity!!.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val dir = File("build/store-screenshots").apply { mkdirs() }
        val out = File(dir, "$name.png")
        FileOutputStream(out).use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (missed.isNotEmpty()) {
            File(dir, "$name.MISSED.txt").writeText("Never appeared: $missed\n\n" + onRoot().printToString(maxDepth = 60))
        }
        assertTrue("$name.png was not written", out.length() > 0)
    }

    /** Every merchant the demo timeline can open on, so "data arrived" does not hinge on today's date. */
    private val anyDemoMerchant = arrayOf(
        "Swiggy", "Zomato", "Uber", "Ola Cabs", "Rapido", "BigBasket", "Blinkit", "Zepto", "DMart", "Amazon",
        "Flipkart", "Myntra", "Netflix", "Monthly Rent", "Groww SIP", "HDFC Home Loan EMI", "Country Delight",
        "House Help", "Acme Technologies Payroll", "Indian Oil", "Shell", "Tata Power", "Airtel", "Apollo Pharmacy",
        "BookMyShow", "PVR Cinemas", "WeWork India", "Chaayos", "Starbucks India", "Haldiram's", "Delhi Metro",
        "Namma Yatri", "Reliance Fresh", "Croma", "Decathlon", "MakeMyTrip", "Nykaa", "Spotify Premium",
        "Domino's Pizza", "Third Wave Coffee", "Behrouz Biryani", "Local store", "Misc",
    )

    // ---------------------------------------------------------------------------------------------
    // The home shell — HomeScreen builds its tabs with hiltViewModel(), so its frame is reproduced here
    // (same bottom bar, + button and privacy eye) around the real tab content built by hand.
    // ---------------------------------------------------------------------------------------------

    @Composable
    private fun HomeShell(analyticsSelected: Boolean, content: @Composable () -> Unit) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = !analyticsSelected,
                        onClick = {},
                        icon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null) },
                        label = { Text("Transactions") },
                    )
                    NavigationBarItem(
                        selected = analyticsSelected,
                        onClick = {},
                        icon = { Icon(Icons.Filled.PieChart, contentDescription = null) },
                        label = { Text("Analytics") },
                    )
                }
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {}, shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = "Add transaction")
                }
            },
        ) { padding ->
            // Balances revealed (the eye has been tapped), so the shot shows real figures.
            CompositionLocalProvider(LocalAmountsHidden provides false) {
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    content()
                    SmallFloatingActionButton(
                        onClick = {},
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        elevation = FloatingActionButtonDefaults.elevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp,
                            focusedElevation = 0.dp,
                            hoveredElevation = 0.dp,
                        ),
                        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).size(42.dp),
                    ) {
                        Icon(Icons.Filled.VisibilityOff, contentDescription = "Hide balances")
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Screens
    // ---------------------------------------------------------------------------------------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun shot_01_home_timeline() = runAndroidComposeUiTest<ComponentActivity> {
        val f = fixture()
        try {
            val vm = TransactionsViewModel(
                expenseRepository = f.expenses,
                settingsRepository = f.settings,
                periodSelectionStore = f.periodStore,
                captureRepository = f.capture,
                paymentMethodRepository = f.paymentMethods,
                categoryRepository = f.categories,
            )
            setContent {
                SpendsTheme(themeMode = ThemeMode.LIGHT) {
                    HomeShell(analyticsSelected = false) {
                        val snackbar = remember { SnackbarHostState() }
                        TransactionsScreen(
                            snackbarHostState = snackbar,
                            onEditTransaction = {},
                            onOpenSettings = {},
                            searchActive = false,
                            onSearchActiveChange = {},
                            searchInTopBar = true,
                            viewModel = vm,
                        )
                        SnackbarHost(snackbar)
                    }
                }
            }
            awaitAny(*anyDemoMerchant)
            shoot("01-home")
        } finally {
            f.db.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun shot_02_analytics() = runAndroidComposeUiTest<ComponentActivity> {
        val f = fixture()
        try {
            val vm = AnalyticsViewModel(
                expenseRepository = f.expenses,
                recurringRepository = f.recurring,
                settingsRepository = f.settings,
                periodSelectionStore = f.periodStore,
                paymentMethodRepository = f.paymentMethods,
            )
            setContent {
                SpendsTheme(themeMode = ThemeMode.LIGHT) {
                    HomeShell(analyticsSelected = true) {
                        AnalyticsScreen(
                            onOpenRecurring = {},
                            onOpenCategory = { _, _, _, _, _, _ -> },
                            onOpenSettings = {},
                            viewModel = vm,
                        )
                    }
                }
            }
            awaitAny("SPENDING BY CATEGORY")
            awaitAny("Groceries", "Food", "Transport")
            shoot("02-analytics")
        } finally {
            f.db.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun shot_03_banks_and_cards() = runAndroidComposeUiTest<ComponentActivity> {
        val f = fixture()
        try {
            val vm = CardsViewModel(
                paymentMethodRepository = f.paymentMethods,
                expenseRepository = f.expenses,
                settingsRepository = f.settings,
                smsCaptureRepository = f.capture,
            )
            setContent {
                SpendsTheme(themeMode = ThemeMode.LIGHT) {
                    CompositionLocalProvider(LocalAmountsHidden provides false) {
                        CardsScreen(onBack = {}, viewModel = vm)
                    }
                }
            }
            awaitAny("HDFC Millennia")
            shoot("03-cards")
        } finally {
            f.db.close()
        }
    }

    /** "Review & add" on a bank SMS from the review queue (Croma, on the HDFC card ending 4821). */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun shot_04_review_and_add_from_sms() = runAndroidComposeUiTest<ComponentActivity> {
        val f = fixture()
        try {
            val vm = AddEditViewModel(
                savedStateHandle = SavedStateHandle(mapOf<String, Any?>(Routes.ARG_PENDING_ID to f.pendingIds.first())),
                expenseRepository = f.expenses,
                categoryRepository = f.categories,
                captureRepository = f.capture,
                paymentMethodRepository = f.paymentMethods,
                settingsRepository = f.settings,
                captureDraftStore = CaptureDraftStore(),
            )
            setContent {
                SpendsTheme(themeMode = ThemeMode.LIGHT) {
                    AddEditScreen(onDone = {}, viewModel = vm)
                }
            }
            awaitAny("Review & add")
            awaitAny("Croma")
            shoot("04-review-and-add")
        } finally {
            f.db.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun shot_05_category_detail() = runAndroidComposeUiTest<ComponentActivity> {
        val f = fixture()
        try {
            val name = "Food"
            val id = f.categoryIds[name] ?: error("The seed has no $name category")
            val vm = CategoryTransactionsViewModel(
                savedStateHandle = SavedStateHandle(
                    mapOf<String, Any?>(
                        Routes.ARG_CATEGORY_ID to id,
                        Routes.ARG_CATEGORY_NAME to name,
                        Routes.ARG_KIND to TxnKind.EXPENSE.name,
                    ),
                ),
                expenseRepository = f.expenses,
                settingsRepository = f.settings,
                periodSelectionStore = f.periodStore,
                paymentMethodRepository = f.paymentMethods,
            )
            setContent {
                SpendsTheme(themeMode = ThemeMode.LIGHT) {
                    CompositionLocalProvider(LocalAmountsHidden provides false) {
                        CategoryTransactionsScreen(onBack = {}, viewModel = vm)
                    }
                }
            }
            awaitAny(
                "Swiggy", "Zomato", "Domino's Pizza", "Chaayos", "Third Wave Coffee", "Haldiram's",
                "Behrouz Biryani", "Starbucks India", "MakeMyTrip",
            )
            shoot("05-category-food")
        } finally {
            f.db.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun shot_06_recurring() = runAndroidComposeUiTest<ComponentActivity> {
        val f = fixture()
        try {
            val vm = RecurringViewModel(
                context = f.context,
                recurringRepository = f.recurring,
                settingsRepository = f.settings,
                paymentMethodRepository = f.paymentMethods,
                categoryRepository = f.categories,
            )
            setContent {
                SpendsTheme(themeMode = ThemeMode.LIGHT) {
                    CompositionLocalProvider(LocalAmountsHidden provides false) {
                        RecurringScreen(onBack = {}, viewModel = vm)
                    }
                }
            }
            awaitAny("Netflix")
            shoot("06-recurring")
        } finally {
            f.db.close()
        }
    }

    /**
     * Settings > Automatic entries > Detect from SMS & notifications, with SMS detection on and the demo's
     * review queue waiting. `CaptureSettingsScreen` builds its section with hiltViewModel(), so its frame
     * (same top bar, same scrolling column) is reproduced here around the real section.
     */
    @OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
    @Test
    fun shot_07_sms_capture_settings() = runAndroidComposeUiTest<ComponentActivity> {
        val f = fixture(smsCaptureOn = true)
        try {
            val vm = CaptureViewModel(settingsRepository = f.settings, captureRepository = f.capture)
            setContent {
                SpendsTheme(themeMode = ThemeMode.LIGHT) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("Detect from SMS & notifications") },
                                navigationIcon = {
                                    IconButton(onClick = {}) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                },
                            )
                        },
                    ) { padding ->
                        androidx.compose.foundation.layout.Column(
                            modifier = Modifier
                                .padding(padding)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp),
                        ) {
                            CaptureSection(onOpenReview = {}, onOpenSilencedAlerts = {}, viewModel = vm)
                        }
                    }
                }
            }
            awaitAny("waiting for you to confirm")
            shoot("07-sms-capture")
        } finally {
            f.db.close()
        }
    }
}
