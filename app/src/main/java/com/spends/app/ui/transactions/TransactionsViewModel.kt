package com.spends.app.ui.transactions

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
import com.spends.app.core.period.ResolvedPeriod
import com.spends.app.core.time.CycleUtils
import com.spends.app.core.time.DateUtils
import kotlin.math.abs
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.ExpenseWithAllocations
import com.spends.app.data.db.entity.KindSum
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.data.settings.SettingsState
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
import com.spends.app.ui.cards.isCardInstrument
import com.spends.app.ui.components.CardChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val settingsRepository: SettingsRepository,
    private val periodSelectionStore: PeriodSelectionStore,
    private val captureRepository: com.spends.app.data.capture.SmsCaptureRepository,
    private val paymentMethodRepository: PaymentMethodRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    /**
     * A resolved period for the timeline. [composite] is null for a normal contiguous window (Month /
     * Salary / ranges); non-null for the Smart Cycle composite (Round B), where [startMillis,
     * endExclusiveMillis) is the BOUNDING range to fetch, then [CompositePeriod.contains] filters per
     * instrument. Totals/carry-forward are computed from the filtered items for a composite.
     */
    private data class ResolvedB(
        val startMillis: Long,
        val endExclusiveMillis: Long,
        val label: String,
        val composite: CompositePeriod? = null,
        // Single-Card only (#7): the SALARY cycle window whose balance (income − all expenses) is shown as
        // the headline, so a single card doesn't read as a bare negative. 0/0 = no override (use own totals).
        val headlineStart: Long = 0,
        val headlineEnd: Long = 0,
    )

    /** Categories (most-used first) for the bulk change-category picker (multi-select). */
    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeActiveByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Cycle/range is shared with Analytics (#8) so changing it on one screen syncs the other.
    private val selection = periodSelectionStore.selection
    val periodSelection: StateFlow<PeriodSelection> = selection

    // Drives the period selector's Smart pill + Single-Card picker (Round B). Confirmed cards only.
    val smartCycleEnabled: StateFlow<Boolean> =
        settingsRepository.settings.map { it.smartCycleEnabled }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    // Only CARDS can be viewed as a single instrument (their own billing cycle); a "single bank" would just
    // be the salary cycle, so banks are excluded here (#2).
    val cardChoices: StateFlow<List<CardChoice>> =
        paymentMethodRepository.observeConfirmed()
            .map { cards -> cards.filter { it.isCardInstrument() }.map { CardChoice(it.id, it.label, it.colorHex) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val searchQuery = MutableStateFlow("")

    // Resolve (type, range) into a concrete window — reacting to the salary day, the reset day, the
    // confirmed cards, and the earliest txn. Smart Cycle (all instruments) is ONE contiguous window anchored
    // on the user's reset day (default = salary day): every transaction counts in the cycle it was spent in,
    // whatever instrument paid it. (The old per-instrument composite silently dropped a card's spends from
    // the balance the moment its billing day passed — the "balance improves on billing day" bug.) Only
    // Single-Card mode still uses the composite machinery, to show that one card's own billing cycle.
    private val resolvedFlow: StateFlow<ResolvedB> =
        combine(
            selection,
            settingsRepository.settings,
            expenseRepository.observeEarliestDay(),
            paymentMethodRepository.observeConfirmed(),
        ) { sel, settings, earliest, cards ->
            val today = LocalDate.now(DateUtils.ZONE)
            if (settings.smartCycleEnabled && sel.type == PeriodType.SMART_CYCLE && sel.selectedCardId != null) {
                resolveSingleCard(sel, settings, today, cards)
            } else {
                // A stale SMART_CYCLE selection while the feature is off falls back to the salary cycle.
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
        }.stateIn(viewModelScope, SharingStarted.Eagerly, currentSalaryCycle())

    val uiState: StateFlow<TransactionsUiState> =
        combine(resolvedFlow, settingsRepository.settings, searchQuery) { resolved, settings, query ->
            Triple(resolved, settings, query)
        }.flatMapLatest { (resolved, settings, query) ->
            val anchorMillis = if (settings.carryForwardAnchorEpochDay > 0) {
                DateUtils.startOfDayMillis(LocalDate.ofEpochDay(settings.carryForwardAnchorEpochDay))
            } else {
                0L
            }
            combine(
                expenseRepository.observeBetween(resolved.startMillis, resolved.endExclusiveMillis),
                expenseRepository.observeKindSums(resolved.startMillis, resolved.endExclusiveMillis),
                expenseRepository.observeBalanceBefore(resolved.startMillis),
                expenseRepository.observeBalanceBefore(anchorMillis),
                // Single-Card headline (#7): the salary cycle's income/expense (0/0 window → empty otherwise).
                expenseRepository.observeKindSums(resolved.headlineStart, resolved.headlineEnd),
            ) { items, kindSums, carryBeforePeriod, carryBeforeAnchor, headlineKindSums ->
                if (resolved.composite != null) {
                    // Composite: kindSums/balanceBefore (over the bounding range) don't apply — totals come
                    // from the per-instrument-filtered items, and carry-forward is off for the composite.
                    buildStateComposite(resolved, settings, query, items, headlineKindSums)
                } else {
                    buildState(resolved, settings, query, items, kindSums, carryBeforePeriod, carryBeforeAnchor, anchorMillis)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    /** Single-Card mode: that one card's own billing cycle, from the selection + confirmed cards. */
    private fun resolveSingleCard(
        sel: PeriodSelection,
        settings: SettingsState,
        today: LocalDate,
        cards: List<com.spends.app.data.db.entity.PaymentMethodEntity>,
    ): ResolvedB {
        val salaryDay = settings.salaryCycleStartDay
        val infos = cards.map { CardCycleInfo(it.id, it.label, it.colorHex, it.last4, it.billingDay) }
        val card = infos.firstOrNull { it.id == sel.selectedCardId }
            // The selected card vanished (deleted / unconfirmed): fall back to the whole Smart Cycle window
            // via the contiguous resolver — mirrors the old composite fallback.
            ?: run {
                val r = PeriodResolver.resolve(
                    type = PeriodType.SMART_CYCLE,
                    range = PeriodRange.CURRENT,
                    salaryDay = salaryDay,
                    smartDay = settings.effectiveSmartResetDay,
                    today = today,
                    earliestDataDay = null,
                    customStartMillis = null,
                    customEndExclusiveMillis = null,
                    cycleOffset = sel.cycleOffset,
                )
                return ResolvedB(r.startMillis, r.endExclusiveMillis, r.label)
            }
        val period = CompositeCycleResolver.resolveSingleCard(card, salaryDay, today, sel.cycleOffset)
        // Also resolve the Smart Cycle window (same offset) — its balance is the headline so the card view
        // reflects your money left this cycle, not just the card's bare negative (#7).
        val resetDay = settings.effectiveSmartResetDay
        var cycleWin = CycleUtils.windowFor(today, resetDay)
        repeat(abs(sel.cycleOffset)) {
            cycleWin = if (sel.cycleOffset < 0) CycleUtils.previousWindow(cycleWin, resetDay) else CycleUtils.nextWindow(cycleWin, resetDay)
        }
        return ResolvedB(
            period.boundingStartMillis, period.boundingEndExclusiveMillis, period.label, period,
            headlineStart = cycleWin.startMillis(), headlineEnd = cycleWin.endExclusiveMillis(),
        )
    }

    fun applySelection(sel: PeriodSelection) = periodSelectionStore.set(sel)

    fun setSearch(value: String) = searchQuery.update { value }

    fun moveToTrash(id: Long) = viewModelScope.launch { expenseRepository.moveToTrash(id) }

    fun restore(id: Long) = viewModelScope.launch { expenseRepository.restore(id) }

    fun changeCategory(id: Long, categoryId: Long) =
        viewModelScope.launch {
            expenseRepository.reassignCategory(id, categoryId)
            // #14: correcting a captured transaction's category teaches the merchant mapping.
            captureRepository.learnFromTransaction(id, categoryId)
        }

    fun bulkMoveToTrash(ids: Set<Long>) = viewModelScope.launch { ids.forEach { expenseRepository.moveToTrash(it) } }

    fun bulkRestore(ids: Set<Long>) = viewModelScope.launch { ids.forEach { expenseRepository.restore(it) } }

    fun bulkChangeCategory(ids: Set<Long>, categoryId: Long) =
        viewModelScope.launch {
            ids.forEach {
                expenseRepository.reassignCategory(it, categoryId)
                captureRepository.learnFromTransaction(it, categoryId)
            }
        }

    private fun currentSalaryCycle(): ResolvedB {
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

    /** Single-Card mode: filter the bounding items to the card's own billing cycle, then build the list. */
    private fun buildStateComposite(
        resolved: ResolvedB,
        settings: SettingsState,
        query: String,
        boundingItems: List<ExpenseWithAllocations>,
        headlineKindSums: List<KindSum>,
    ): TransactionsUiState {
        val composite = resolved.composite!!
        val items = boundingItems.filter { composite.contains(it.expense.occurredAt, it.expense.paymentMethodId) }
        val totals = SummaryTotals(
            income = items.filter { it.expense.kind == TxnKind.INCOME }.sumOf { it.expense.amountMinor },
            expense = items.filter { it.expense.kind == TxnKind.EXPENSE }.sumOf { it.expense.amountMinor },
        )
        // Single-Card (#7): show the Smart Cycle window's remaining balance as the headline (income − all
        // expenses), so the card view reflects your money left, not the card's bare negative.
        val singleCard = resolved.headlineStart > 0L
        val headlineBalance = if (singleCard) {
            (headlineKindSums.firstOrNull { it.kind == TxnKind.INCOME }?.total ?: 0) -
                (headlineKindSums.firstOrNull { it.kind == TxnKind.EXPENSE }?.total ?: 0)
        } else {
            null
        }
        val trimmed = query.trim().lowercase()
        val searched = if (trimmed.isEmpty()) items else items.filter { it.matches(trimmed) }
        val filtered = if (settings.hideCapturedInLists) searched.filter { it.expense.source != TxnSource.SMS } else searched
        return TransactionsUiState(
            loading = false,
            window = null,
            periodLabel = resolved.label,
            canStepForward = false,
            search = query,
            totals = totals,
            carryForward = null, // carry-forward doesn't apply to a single card's statement view
            groups = buildGroups(items, filtered),
            isComposite = true,
            headlineBalanceMinor = headlineBalance,
            headlineLabel = if (singleCard) {
                if (settings.effectiveSmartResetDay == settings.salaryCycleStartDay) "Salary cycle" else "Smart Cycle"
            } else {
                null
            },
        )
    }

    /** Day-group the [filtered] rows, with per-(category,KIND) totals taken from the FULL [allItems] set. */
    private fun buildGroups(allItems: List<ExpenseWithAllocations>, filtered: List<ExpenseWithAllocations>): List<DayGroupUi> {
        val categoryTotals: Map<Pair<Long, TxnKind>, Long> = allItems
            .flatMap { ewa -> ewa.allocations.map { alloc -> Pair(alloc.category.id, ewa.expense.kind) to alloc.allocation.amountMinor } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, amounts) -> amounts.sum() }
        return filtered
            .groupBy { DateUtils.toLocalDate(it.expense.occurredAt) }
            .toSortedMap(compareByDescending { it })
            .map { (date, dayItems) ->
                val rows = dayItems
                    .sortedWith(
                        compareByDescending<ExpenseWithAllocations> { it.expense.createdAt }
                            .thenByDescending { it.expense.id },
                    )
                    .map { it.toRowUi(categoryTotals) }
                DayGroupUi(
                    date = date,
                    headerLabel = DateUtils.formatDayHeader(date),
                    expenseSubtotal = dayItems.filter { it.expense.kind == TxnKind.EXPENSE }.sumOf { it.expense.amountMinor },
                    incomeSubtotal = dayItems.filter { it.expense.kind == TxnKind.INCOME }.sumOf { it.expense.amountMinor },
                    netSubtotal = dayItems.sumOf { it.signedBalanceContribution() },
                    rows = rows,
                )
            }
    }

    private fun buildState(
        resolved: ResolvedB,
        settings: SettingsState,
        query: String,
        items: List<ExpenseWithAllocations>,
        kindSums: List<KindSum>,
        carryBeforePeriod: Long,
        carryBeforeAnchor: Long,
        anchorMillis: Long,
    ): TransactionsUiState {
        val totals = SummaryTotals(
            income = kindSums.firstOrNull { it.kind == TxnKind.INCOME }?.total ?: 0,
            expense = kindSums.firstOrNull { it.kind == TxnKind.EXPENSE }?.total ?: 0,
        )

        val trimmed = query.trim().lowercase()
        val searched = if (trimmed.isEmpty()) items else items.filter { it.matches(trimmed) }
        // #4: optionally keep SMS-captured transactions out of the timeline list (still in the totals/balance).
        val filtered = if (settings.hideCapturedInLists) searched.filter { it.expense.source != TxnSource.SMS } else searched

        // Per-(category,KIND) totals come from the FULL period set (items), groups from the filtered subset.
        val groups = buildGroups(items, filtered)

        return TransactionsUiState(
            loading = false,
            window = null,
            periodLabel = resolved.label,
            canStepForward = false,
            search = query,
            totals = totals,
            carryForward = when {
                !settings.carryForwardEnabled -> null
                // Carry-forward REQUIRES an anchor. Without one, never fold in all incomplete old
                // history (that produced the hugely-negative balance the user hit).
                anchorMillis <= 0 -> null
                // Periods that start strictly before the anchor get no carry-in (the opening balance
                // applies from the anchor onward only).
                resolved.startMillis < anchorMillis -> null
                // Opening balance as of the anchor + the net of everything from the anchor up to this
                // period's start (pre-anchor data excluded by the subtraction).
                else -> settings.carryForwardOpeningMinor + carryBeforePeriod - carryBeforeAnchor
            },
            groups = groups,
            isComposite = false,
        )
    }

    private fun ExpenseWithAllocations.matches(q: String): Boolean {
        if (expense.merchantRaw?.lowercase()?.contains(q) == true) return true
        if (expense.note?.lowercase()?.contains(q) == true) return true
        return allocations.any { it.category.name.lowercase().contains(q) }
    }

    private fun ExpenseWithAllocations.signedBalanceContribution(): Long = when (expense.kind) {
        TxnKind.INCOME -> expense.amountMinor
        TxnKind.EXPENSE -> -expense.amountMinor
    }

    private fun ExpenseWithAllocations.toRowUi(categoryTotals: Map<Pair<Long, TxnKind>, Long>): TransactionRowUi {
        val chips = allocations.map {
            CategoryChipUi(
                categoryId = it.category.id,
                name = it.category.name,
                colorHex = it.category.colorHex,
                iconKey = it.category.iconKey,
                amountMinor = it.allocation.amountMinor,
            )
        }
        val primary = chips.firstOrNull()
        // Title is ALWAYS the (primary) category name now (#2). Merchant is searchable (see matches) and
        // visible in the editor, but no longer the row title.
        val title = primary?.name ?: "Uncategorized"
        return TransactionRowUi(
            id = expense.id,
            amountMinor = expense.amountMinor,
            kind = expense.kind,
            title = title,
            note = expense.note,
            categoryTotalMinor = primary?.let { categoryTotals[it.categoryId to expense.kind] } ?: expense.amountMinor,
            // Recurring items carry a synthetic start-of-day time — suppress it (would read "12:00 AM").
            timeLabel = if (expense.source == TxnSource.RECURRING) null else DateUtils.formatTime(expense.occurredAt),
            source = expense.source,
            categories = chips,
        )
    }
}
