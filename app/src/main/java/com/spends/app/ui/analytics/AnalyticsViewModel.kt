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
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.CategorySpend
import com.spends.app.data.db.entity.ExpenseWithAllocations
import com.spends.app.data.db.entity.KindSum
import com.spends.app.data.db.entity.PaymentMethodEntity
import com.spends.app.data.db.entity.RecurringRuleEntity
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.repo.RecurringRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.RecurrenceFreq
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.cards.isCardInstrument
import com.spends.app.ui.components.CardChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject
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

/** A resolved Analytics period — [composite] is non-null for the Smart Cycle composite (Round B). */
private data class ResolvedB(
    val startMillis: Long,
    val endExclusiveMillis: Long,
    val label: String,
    val composite: CompositePeriod? = null,
)

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val recurringRepository: RecurringRepository,
    private val settingsRepository: SettingsRepository,
    private val periodSelectionStore: PeriodSelectionStore,
    private val paymentMethodRepository: PaymentMethodRepository,
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
            if (settings.smartCycleEnabled && sel.type == PeriodType.SMART_CYCLE) {
                resolveComposite(sel, settings.salaryCycleStartDay, today, cards)
            } else {
                val effType = if (sel.type == PeriodType.SMART_CYCLE) PeriodType.SALARY_CYCLE else sel.type
                val r = PeriodResolver.resolve(
                    type = effType,
                    range = sel.range,
                    salaryDay = settings.salaryCycleStartDay,
                    smartDay = settings.salaryCycleStartDay,
                    today = today,
                    earliestDataDay = earliest?.let { DateUtils.toLocalDate(it) },
                    customStartMillis = sel.customStartMillis,
                    customEndExclusiveMillis = sel.customEndExclusiveMillis,
                    cycleOffset = sel.cycleOffset,
                )
                ResolvedB(r.startMillis, r.endExclusiveMillis, r.label)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, currentCycle())

    val state: StateFlow<AnalyticsUiState> =
        resolvedFlow.flatMapLatest { resolved ->
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

    fun applySelection(sel: PeriodSelection) = periodSelectionStore.set(sel)

    private fun resolveComposite(sel: PeriodSelection, salaryDay: Int, today: LocalDate, cards: List<PaymentMethodEntity>): ResolvedB {
        val infos = cards.map { CardCycleInfo(it.id, it.label, it.colorHex, it.last4, it.billingDay) }
        val card = sel.selectedCardId?.let { id -> infos.firstOrNull { it.id == id } }
        val period = if (card != null) {
            CompositeCycleResolver.resolveSingleCard(card, salaryDay, today, sel.cycleOffset)
        } else {
            CompositeCycleResolver.resolveSmartCycle(infos, salaryDay, today, sel.cycleOffset)
        }
        return ResolvedB(period.boundingStartMillis, period.boundingEndExclusiveMillis, period.label, period)
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
