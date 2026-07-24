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
import com.spends.app.core.time.DateUtils
import com.spends.app.data.ai.AiInsights
import com.spends.app.data.ai.GroqClient
import com.spends.app.data.ai.InsightPayload
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
 *  present for an empty cycle. [failed] = the call didn't return usable text (fail-closed → the card hides). */
data class InsightsUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val text: String? = null,
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

    init {
        viewModelScope.launch {
            // Gate = master + sub-toggle + a key, all REACTIVE. When OFF, flatMapLatest yields flowOf(null) and we
            // never subscribe to `state`, so the analytics DB flows stay idle for AI-off users (no battery cost).
            combine(
                settingsRepository.settings.map { it.aiEnabled && it.aiInsights }.distinctUntilChanged(),
                groqClient.hasKeyFlow,
            ) { on, hasKey -> on && hasKey }
                .distinctUntilChanged()
                .flatMapLatest { gateOn ->
                    if (!gateOn) {
                        flowOf<String?>(null)
                    } else {
                        // ONLY the fingerprint drives re-summarising. Unrelated churn (a recurring edit, an
                        // unrelated DataStore write) keeps the fingerprint the same → distinctUntilChanged drops
                        // it → the in-flight call is NOT cancelled and completes (fixes the "stuck on Thinking…"
                        // hang). null = an empty/loading cycle → hide the card.
                        state.map { st -> if (st.loading || st.isEmpty) null else insightFingerprint(st) }
                            .distinctUntilChanged()
                    }
                }
                .collectLatest { fp ->
                    if (fp == null) {
                        if (_insights.value.visible) _insights.value = InsightsUiState(visible = false)
                        return@collectLatest
                    }
                    _insights.value = InsightsUiState(visible = true, loading = true)
                    val text = aiInsights.summarize(buildInsightPayload(state.value))
                    _insights.value = InsightsUiState(visible = true, loading = false, text = text, failed = text == null)
                }
        }
    }

    /** Re-generate the insight bypassing the per-cycle cache (the card's refresh button). */
    fun refreshInsights() = viewModelScope.launch {
        val st = state.value
        val s = settingsRepository.settings.first()
        if (!(s.aiEnabled && s.aiInsights) || !groqClient.hasKey() || st.loading || st.isEmpty) return@launch
        _insights.value = InsightsUiState(visible = true, loading = true)
        val text = aiInsights.summarize(buildInsightPayload(st), forceRefresh = true)
        _insights.value = InsightsUiState(visible = true, loading = false, text = text, failed = text == null)
    }

    private fun insightFingerprint(st: AnalyticsUiState): String = buildString {
        append(st.windowStartMillis).append('|').append(st.windowEndExclusiveMillis)
        append('|').append(st.incomeMinor).append('|').append(st.expenseMinor)
        st.categories.forEach { append('|').append(it.categoryId).append(':').append(it.amountMinor) }
    }

    /**
     * Build the aggregates-only insight payload from the reconciled on-screen [st] (so the card's ₹ figures match
     * the charts) plus a one-shot previous-cycle read. Sends NO dates, NO rows, NO merchants, NO balances — the
     * cycle label is the descriptive name (e.g. "Current Salary Cycle"), never concrete transaction dates.
     */
    private suspend fun buildInsightPayload(st: AnalyticsUiState): InsightPayload {
        val sel = selection.value
        val byCategory = st.categories.map { InsightPayload.CategoryTotal(it.name, it.amountMinor) }
        // "vs last cycle" only for a single navigable cycle; the previous window is the equal-length span
        // immediately before this one (a universal approximation across Month / Salary / Smart).
        var lastExpense: Long? = null
        var lastByCategory: List<InsightPayload.CategoryTotal>? = null
        if (sel.range == PeriodRange.CURRENT && st.windowEndExclusiveMillis > st.windowStartMillis) {
            val span = st.windowEndExclusiveMillis - st.windowStartMillis
            val prevStart = st.windowStartMillis - span
            val prevEnd = st.windowStartMillis
            lastExpense = expenseRepository.kindSumsOnce(prevStart, prevEnd)
                .firstOrNull { it.kind == TxnKind.EXPENSE }?.total ?: 0L
            lastByCategory = expenseRepository.categorySpendOnce(prevStart, prevEnd)
                .map { InsightPayload.CategoryTotal(it.name, it.total) }
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
