package com.spends.app.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.period.PeriodRange
import com.spends.app.core.period.PeriodResolver
import com.spends.app.core.period.PeriodSelection
import com.spends.app.core.period.PeriodType
import com.spends.app.core.period.ResolvedPeriod
import com.spends.app.core.period.SmartCycleDetector
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.CategorySpend
import com.spends.app.data.db.entity.ExpenseWithAllocations
import com.spends.app.data.db.entity.KindSum
import com.spends.app.data.db.entity.RecurringRuleEntity
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.RecurringRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.RecurrenceFreq
import com.spends.app.domain.model.TxnKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    val transferMinor: Long = 0,
    // Spend actually categorised in the breakdown — reconciles the donut centre with its wedges/legend.
    val categorisedSpendMinor: Long = 0,
    val categories: List<CategorySlice> = emptyList(),
    val weekly: List<Float> = emptyList(),
    val weekLabels: List<String> = emptyList(),
    val recurring: List<RecurringFreqSummary> = emptyList(),
    // The selected period's epoch-millis bounds, so tapping a category drills into that exact window.
    val windowStartMillis: Long = 0,
    val windowEndExclusiveMillis: Long = 0,
) {
    val netMinor: Long get() = incomeMinor - expenseMinor
    val isEmpty: Boolean get() = !loading && expenseMinor == 0L && incomeMinor == 0L
}

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val recurringRepository: RecurringRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val selection = MutableStateFlow(PeriodSelection(type = PeriodType.SALARY_CYCLE, range = PeriodRange.CURRENT))
    val periodSelection: StateFlow<PeriodSelection> = selection

    private val resolvedFlow: StateFlow<ResolvedPeriod> =
        combine(
            selection,
            settingsRepository.settings,
            expenseRepository.observeIncomeOccurredAt(),
            expenseRepository.observeEarliestDay(),
        ) { sel, settings, income, earliest ->
            val smartDay = SmartCycleDetector.detectSalaryDay(income, settings.salaryCycleStartDay)
            PeriodResolver.resolve(
                type = sel.type,
                range = sel.range,
                salaryDay = settings.salaryCycleStartDay,
                smartDay = smartDay,
                today = LocalDate.now(DateUtils.ZONE),
                earliestDataDay = earliest?.let { DateUtils.toLocalDate(it) },
                customStartMillis = sel.customStartMillis,
                customEndExclusiveMillis = sel.customEndExclusiveMillis,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, currentCycle())

    val state: StateFlow<AnalyticsUiState> =
        resolvedFlow.flatMapLatest { resolved ->
            combine(
                expenseRepository.observeCategorySpend(resolved.startMillis, resolved.endExclusiveMillis),
                expenseRepository.observeKindSums(resolved.startMillis, resolved.endExclusiveMillis),
                expenseRepository.observeBetween(resolved.startMillis, resolved.endExclusiveMillis),
                recurringRepository.observeAll(),
            ) { catSpend, kindSums, items, rules ->
                buildState(resolved, catSpend, kindSums, items, rules)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

    fun applySelection(sel: PeriodSelection) = selection.update { sel }

    private fun currentCycle(): ResolvedPeriod = PeriodResolver.resolve(
        type = PeriodType.SALARY_CYCLE,
        range = PeriodRange.CURRENT,
        salaryDay = 1,
        smartDay = 1,
        today = LocalDate.now(DateUtils.ZONE),
        earliestDataDay = null,
        customStartMillis = null,
        customEndExclusiveMillis = null,
    )

    private fun buildState(
        resolved: ResolvedPeriod,
        catSpend: List<CategorySpend>,
        kindSums: List<KindSum>,
        items: List<ExpenseWithAllocations>,
        rules: List<RecurringRuleEntity>,
    ): AnalyticsUiState {
        val expense = kindSums.firstOrNull { it.kind == TxnKind.EXPENSE }?.total ?: 0
        val income = kindSums.firstOrNull { it.kind == TxnKind.INCOME }?.total ?: 0
        val transfer = kindSums.firstOrNull { it.kind == TxnKind.TRANSFER }?.total ?: 0

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
            transferMinor = transfer,
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
