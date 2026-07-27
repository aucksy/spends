package com.spends.app.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.period.CardCycleInfo
import com.spends.app.core.period.CompositeCycleResolver
import com.spends.app.core.period.CompositePeriod
import com.spends.app.core.period.PeriodRange
import com.spends.app.core.period.PeriodResolver
import com.spends.app.core.period.PeriodSelection
import com.spends.app.core.period.PeriodSelectionStore
import com.spends.app.core.period.PeriodType
import com.spends.app.core.period.SmartCardCycle
import com.spends.app.core.time.CycleUtils
import com.spends.app.core.time.CycleWindow
import com.spends.app.core.time.DateUtils
import com.spends.app.data.ai.AiInsights
import com.spends.app.data.ai.GroqClient
import com.spends.app.data.ai.InsightPayload
import com.spends.app.data.ai.insights.InsightCalendar
import com.spends.app.data.ai.insights.InsightCard
import com.spends.app.data.ai.insights.InsightKind
import com.spends.app.data.ai.insights.InsightsProvider
import com.spends.app.data.db.entity.CategorySpend
import com.spends.app.data.db.entity.ExpenseWithAllocations
import com.spends.app.data.db.entity.KindSum
import com.spends.app.data.db.entity.PaymentMethodEntity
import com.spends.app.data.db.entity.RecurringRuleEntity
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.repo.RecurringRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.data.settings.SettingsState
import com.spends.app.domain.model.RecurrenceFreq
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.cards.isCardInstrument
import com.spends.app.ui.components.CardChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.ceil

/** Totals of active recurring rules in one frequency bucket (cycle-independent). */
data class RecurringFreqSummary(
    val frequency: RecurrenceFreq,
    val outMinor: Long,
    val inMinor: Long,
    val count: Int,
)

/** One category wedge of the donut + legend. */
data class CategorySlice(
    val categoryId: Long,
    val name: String,
    val colorHex: String,
    val iconKey: String,
    val amountMinor: Long,
    val percent: Int,
)

data class AnalyticsUiState(
    val loading: Boolean = true,
    val periodLabel: String = "",
    val expenseMinor: Long = 0,
    val incomeMinor: Long = 0,
    // Spend actually categorised in the breakdown — reconciles the donut centre with its wedges/legend.
    val categorisedSpendMinor: Long = 0,
    val categories: List<CategorySlice> = emptyList(),
    val weekly: List<Float> = emptyList(),
    val weekLabels: List<String> = emptyList(),
    val recurring: List<RecurringFreqSummary> = emptyList(),
    // The selected period's epoch-millis bounds, so tapping a category drills into that exact window.
    val windowStartMillis: Long = 0,
    val windowEndExclusiveMillis: Long = 0,
    // True for the Smart Cycle composite (Round B) — Analytics offers the "per-instrument breakdown" link (#4).
    val isComposite: Boolean = false,
) {
    val netMinor: Long get() = incomeMinor - expenseMinor
    val isEmpty: Boolean get() = !loading && expenseMinor == 0L && incomeMinor == 0L
}

/** AI insights card state (#2). Read-only text; hidden unless AI + the sub-toggle + a key are on, and never
 *  present for an empty cycle. The carousel hides whenever [cards] is empty, so failing closed needs no
 *  separate flag; [failed] only distinguishes "nothing came back" from "quiet cycle" for a future error
 *  state, and nothing renders it today. */
data class InsightsUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    /** Page 1 is the cycle summary; the rest are findings the engine judged worth saying. Empty = nothing
     *  to show, which is a legitimate answer for a quiet cycle rather than a failure. */
    val cards: List<InsightCard> = emptyList(),
    val failed: Boolean = false,
)

/** A resolved Analytics period — [composite] is non-null for the Smart Cycle composite (Round B). */
private data class ResolvedB(
    val startMillis: Long,
    val endExclusiveMillis: Long,
    val label: String,
    val composite: CompositePeriod? = null,
    // Card-billing-aware Smart Cycle. When set, [startMillis, endExclusiveMillis) is the DISPLAY cycle, and
    // [smartCard] carries the WIDE fetch range + the per-card billing days used to bucket each txn in memory
    // (a card purchase counts in the cycle where its statement bills). null for every other view.
    val smartCard: SmartCardCtx? = null,
)

/** In-memory bucketing context for the card-billing-aware Smart Cycle (see [SmartCardCycle]). */
private data class SmartCardCtx(
    val fetchStartMillis: Long,
    val fetchEndExclusiveMillis: Long,
    val resetDay: Int,
    val cardBillingDays: Map<Long, Int?>,
)

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val recurringRepository: RecurringRepository,
    private val settingsRepository: SettingsRepository,
    private val periodSelectionStore: PeriodSelectionStore,
    private val paymentMethodRepository: PaymentMethodRepository,
    private val aiInsights: AiInsights,
    private val insightsProvider: InsightsProvider,
    private val groqClient: GroqClient,
) : ViewModel() {

    // Shared with the Transactions screen (#8) so the cycle/range stays in sync across both.
    private val selection = periodSelectionStore.selection
    val periodSelection: StateFlow<PeriodSelection> = selection

    // Drives the period selector's Smart pill + Single-Card picker (Round B), mirroring Transactions.
    val smartCycleEnabled: StateFlow<Boolean> =
        settingsRepository.settings.map { it.smartCycleEnabled }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    // Only CARDS can be viewed as a single instrument (their own billing cycle); banks ride the salary
    // cycle, so they're excluded from the Single-Card picker (#2).
    val cardChoices: StateFlow<List<CardChoice>> =
        paymentMethodRepository.observeConfirmed()
            .map { cards -> cards.filter { it.isCardInstrument() }.map { CardChoice(it.id, it.label, it.colorHex) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val resolvedFlow: StateFlow<ResolvedB> =
        combine(
            selection,
            settingsRepository.settings,
            expenseRepository.observeEarliestDay(),
            paymentMethodRepository.observeConfirmed(),
        ) { sel, settings, earliest, cards ->
            val today = LocalDate.now(DateUtils.ZONE)
            // Smart Cycle (all instruments) is ONE contiguous window anchored on the user's reset day
            // (default = salary day) — a card's spends stay counted after its billing day passes. Only
            // Single-Card mode still uses the composite (that one card's own billing cycle).
            when {
                settings.smartCycleEnabled && sel.type == PeriodType.SMART_CYCLE && sel.selectedCardId != null ->
                    resolveSingleCard(sel, settings, today, cards)
                // Smart Cycle across ALL cards: bucket each card's spends by its billing day (billed spends
                // roll into the next cycle), computed in memory — reconciles with the timeline.
                settings.smartCycleEnabled && sel.type == PeriodType.SMART_CYCLE ->
                    resolveSmartCardComposite(settings, today, cards, sel.cycleOffset)
                else -> {
                val effType = if (!settings.smartCycleEnabled && sel.type == PeriodType.SMART_CYCLE) {
                    PeriodType.SALARY_CYCLE
                } else {
                    sel.type
                }
                val r = PeriodResolver.resolve(
                    type = effType,
                    range = sel.range,
                    salaryDay = settings.salaryCycleStartDay,
                    smartDay = settings.effectiveSmartResetDay,
                    today = today,
                    earliestDataDay = earliest?.let { DateUtils.toLocalDate(it) },
                    customStartMillis = sel.customStartMillis,
                    customEndExclusiveMillis = sel.customEndExclusiveMillis,
                    cycleOffset = sel.cycleOffset,
                )
                ResolvedB(r.startMillis, r.endExclusiveMillis, r.label)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, currentCycle())

    val state: StateFlow<AnalyticsUiState> =
        resolvedFlow.flatMapLatest { resolved ->
            val sc = resolved.smartCard
            if (sc != null) {
                // Card-billing-aware Smart Cycle: fetch the wide range, bucket to the display cycle in memory
                // via the SAME rule as the timeline, then derive the donut + weekly + totals from those items.
                return@flatMapLatest combine(
                    expenseRepository.observeBetween(sc.fetchStartMillis, sc.fetchEndExclusiveMillis),
                    recurringRepository.observeAll(),
                ) { items, rules -> buildStateSmartCard(resolved, items, rules) }
            }
            combine(
                expenseRepository.observeCategorySpend(resolved.startMillis, resolved.endExclusiveMillis),
                expenseRepository.observeKindSums(resolved.startMillis, resolved.endExclusiveMillis),
                expenseRepository.observeBetween(resolved.startMillis, resolved.endExclusiveMillis),
                recurringRepository.observeAll(),
            ) { catSpend, kindSums, items, rules ->
                if (resolved.composite != null) {
                    buildStateComposite(resolved, items, rules)
                } else {
                    buildState(resolved, catSpend, kindSums, items, rules)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

    // ---- AI insights (#2) — read-only, aggregates only, cached per cycle ----
    private val _insights = MutableStateFlow(InsightsUiState())
    val insights: StateFlow<InsightsUiState> = _insights

    /** Whether the Analytics tab is actually on screen. See the gate in `init`. */
    private val analyticsVisible = MutableStateFlow(false)

    fun setAnalyticsVisible(visible: Boolean) { analyticsVisible.value = visible }

    init {
        viewModelScope.launch {
            // Gate = master + sub-toggle + a key, all REACTIVE. When OFF, flatMapLatest yields flowOf(null) and we
            // never subscribe to `state`, so the analytics DB flows stay idle for AI-off users (no battery cost).
            combine(
                settingsRepository.settings.map { it.aiEnabled && it.aiInsights }.distinctUntilChanged(),
                groqClient.hasKeyFlow,
                // This ViewModel is scoped to the Home back-stack entry, so it outlives the Analytics tab.
                // Without a visibility gate the collector stays subscribed and any ledger change — confirming
                // a capture, adding an expense — fires Groq calls while the user is on another tab. The
                // shipped promise is "nothing is sent while you're not looking"; this is what keeps it.
                analyticsVisible,
            ) { on, hasKey, visible -> on && hasKey && visible }
                .distinctUntilChanged()
                .flatMapLatest { gateOn ->
                    if (!gateOn) {
                        flowOf<String?>(null)
                    } else {
                        // ONLY the fingerprint drives re-summarising. Unrelated churn (a recurring edit, an
                        // unrelated DataStore write) keeps the fingerprint the same → distinctUntilChanged drops
                        // it → the in-flight call is NOT cancelled and completes (fixes the "stuck on Thinking…"
                        // hang). null = an empty/loading cycle → hide the card.
                        //
                        // The salary day is folded in because it decides whether the payday card may appear at
                        // all, and on a plain Month view it does NOT move the window bounds — so without it,
                        // changing your salary day leaves the cached carousel showing (or hiding) that card
                        // wrongly until some total happens to change. It is mapped to just the day and made
                        // distinct first, so other settings writes still cause no churn.
                        combine(
                            state,
                            settingsRepository.settings
                                .map { it.salaryCycleStartDay to it.smartCycleEnabled }
                                .distinctUntilChanged(),
                            // The cycle TYPE has to be its own input. With the default reset day, Salary and
                            // Smart resolve to byte-identical windows — same bounds, same totals, same
                            // categories — so `state` conflates them and the fingerprint collides, while the
                            // type is exactly what decides whether the two whole-cycle cards may appear.
                            selection.map { it.type to it.range }.distinctUntilChanged(),
                        ) { st, (salaryDay, smartCycle), (type, range) ->
                            if (st.loading || st.isEmpty) {
                                null
                            } else {
                                insightFingerprint(st, salaryDay, smartCycle, type, range)
                            }
                        }.distinctUntilChanged()
                    }
                }
                .collectLatest { fp ->
                    if (fp == null) {
                        if (_insights.value.visible) _insights.value = InsightsUiState(visible = false)
                        return@collectLatest
                    }
                    _insights.value = InsightsUiState(visible = true, loading = true)
                    val cards = buildCardsOrEmpty(state.value, fp, forceRefresh = false)
                    _insights.value = InsightsUiState(visible = true, loading = false, cards = cards, failed = cards.isEmpty())
                }
        }
    }

    /** Re-generate the carousel bypassing the per-cycle caches (the card's refresh button). */
    fun refreshInsights() = viewModelScope.launch {
        // The settings read is INSIDE the guard: it is a DataStore call like any other, and an unhandled
        // throw here escapes a bare launch and takes the coroutine down.
        val st = state.value
        val s = runCatching { settingsRepository.settings.first() }
            .getOrElse { if (it is CancellationException) throw it else return@launch }
        if (!(s.aiEnabled && s.aiInsights) || !groqClient.hasKey() || st.loading || st.isEmpty) return@launch
        _insights.value = InsightsUiState(visible = true, loading = true)
        val sel = selection.value
        val fingerprint = insightFingerprint(st, s.salaryCycleStartDay, s.smartCycleEnabled, sel.type, sel.range)
        val cards = buildCardsOrEmpty(st, fingerprint, forceRefresh = true)
        _insights.value = InsightsUiState(visible = true, loading = false, cards = cards, failed = cards.isEmpty())
    }

    /**
     * [buildCards], but a thrown exception costs the cards rather than the collector.
     *
     * Without this, anything that throws — a DataStore read failing on an unreadable prefs file, say —
     * propagates out of `collectLatest` and kills the insights collector for the ViewModel's whole lifetime,
     * leaving the card pinned on "Thinking…" with no error state and no way back. That is the exact hang
     * v1.56.1 fixed once already, so it gets a guard rather than an argument about whether it can happen.
     * `CancellationException` is rethrown so structured concurrency still works.
     */
    private suspend fun buildCardsOrEmpty(st: AnalyticsUiState, fingerprint: String, forceRefresh: Boolean): List<InsightCard> =
        runCatching { buildCards(st, fingerprint, forceRefresh) }
            .getOrElse { if (it is CancellationException) throw it else emptyList() }

    /**
     * The carousel: the cycle summary (page 1, unchanged since v1.56.0) followed by whatever the on-device
     * `InsightEngine` found worth saying.
     *
     * The two run **concurrently**. They are independent calls, and doing them in sequence would make a
     * carousel of six cards feel twice as slow as the single card it replaces.
     */
    private suspend fun buildCards(st: AnalyticsUiState, fingerprint: String, forceRefresh: Boolean): List<InsightCard> =
        coroutineScope {
            // Read once and share: both halves need the same settings snapshot, and page 1's "vs last cycle"
            // now depends on them exactly as the finding cards do.
            val settings = settingsRepository.settings.first()
            val summary = async {
                aiInsights.summarize(buildInsightPayload(st, settings), forceRefresh = forceRefresh)
            }
            val findings = async {
                // Finding cards need a single navigable cycle AND on-screen figures drawn from the same
                // dataset the history query sees. Neither holds otherwise:
                //  - Single-Card (isComposite) filters the screen to ONE instrument, while the history query
                //    carries no instrument filter — every category would read as a collapse, and an outlier
                //    on a different card would be described as if it were on this one.
                //  - All-time / Last-3 / Custom aren't a cycle, so "so far this cycle" would be a lie.
                if (st.isComposite || selection.value.range != PeriodRange.CURRENT) {
                    emptyList()
                } else {
                    insightsProvider.cards(
                        fingerprint = fingerprint,
                        cycleLabel = selection.value.describe(),
                        windowStartMillis = st.windowStartMillis,
                        windowEndExclusiveMillis = st.windowEndExclusiveMillis,
                        cycleBoundaries = cycleBoundaries(st.windowStartMillis, selection.value, settings),
                        // Sum rather than associate: category names carry no unique index, so two
                        // same-named categories would silently drop one and understate that category.
                        currentByCategory = st.categories.groupBy { it.name }
                            .mapValues { (_, slices) -> slices.sumOf { it.amountMinor } },
                        expenseMinor = st.expenseMinor,
                        // Phase C: the SAVINGS-RATE card is a share of what came in, taken from the same
                        // reconciled on-screen state as the expense total so it can never quote an income
                        // the charts above it disagree with. (The commitments card does NOT use this — its
                        // denominator is the median of prior COMPLETE cycles, read from the ledger.)
                        incomeMinor = st.incomeMinor,
                        paydayAligned = DateUtils.toLocalDate(st.windowStartMillis).dayOfMonth ==
                            settings.salaryCycleStartDay,
                        // Smart Cycle across all cards buckets a card purchase into the cycle its statement
                        // BILLS, while the history queries read raw transaction dates. The whole-total
                        // comparisons are the two that cannot survive that difference.
                        wholeCycleComparable = !(
                            settings.smartCycleEnabled && selection.value.type == PeriodType.SMART_CYCLE
                            ),
                        forceRefresh = forceRefresh,
                    )
                }
            }
            // A failed summary drops that page rather than showing placeholder prose; the finding cards each
            // carry their own template fallback, so they survive a failed narration.
            val summaryCard = summary.await()?.let { InsightCard(InsightKind.CYCLE_SUMMARY, "This cycle", it) }
            listOfNotNull(summaryCard) + findings.await()
        }

    /**
     * The **real** starts of the displayed cycle and the six before it, descending, newest first.
     *
     * ⭐This exists because the obvious shortcut is wrong. Walking back by subtracting the current cycle's
     * length six times looks equivalent and isn't: cycles are 28, 30 or 31 days, so the synthetic boundaries
     * slide further off the real ones the further back you go — for a salary day of 1, February alone drags
     * every earlier boundary days out of step. Rent paid on the 1st then lands near the *end* of a drifted
     * window, past the point this cycle has reached, and drops out of the baseline while this cycle's rent
     * is still counted. The card then says "your recent cycles were at ₹11,000 by this point" about cycles
     * that were at ₹36,000, which is the same class of confident falsehood day-alignment exists to prevent.
     *
     * So the boundaries come from `CycleUtils` — the same code that drew the window on screen — and the
     * provider refuses to produce anything if the first one doesn't match that window.
     *
     * ⭐Anchored on **the displayed window's own start date**, never on today's date replayed through the
     * cycle offset. `windowFor(w.start, anchor).start == w.start`, so `boundaries[0] == startMillis` holds by
     * construction on every path, and this stops having to mirror `PeriodResolver`'s anchor rules — a
     * duplicated invariant that could drift. It also closes a real hole: `resolvedFlow` recomputes its
     * `today` only when the selection, settings, earliest day or cards change, so an app left open overnight
     * carries yesterday's window into the next morning. Reading a fresh clock here would then disagree with
     * it, the provider would fail closed, and the user would silently lose the **whole** carousel — Phase A's
     * cards included — for one day every month.
     */
    private fun cycleBoundaries(startMillis: Long, sel: PeriodSelection, settings: SettingsState): List<Long> {
        val month = sel.type == PeriodType.MONTH
        // Mirrors PeriodResolver's anchor choice, including its coercion of Smart to Salary when the Smart
        // Cycle setting is off.
        val anchor = if (settings.smartCycleEnabled && sel.type == PeriodType.SMART_CYCLE) {
            settings.effectiveSmartResetDay
        } else {
            settings.salaryCycleStartDay
        }
        val startDate = DateUtils.toLocalDate(startMillis)
        var window = if (month) CycleUtils.calendarMonth(startDate) else CycleUtils.windowFor(startDate, anchor)
        val out = mutableListOf(window.startMillis())
        repeat(InsightsProvider.HISTORY_CYCLES) {
            window = previousCycle(window, month, anchor)
            out += window.startMillis()
        }
        return out
    }

    private fun previousCycle(window: CycleWindow, month: Boolean, anchor: Int): CycleWindow =
        if (month) CycleUtils.calendarMonth(window.start.minusDays(1)) else CycleUtils.previousWindow(window, anchor)

    private fun insightFingerprint(
        st: AnalyticsUiState,
        salaryDay: Int,
        smartCycle: Boolean,
        type: PeriodType,
        range: PeriodRange,
    ): String = buildString {
        append(st.windowStartMillis).append('|').append(st.windowEndExclusiveMillis)
        append('|').append(st.incomeMinor).append('|').append(st.expenseMinor)
        // All four decide whether a card may appear at all — the salary day gates the payday habit; the Smart
        // Cycle switch and the cycle type together gate the two whole-cycle comparisons; the range gates every
        // finding card — while none of them necessarily moves a total. Without them a cached carousel outlives
        // the setting that should have suppressed it.
        append('|').append(salaryDay).append('|').append(smartCycle)
        append('|').append(type).append('|').append(range)
        // The NAME as well as the id: the provider is handed categories keyed by name and the name is what
        // ends up on the card, so renaming "Dining" to "Food" leaves a cached carousel talking about a
        // category that no longer exists.
        st.categories.forEach {
            append('|').append(it.categoryId).append(':').append(it.name).append(':').append(it.amountMinor)
        }
        // The pace card says "day N of the cycle", which ages on the clock rather than on the data. Without
        // this, opening Analytics a week later with nothing added would serve the cached card and still claim
        // day 5. Clamped to the cycle length exactly as the engine clamps it, so a *finished* cycle keeps one
        // stable fingerprint instead of re-narrating itself every day the user browses back to it.
        append('|').append(insightDayOfCycle(st))
    }

    private fun insightDayOfCycle(st: AnalyticsUiState): Int {
        val cycleDays = InsightCalendar.cycleDays(st.windowStartMillis, st.windowEndExclusiveMillis, DateUtils.ZONE)
        if (cycleDays <= 0) return 0
        return InsightCalendar.daysElapsed(st.windowStartMillis, DateUtils.nowMillis(), DateUtils.ZONE)
            .coerceIn(0, cycleDays)
    }

    /**
     * Build the aggregates-only insight payload from the reconciled on-screen [st] (so the card's ₹ figures match
     * the charts) plus a one-shot previous-cycle read. Sends NO dates, NO rows, NO merchants, NO balances — the
     * cycle label is the descriptive name (e.g. "Current Salary Cycle"), never concrete transaction dates.
     */
    private suspend fun buildInsightPayload(st: AnalyticsUiState, settings: SettingsState): InsightPayload {
        val sel = selection.value
        val byCategory = st.categories.map { InsightPayload.CategoryTotal(it.name, it.amountMinor) }
        var lastExpense: Long? = null
        var lastByCategory: List<InsightPayload.CategoryTotal>? = null
        // ⭐"vs last cycle" is dropped outright in the two modes where the comparison cannot be honest — the
        // same two the finding cards already refuse. Single-Card filters the screen to ONE instrument while
        // these queries carry no instrument filter, so page 1 was comparing one card's spend against every
        // instrument's previous window ("spending fell from ₹1,20,000 to ₹8,000"). Smart Cycle buckets a card
        // purchase into the cycle its statement bills while these read raw dates.
        val comparable = sel.range == PeriodRange.CURRENT &&
            !st.isComposite &&
            !(settings.smartCycleEnabled && sel.type == PeriodType.SMART_CYCLE)
        if (comparable && st.windowEndExclusiveMillis > st.windowStartMillis) {
            // ⭐The REAL previous cycle, not this one's length subtracted. Cycles are 28/30/31 days, so a
            // fixed-span step lands days off: viewing February with a salary day of 1, the "previous cycle"
            // began on 4 January and the rent paid on the 1st fell outside it — page 1 then read "you spent
            // ₹25,000 more than last cycle" when nothing had changed. This is the Phase B blocker, and it was
            // alive here too because this payload predates that fix and never went through it.
            val boundaries = cycleBoundaries(st.windowStartMillis, sel, settings)
            // ⭐Fail closed on boundaries that don't describe the window on screen — the same check
            // InsightsProvider makes, and page 1 needs it for the same reason. Deriving them re-reads the
            // DEVICE TIMEZONE, while `windowStartMillis` was resolved in whatever zone was in force earlier
            // and is not recomputed on a zone change. Fly west and the two disagree: without this, page 1
            // silently compares against a cycle two months out. The arithmetic this replaced could not go
            // wrong that way, so the guard is the price of using real cycles.
            if (boundaries.size >= 2 && boundaries.first() == st.windowStartMillis) {
                val prevStart = boundaries[1]
                val prevEnd = boundaries[0]
                val prevCategories = expenseRepository.categorySpendOnce(prevStart, prevEnd)
                val prevExpense = if (prevCategories.isEmpty()) {
                    null
                } else {
                    expenseRepository.kindSumsOnce(prevStart, prevEnd)
                        .firstOrNull { it.kind == TxnKind.EXPENSE }?.total
                }
                // ⭐No rows means the app wasn't in use, not that nothing was spent. Defaulting to ₹0 told
                // every brand-new user "₹45,000 this cycle, well above last cycle" about a month Spends
                // didn't exist for them — the same falsehood the year-on-year card carries three gates
                // against. Null omits the comparison from the payload entirely.
                if (prevExpense != null && prevExpense > 0L) {
                    lastExpense = prevExpense
                    lastByCategory = prevCategories.map { InsightPayload.CategoryTotal(it.name, it.total) }
                }
            }
        }
        return InsightPayload(
            cycleLabel = sel.describe(),
            incomeMinor = st.incomeMinor,
            expenseMinor = st.expenseMinor,
            byCategory = byCategory,
            lastCycleExpenseMinor = lastExpense,
            lastCycleByCategory = lastByCategory,
        )
    }

    fun applySelection(sel: PeriodSelection) = periodSelectionStore.set(sel)

    /** Single-Card mode: that one card's own billing cycle (falls back to the Smart window if it vanished). */
    private fun resolveSingleCard(sel: PeriodSelection, settings: SettingsState, today: LocalDate, cards: List<PaymentMethodEntity>): ResolvedB {
        val infos = cards.map { CardCycleInfo(it.id, it.label, it.colorHex, it.last4, it.billingDay) }
        val card = infos.firstOrNull { it.id == sel.selectedCardId }
            ?: run {
                val r = PeriodResolver.resolve(
                    type = PeriodType.SMART_CYCLE,
                    range = PeriodRange.CURRENT,
                    salaryDay = settings.salaryCycleStartDay,
                    smartDay = settings.effectiveSmartResetDay,
                    today = today,
                    earliestDataDay = null,
                    customStartMillis = null,
                    customEndExclusiveMillis = null,
                    cycleOffset = sel.cycleOffset,
                )
                return ResolvedB(r.startMillis, r.endExclusiveMillis, r.label)
            }
        val period = CompositeCycleResolver.resolveSingleCard(card, settings.salaryCycleStartDay, today, sel.cycleOffset)
        return ResolvedB(period.boundingStartMillis, period.boundingEndExclusiveMillis, period.label, period)
    }

    /**
     * Smart Cycle across ALL cards, card-billing-aware. [startMillis, endExclusiveMillis) is the DISPLAY cycle;
     * [smartCard] carries the wide fetch range (previous cycle start → this cycle end, so pulled-forward card
     * spends are included) + the per-card billing days, used to bucket in memory. Reconciles with the timeline.
     */
    private fun resolveSmartCardComposite(settings: SettingsState, today: LocalDate, cards: List<PaymentMethodEntity>, cycleOffset: Int): ResolvedB {
        val reset = settings.effectiveSmartResetDay
        var cycleWin = CycleUtils.windowFor(today, reset)
        repeat(abs(cycleOffset)) {
            cycleWin = if (cycleOffset < 0) CycleUtils.previousWindow(cycleWin, reset) else CycleUtils.nextWindow(cycleWin, reset)
        }
        val prevWin = CycleUtils.previousWindow(cycleWin, reset)
        val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        val label = "${dayFmt.format(cycleWin.start)} – ${dayFmt.format(cycleWin.endInclusive)}"
        return ResolvedB(
            startMillis = cycleWin.startMillis(),
            endExclusiveMillis = cycleWin.endExclusiveMillis(),
            label = label,
            smartCard = SmartCardCtx(
                fetchStartMillis = prevWin.startMillis(),
                fetchEndExclusiveMillis = cycleWin.endExclusiveMillis(),
                resetDay = reset,
                cardBillingDays = cards.associate { it.id to it.billingDay },
            ),
        )
    }

    private fun currentCycle(): ResolvedB {
        val r = PeriodResolver.resolve(
            type = PeriodType.SALARY_CYCLE,
            range = PeriodRange.CURRENT,
            salaryDay = 1,
            smartDay = 1,
            today = LocalDate.now(DateUtils.ZONE),
            earliestDataDay = null,
            customStartMillis = null,
            customEndExclusiveMillis = null,
        )
        return ResolvedB(r.startMillis, r.endExclusiveMillis, r.label)
    }

    /** Smart Cycle composite analytics — totals, donut + weekly all from the per-instrument-filtered items. */
    private fun buildStateComposite(
        resolved: ResolvedB,
        boundingItems: List<ExpenseWithAllocations>,
        rules: List<RecurringRuleEntity>,
    ): AnalyticsUiState {
        val composite = resolved.composite!!
        val items = boundingItems.filter { composite.contains(it.expense.occurredAt, it.expense.paymentMethodId) }
        val catSpend = items.filter { it.expense.kind == TxnKind.EXPENSE }
            .flatMap { it.allocations }
            .groupBy { it.category.id }
            .map { (id, allocs) ->
                val c = allocs.first().category
                CategorySpend(categoryId = id, name = c.name, colorHex = c.colorHex, iconKey = c.iconKey, total = allocs.sumOf { it.allocation.amountMinor })
            }
            .sortedByDescending { it.total }
        val kindSums = listOf(
            KindSum(TxnKind.INCOME, items.filter { it.expense.kind == TxnKind.INCOME }.sumOf { it.expense.amountMinor }),
            KindSum(TxnKind.EXPENSE, items.filter { it.expense.kind == TxnKind.EXPENSE }.sumOf { it.expense.amountMinor }),
        )
        return buildState(resolved, catSpend, kindSums, items, rules).copy(isComposite = true)
    }

    /** Card-billing-aware Smart Cycle analytics — donut, weekly + totals from the items bucketed to this cycle. */
    private fun buildStateSmartCard(
        resolved: ResolvedB,
        fetched: List<ExpenseWithAllocations>,
        rules: List<RecurringRuleEntity>,
    ): AnalyticsUiState {
        val ctx = resolved.smartCard!!
        val items = SmartCardCycle.filterToWindow(
            items = fetched,
            windowStartMillis = resolved.startMillis,
            resetDay = ctx.resetDay,
            occurredAtOf = { it.expense.occurredAt },
            billingDayOf = { ctx.cardBillingDays[it.expense.paymentMethodId] },
        )
        val catSpend = items.filter { it.expense.kind == TxnKind.EXPENSE }
            .flatMap { it.allocations }
            .groupBy { it.category.id }
            .map { (id, allocs) ->
                val c = allocs.first().category
                CategorySpend(categoryId = id, name = c.name, colorHex = c.colorHex, iconKey = c.iconKey, total = allocs.sumOf { it.allocation.amountMinor })
            }
            .sortedByDescending { it.total }
        val kindSums = listOf(
            KindSum(TxnKind.INCOME, items.filter { it.expense.kind == TxnKind.INCOME }.sumOf { it.expense.amountMinor }),
            KindSum(TxnKind.EXPENSE, items.filter { it.expense.kind == TxnKind.EXPENSE }.sumOf { it.expense.amountMinor }),
        )
        return buildState(resolved, catSpend, kindSums, items, rules)
    }

    private fun buildState(
        resolved: ResolvedB,
        catSpend: List<CategorySpend>,
        kindSums: List<KindSum>,
        items: List<ExpenseWithAllocations>,
        rules: List<RecurringRuleEntity>,
    ): AnalyticsUiState {
        val expense = kindSums.firstOrNull { it.kind == TxnKind.EXPENSE }?.total ?: 0
        val income = kindSums.firstOrNull { it.kind == TxnKind.INCOME }?.total ?: 0

        val categorisedSpend = catSpend.sumOf { it.total }
        val spendTotal = categorisedSpend.coerceAtLeast(1)
        val slices = catSpend.map {
            CategorySlice(
                categoryId = it.categoryId,
                name = it.name,
                colorHex = it.colorHex,
                iconKey = it.iconKey,
                amountMinor = it.total,
                percent = Math.round(it.total.toDouble() / spendTotal * 100).toInt(),
            )
        }

        // Spend-over-time buckets — proportional so any range length maps onto at most 6 segments.
        val startDay = DateUtils.toLocalDate(resolved.startMillis)
        val endDay = DateUtils.toLocalDate(resolved.endExclusiveMillis)
        val days = (endDay.toEpochDay() - startDay.toEpochDay()).toInt().coerceAtLeast(1)
        val weeks = ceil(days / 7.0).toInt().coerceIn(1, 6)
        val weekly = DoubleArray(weeks)
        items.filter { it.expense.kind == TxnKind.EXPENSE }.forEach { e ->
            val dayIndex = (DateUtils.toLocalDate(e.expense.occurredAt).toEpochDay() - startDay.toEpochDay()).toInt()
            val w = ((dayIndex.toLong() * weeks) / days).toInt().coerceIn(0, weeks - 1)
            e.allocations.forEach { weekly[w] += it.allocation.amountMinor.toDouble() }
        }

        return AnalyticsUiState(
            loading = false,
            periodLabel = resolved.label,
            expenseMinor = expense,
            incomeMinor = income,
            categorisedSpendMinor = categorisedSpend,
            categories = slices,
            weekly = weekly.map { it.toFloat() },
            weekLabels = (1..weeks).map { "W$it" },
            recurring = summariseRecurring(rules),
            windowStartMillis = resolved.startMillis,
            windowEndExclusiveMillis = resolved.endExclusiveMillis,
        )
    }

    private fun summariseRecurring(rules: List<RecurringRuleEntity>): List<RecurringFreqSummary> {
        val active = rules.filter { it.active }
        return RecurrenceFreq.entries.mapNotNull { freq ->
            val inFreq = active.filter { it.frequency == freq }
            if (inFreq.isEmpty()) {
                null
            } else {
                RecurringFreqSummary(
                    frequency = freq,
                    outMinor = inFreq.filter { it.kind == TxnKind.EXPENSE }.sumOf { it.amountMinor },
                    inMinor = inFreq.filter { it.kind == TxnKind.INCOME }.sumOf { it.amountMinor },
                    count = inFreq.size,
                )
            }
        }
    }
}
