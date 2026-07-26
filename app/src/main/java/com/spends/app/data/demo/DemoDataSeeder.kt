package com.spends.app.data.demo

import android.content.Context
import androidx.room.withTransaction
import com.spends.app.core.category.ColorAssigner
import com.spends.app.core.category.IconAssigner
import com.spends.app.core.time.DateUtils
import com.spends.app.data.ai.GroqClient
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.db.dao.CategoryDao
import com.spends.app.data.db.dao.ExpenseDao
import com.spends.app.data.db.dao.MerchantCategoryDao
import com.spends.app.data.db.dao.PaymentMethodDao
import com.spends.app.data.db.dao.PendingCaptureDao
import com.spends.app.data.db.dao.RecurringDao
import com.spends.app.data.capture.MerchantKeys
import com.spends.app.data.db.entity.AllocationEntity
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.ExpenseEntity
import com.spends.app.data.db.entity.MerchantCategoryEntity
import com.spends.app.data.db.entity.PaymentMethodEntity
import com.spends.app.data.db.entity.PendingCaptureEntity
import com.spends.app.data.db.entity.RecurringRuleEntity
import com.spends.app.data.importer.DedupeKey
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.DefaultLanding
import com.spends.app.domain.model.Direction
import com.spends.app.domain.model.PaymentMethodType
import com.spends.app.domain.model.TxnKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Fills the demo database from [DemoScript].
 *
 * ## The guard is the important part
 * This class deletes every row in the database it is pointed at. It therefore refuses to run unless **both**
 * of these hold:
 *  1. [DemoMode.isEnabled] — the process was started in demo mode; and
 *  2. the open Room file is literally [DemoMode.DEMO_DB_NAME].
 *
 * Check 2 is the one that actually matters: it inspects the file being written rather than trusting a flag,
 * so even a future refactor that got the wiring wrong would fail loudly here instead of wiping real money.
 */
@Singleton
class DemoDataSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SpendsDatabase,
    private val categoryDao: CategoryDao,
    private val expenseDao: ExpenseDao,
    private val paymentMethodDao: PaymentMethodDao,
    private val recurringDao: RecurringDao,
    private val pendingCaptureDao: PendingCaptureDao,
    private val merchantCategoryDao: MerchantCategoryDao,
    private val settingsRepository: SettingsRepository,
    // A Provider, not the instance: this seeder is a constructor dependency of MainViewModel, so eagerly
    // injecting GroqClient would build an OkHttpClient and load SecureKeyStore's SharedPreferences on the
    // main thread during EVERY cold start — including for the vast majority of users who never open demo
    // mode. Resolved only inside applyDemoSettings, which runs only in the sandbox.
    private val groqClient: Provider<GroqClient>,
) {

    /** Wipe and rebuild the demo account as of today. Safe to call repeatedly ("Reset demo data"). */
    suspend fun seed() {
        requireDemoSandbox()
        val today = LocalDate.now(DateUtils.ZONE)

        // Settings FIRST, deliberately. They are cheap and cannot fail on data volume, and the important one
        // is `onboardingComplete`: if a later step threw with it still false, the demo would drop into the
        // welcome flow — where "Restore from Google Drive" is offered, which in demo mode is a dead end.
        applyDemoSettings(today)

        // ~800 transactions and ~10,000 weighted day-picks. Off the main thread: this runs during ViewModel
        // creation on a cold start, behind the splash, and has no business stalling it.
        val data = withContext(Dispatchers.Default) { DemoScript.build(today) }
        val now = DateUtils.nowMillis()

        db.withTransaction {
            // Clear in dependency order: allocations reference expenses AND categories (RESTRICT), so they go
            // first; categories are rebuilt from the seed set so learned mappings must go too.
            expenseDao.deleteAllAllocations()
            expenseDao.deleteAllExpenses()
            pendingCaptureDao.deleteAll()
            recurringDao.deleteAll()
            merchantCategoryDao.deleteAll()
            paymentMethodDao.deleteAll()

            val categoryIds = ensureCategories()
            val fallbackId = categoryIds["Other"] ?: categoryIds.values.first()
            fun categoryId(name: String): Long = categoryIds[name] ?: fallbackId

            val instrumentIds = insertInstruments(now)
            val ruleIds = insertRecurringRules(data.recurring, categoryIds, instrumentIds, today, now)

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
                        // Replayed recurring occurrences get the SAME hash the materialiser would have
                        // produced. `nextRunAt` already prevents re-generating them, but that is a single
                        // point of failure; this restores the app's own second net, since the materialiser
                        // skips any occurrence whose hash it already finds in the ledger.
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

            for (p in data.pending) {
                val occurredAt = DateUtils.epochMillisFor(today.minusDays(p.daysAgo.toLong()), 11, 20)
                pendingCaptureDao.insert(
                    PendingCaptureEntity(
                        amountMinor = p.amountMinor,
                        kind = p.kind,
                        occurredAt = occurredAt,
                        merchant = p.merchant,
                        last4 = p.last4,
                        institution = p.institution,
                        categoryId = categoryId(p.categoryName),
                        parseConfidence = p.parseConfidence,
                        // Namespaced so a demo row can never collide with a hash the real capture pipeline made.
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
        }

        // Only now is the account genuinely built — a failure above leaves this unset so the next launch retries.
        DemoMode.markSeeded(context, today.toEpochDay())
    }

    /** Both checks must pass. See the class doc — check 2 inspects the actual open file, not a flag. */
    private fun requireDemoSandbox() {
        check(DemoMode.isEnabled(context)) { "DemoDataSeeder ran outside demo mode" }
        val open = db.openHelper.databaseName
        check(open == DemoMode.DEMO_DB_NAME) { "DemoDataSeeder pointed at '$open', not the demo database" }
    }

    /**
     * The demo's categories: whatever the Room seed callback created, plus the custom ones the demo needs and
     * one archived category so category management has something to show. Returns name → id.
     */
    private suspend fun ensureCategories(): Map<String, Long> {
        val existing = categoryDao.getAllOnce().associateBy { it.name }.toMutableMap()
        val taken = existing.values.map { it.colorHex }.toMutableSet()
        var order = (existing.values.maxOfOrNull { it.sortOrder } ?: 0) + 1

        suspend fun add(name: String, archived: Boolean) {
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

        DemoScript.CUSTOM_EXPENSE_CATEGORIES.forEach { add(it, archived = false) }
        add(DemoScript.ARCHIVED_CATEGORY, archived = true)
        return existing.mapValues { it.value.id }
    }

    /** Two credit cards (different billing days, so Smart Cycle's roll-to-next is visible), a bank and a UPI. */
    private suspend fun insertInstruments(now: Long): Map<DemoInstrument, Long> {
        suspend fun add(instrument: DemoInstrument, type: PaymentMethodType, label: String, institution: String, last4: String?, color: String, billingDay: Int?, dueDay: Int?): Pair<DemoInstrument, Long> =
            instrument to paymentMethodDao.insert(
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
        return mapOf(
            add(DemoInstrument.CARD_HDFC, PaymentMethodType.CREDIT_CARD, "HDFC Millennia", "HDFC Bank", "4821", "#0F766E", 5, 25),
            add(DemoInstrument.CARD_AXIS, PaymentMethodType.CREDIT_CARD, "Axis Magnus", "Axis Bank", "4409", "#B45309", 18, 8),
            add(DemoInstrument.BANK_ICICI, PaymentMethodType.BANK_ACCOUNT, "ICICI Savings", "ICICI Bank", "8842", "#1D4ED8", null, null),
            add(DemoInstrument.UPI_GPAY, PaymentMethodType.UPI, "GPay UPI", "ICICI Bank", null, "#7C3AED", null, null),
        )
    }

    /**
     * Insert the rules, and — critically — point `nextRunAt` at the first occurrence **after today**.
     *
     * [DemoScript] has already written every past occurrence as a real transaction. If `nextRunAt` were left in
     * the past, the app's materialiser would run on next launch and generate that same history a second time,
     * doubling rent, EMI and SIP in the demo's balance.
     */
    private suspend fun insertRecurringRules(
        rules: List<DemoRecurring>,
        categoryIds: Map<String, Long>,
        instrumentIds: Map<DemoInstrument, Long>,
        today: LocalDate,
        now: Long,
    ): Map<String, Long> = rules.associate { rule ->
        var occurrence = DemoScript.firstOccurrence(rule, today)
        val startDate = occurrence
        var lastRun: LocalDate? = null
        while (!occurrence.isAfter(today)) {
            lastRun = occurrence
            occurrence = DemoScript.stepRecurring(occurrence, rule)
        }
        rule.key to recurringDao.insert(
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

    /**
     * The demo account's preferences. These write to the **demo** DataStore file, never the real one.
     *
     * `onboardingComplete` is the load-bearing one: a fresh preferences file defaults it to false, which would
     * drop the demo into the welcome flow instead of the app.
     */
    private suspend fun applyDemoSettings(today: LocalDate) {
        settingsRepository.setOnboardingComplete(true)
        settingsRepository.setSalaryCycleStartDay(DemoScript.SALARY_DAY)
        settingsRepository.setDefaultLanding(DefaultLanding.TRANSACTIONS)
        settingsRepository.setTrashRetentionDays(30)

        // Smart Cycle on, following the salary day — the richest period model to demo.
        settingsRepository.setSmartCycleEnabled(true)
        settingsRepository.setSmartCycleResetDay(0)

        // Carry-forward anchored at the start of the dense window, with a believable opening balance, so the
        // carry-forward tile is populated for the cycles you'll actually show and absent before it (which is
        // itself the anchor behaviour worth demonstrating).
        val anchor = YearMonth.from(today).minusMonths((DemoScript.RICH_MONTHS - 1).toLong()).atDay(1)
        settingsRepository.setCarryForwardEnabled(true)
        settingsRepository.setCarryForwardAnchor(anchor.toEpochDay())
        settingsRepository.setCarryForwardOpening(1_25_000_00L)

        // Nothing in demo mode may reach the network or the real inbox. Capture is hard-gated in the receivers
        // too — this just keeps the toggles reading honestly.
        settingsRepository.setAutoBackupEnabled(false)
        settingsRepository.setSmsCaptureEnabled(false)
        settingsRepository.setNotificationCaptureEnabled(false)

        // If the user already has a Groq key, switch the AI helper on so its features are demoable. The key
        // itself is device-local and shared (it is not part of the swapped settings file).
        if (groqClient.get().hasKey()) {
            settingsRepository.setAiEnabled(true)
            settingsRepository.setAiCategorize(true)
            settingsRepository.setAiInsights(true)
        }
    }
}
