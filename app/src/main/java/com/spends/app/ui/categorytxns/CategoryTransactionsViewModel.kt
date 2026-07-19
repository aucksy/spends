package com.spends.app.ui.categorytxns

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.period.PeriodResolver
import com.spends.app.core.period.PeriodSelection
import com.spends.app.core.period.PeriodSelectionStore
import com.spends.app.core.period.PeriodType
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.ExpenseWithAllocations
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import javax.inject.Inject

/** Trailing window the monthly-average metric is computed over (#8). null months = all time. */
enum class AvgWindow(val label: String, val monthsBack: Long?) {
    M3("3M", 3), M6("6M", 6), ALL("All", null)
}

/** One transaction row in the per-category drill-down list. */
data class CategoryTxnRow(
    val id: Long,
    val title: String,
    val note: String?,
    val dateLabel: String,
    val timeLabel: String,
    val kind: TxnKind,
    val iconKey: String,
    val colorHex: String,
    /** The amount actually allocated to *this* category on the transaction (handles splits). */
    val amountMinor: Long,
)

data class CategoryTxnsUiState(
    val loading: Boolean = true,
    val categoryName: String = "",
    // The concrete date range of the selected cycle (#5) — shown by the selector pill as its secondary line.
    val cycleLabel: String = "",
    val totalMinor: Long = 0,
    val count: Int = 0,
    // Average spend per month over the chosen trailing window (#8: Last 3M / 6M / All), independent of the
    // selected cycle. total-in-window ÷ months-in-window (capped at the category's own data span).
    val monthlyAverageMinor: Long = 0,
    val avgWindow: AvgWindow = AvgWindow.M6,
    val rows: List<CategoryTxnRow> = emptyList(),
)

@HiltViewModel
class CategoryTransactionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    expenseRepository: ExpenseRepository,
    settingsRepository: SettingsRepository,
    periodSelectionStore: PeriodSelectionStore,
) : ViewModel() {

    private val categoryId: Long = savedStateHandle[Routes.ARG_CATEGORY_ID] ?: -1L
    private val categoryName: String = savedStateHandle[Routes.ARG_CATEGORY_NAME] ?: ""

    private val avgWindow = MutableStateFlow(AvgWindow.M6)
    fun setAvgWindow(window: AvgWindow) = avgWindow.update { window }

    // A LOCAL cycle selector (#5), independent of the shared Transactions/Analytics cycle (user's choice):
    // changing it here NEVER writes back to the shared store. It's SEEDED from the cycle you were viewing so
    // the drill-down matches the number you tapped in Analytics (no mismatch). It's shown as a compact
    // single-line control: for a single current cycle (the usual case) it's a ‹ › prev/next stepper; for
    // All-time / Last-N / Custom it's a tappable name (those ranges have no prev/next). Tapping the name opens
    // the full picker either way. Smart Cycle is a whole-portfolio composite that doesn't map onto a single
    // category, so it falls back to the salary cycle here.
    private val period = MutableStateFlow(
        periodSelectionStore.selection.value.let { s ->
            if (s.type == PeriodType.SMART_CYCLE) {
                s.copy(type = PeriodType.SALARY_CYCLE, selectedCardId = null, cycleOffset = 0)
            } else {
                s
            }
        },
    )
    val periodSelection: StateFlow<PeriodSelection> = period.asStateFlow()
    fun setPeriod(selection: PeriodSelection) = period.update { selection }

    val state: StateFlow<CategoryTxnsUiState> =
        // Query the category's WHOLE history once, then slice in memory: the list/total show the selected
        // cycle, while the monthly average is computed over the chosen trailing window (#8).
        combine(
            expenseRepository.observeByCategoryBetween(categoryId, 0L, Long.MAX_VALUE),
            avgWindow,
            period,
            settingsRepository.settings,
            expenseRepository.observeEarliestDay(),
        ) { allItems, window, sel, settings, earliest ->
            val now = DateUtils.nowMillis()
            val today = LocalDate.now(DateUtils.ZONE)
            // Resolve the selected cycle to a concrete [start, end) window, the same way Analytics does
            // (Smart already coerced to salary above, so the naive smartDay is never used).
            val effType = if (sel.type == PeriodType.SMART_CYCLE) PeriodType.SALARY_CYCLE else sel.type
            val resolved = PeriodResolver.resolve(
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
            val periodItems = allItems.filter {
                it.expense.occurredAt >= resolved.startMillis && it.expense.occurredAt < resolved.endExclusiveMillis
            }
            val rows = periodItems.map { it.toRow() }
            val total = rows.sumOf { it.amountMinor }

            // The trailing window's spend on THIS category ÷ its months. The denominator is capped at the
            // category's own first transaction, so a young category isn't divided by the full 3/6 months.
            val windowStart = window.monthsBack?.let {
                DateUtils.startOfDayMillis(LocalDate.now(DateUtils.ZONE).minusMonths(it))
            } ?: 0L
            val windowItems = allItems.filter { it.expense.occurredAt in windowStart until now }
            val windowTotal = windowItems.sumOf { it.allocatedToCategory() }
            val earliestAll = allItems.minOfOrNull { it.expense.occurredAt }
            val effectiveStart = maxOf(windowStart, earliestAll ?: now)
            val months = if (now <= effectiveStart) 1.0 else ((now - effectiveStart).toDouble() / 86_400_000.0 / 30.44).coerceAtLeast(1.0)

            CategoryTxnsUiState(
                loading = false,
                categoryName = categoryName,
                cycleLabel = resolved.label,
                totalMinor = total,
                count = rows.size,
                monthlyAverageMinor = (windowTotal / months).toLong(),
                avgWindow = window,
                rows = rows,
            )
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                CategoryTxnsUiState(categoryName = categoryName),
            )

    /** The amount allocated to THIS category on a transaction (handles splits). */
    private fun ExpenseWithAllocations.allocatedToCategory(): Long =
        allocations.filter { it.allocation.categoryId == categoryId }.sumOf { it.allocation.amountMinor }

    private fun ExpenseWithAllocations.toRow(): CategoryTxnRow {
        // The amount belonging to this category specifically (a transaction may split across many).
        val allocated = allocations
            .filter { it.allocation.categoryId == categoryId }
            .sumOf { it.allocation.amountMinor }
        val thisCat = allocations.firstOrNull { it.allocation.categoryId == categoryId }?.category
        val title = expense.merchantRaw?.takeIf { it.isNotBlank() }
            ?: thisCat?.name
            ?: expense.note?.takeIf { it.isNotBlank() }
            ?: "Transaction"
        return CategoryTxnRow(
            id = expense.id,
            title = title,
            note = expense.note,
            dateLabel = DateUtils.formatDay(expense.occurredAt),
            timeLabel = DateUtils.formatTime(expense.occurredAt),
            kind = expense.kind,
            iconKey = thisCat?.iconKey ?: "tag",
            colorHex = thisCat?.colorHex ?: "#78716C",
            amountMinor = allocated,
        )
    }
}
