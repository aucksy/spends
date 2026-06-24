package com.spends.app.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.time.CycleUtils
import com.spends.app.core.time.CycleWindow
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.ExpenseWithAllocations
import com.spends.app.data.db.entity.KindSum
import com.spends.app.data.db.entity.CategorySpend
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
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.ceil

enum class PeriodMode { SALARY_CYCLE, CALENDAR_MONTH }

/** Totals of active recurring rules in one frequency bucket (cycle-independent). */
data class RecurringFreqSummary(
    val frequency: RecurrenceFreq,
    val outMinor: Long,
    val inMinor: Long,
    val count: Int,
)

/** One category wedge of the donut + legend. */
data class CategorySlice(
    val name: String,
    val colorHex: String,
    val iconKey: String,
    val amountMinor: Long,
    val percent: Int,
)

data class AnalyticsUiState(
    val loading: Boolean = true,
    val periodMode: PeriodMode = PeriodMode.SALARY_CYCLE,
    val periodLabel: String = "",
    val canStepForward: Boolean = false,
    val expenseMinor: Long = 0,
    val incomeMinor: Long = 0,
    val transferMinor: Long = 0,
    // Spend that is actually categorised in the breakdown (excludes investments/EMI etc.), so the
    // donut centre reconciles with its wedges + legend. expenseMinor stays the gross figure.
    val categorisedSpendMinor: Long = 0,
    val categories: List<CategorySlice> = emptyList(),
    val weekly: List<Float> = emptyList(),
    val weekLabels: List<String> = emptyList(),
    val recurring: List<RecurringFreqSummary> = emptyList(),
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

    private val rangeFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val rangeFmtYear = DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH)
    private val monthFmt = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

    private val periodMode = MutableStateFlow(PeriodMode.SALARY_CYCLE)
    private val offset = MutableStateFlow(0)

    val state: StateFlow<AnalyticsUiState> =
        combine(settingsRepository.settings, periodMode, offset) { settings, mode, off ->
            Triple(computeWindow(mode, settings.salaryCycleStartDay, off), mode, off)
        }.flatMapLatest { (window, mode, off) ->
            combine(
                expenseRepository.observeCategorySpend(window),
                expenseRepository.observeKindSums(window),
                expenseRepository.observeBetween(window),
                recurringRepository.observeAll(),
            ) { catSpend, kindSums, items, rules ->
                buildState(window, mode, off, catSpend, kindSums, items, rules)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

    fun setMode(mode: PeriodMode) {
        if (mode != periodMode.value) { periodMode.value = mode; offset.value = 0 }
    }

    fun stepPrevious() = offset.update { it - 1 }
    fun stepNext() = offset.update { if (it < 0) it + 1 else it }

    private fun computeWindow(mode: PeriodMode, salaryDay: Int, off: Int): CycleWindow {
        val today = LocalDate.now(DateUtils.ZONE)
        return when (mode) {
            PeriodMode.SALARY_CYCLE -> {
                var window = CycleUtils.windowFor(today, salaryDay)
                var remaining = off
                while (remaining < 0) { window = CycleUtils.previousWindow(window, salaryDay); remaining++ }
                while (remaining > 0) { window = CycleUtils.nextWindow(window, salaryDay); remaining-- }
                window
            }
            PeriodMode.CALENDAR_MONTH -> CycleUtils.calendarMonth(YearMonth.from(today).plusMonths(off.toLong()))
        }
    }

    private fun buildState(
        window: CycleWindow,
        mode: PeriodMode,
        off: Int,
        catSpend: List<CategorySpend>,
        kindSums: List<KindSum>,
        items: List<ExpenseWithAllocations>,
        rules: List<RecurringRuleEntity>,
    ): AnalyticsUiState {
        val expense = kindSums.firstOrNull { it.kind == TxnKind.EXPENSE }?.total ?: 0
        val income = kindSums.firstOrNull { it.kind == TxnKind.INCOME }?.total ?: 0
        val transfer = kindSums.firstOrNull { it.kind == TxnKind.TRANSFER }?.total ?: 0

        val categorisedSpend = catSpend.sumOf { it.total }
        val spendTotal = categorisedSpend.coerceAtLeast(1) // divide-by-zero guard for percentages only
        val slices = catSpend.map {
            CategorySlice(
                name = it.name,
                colorHex = it.colorHex,
                iconKey = it.iconKey,
                amountMinor = it.total,
                percent = Math.round(it.total.toDouble() / spendTotal * 100).toInt(),
            )
        }

        // Weekly spend buckets (expenses only, excluding non-consumption — same set as the donut).
        val days = (window.endExclusive.toEpochDay() - window.start.toEpochDay()).toInt().coerceAtLeast(1)
        val weeks = ceil(days / 7.0).toInt().coerceIn(1, 6)
        val weekly = DoubleArray(weeks)
        items.filter { it.expense.kind == TxnKind.EXPENSE }.forEach { e ->
            val dayIndex = (DateUtils.toLocalDate(e.expense.occurredAt).toEpochDay() - window.start.toEpochDay()).toInt()
            val w = (dayIndex / 7).coerceIn(0, weeks - 1)
            e.allocations.filter { !it.category.excludeFromSpend }.forEach { weekly[w] += it.allocation.amountMinor.toDouble() }
        }

        return AnalyticsUiState(
            loading = false,
            periodMode = mode,
            periodLabel = formatPeriod(window, mode),
            canStepForward = off < 0,
            expenseMinor = expense,
            incomeMinor = income,
            transferMinor = transfer,
            categorisedSpendMinor = categorisedSpend,
            categories = slices,
            weekly = weekly.map { it.toFloat() },
            weekLabels = (1..weeks).map { "W$it" },
            recurring = summariseRecurring(rules),
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

    private fun formatPeriod(window: CycleWindow, mode: PeriodMode): String = when (mode) {
        PeriodMode.CALENDAR_MONTH -> monthFmt.format(window.start)
        PeriodMode.SALARY_CYCLE -> {
            val sameYear = window.start.year == window.endInclusive.year
            val startFmt = if (sameYear) rangeFmt else rangeFmtYear
            "${startFmt.format(window.start)} – ${rangeFmtYear.format(window.endInclusive)}"
        }
    }
}
