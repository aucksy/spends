package com.spends.app.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.time.CycleUtils
import com.spends.app.core.time.CycleWindow
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.ExpenseWithAllocations
import com.spends.app.data.db.entity.KindSum
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.data.settings.SettingsState
import com.spends.app.domain.model.TxnKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val settingsRepository: SettingsRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    /** Categories (most-used first) for the quick swipe-to-change-category picker. */
    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeActiveByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val rangeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val rangeFormatterWithYear = DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH)

    private val periodOffset = MutableStateFlow(0)
    private val searchQuery = MutableStateFlow("")

    private val windowFlow: StateFlow<CycleWindow> =
        combine(settingsRepository.settings, periodOffset) { settings, offset ->
            computeWindow(settings.salaryCycleStartDay, offset)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, computeWindow(1, 0))

    val uiState: StateFlow<TransactionsUiState> =
        combine(windowFlow, settingsRepository.settings, searchQuery) { window, settings, query ->
            Triple(window, settings, query)
        }.flatMapLatest { (window, settings, query) ->
            combine(
                expenseRepository.observeBetween(window),
                expenseRepository.observeKindSums(window),
                expenseRepository.observeCarryForwardInto(window),
            ) { items, kindSums, carryBefore ->
                buildState(window, settings, query, items, kindSums, carryBefore)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    fun stepPrevious() = periodOffset.update { it - 1 }

    fun stepNext() = periodOffset.update { if (it < 0) it + 1 else it }

    fun setSearch(value: String) = searchQuery.update { value }

    fun moveToTrash(id: Long) = viewModelScope.launch { expenseRepository.moveToTrash(id) }

    fun restore(id: Long) = viewModelScope.launch { expenseRepository.restore(id) }

    fun changeCategory(id: Long, categoryId: Long) =
        viewModelScope.launch { expenseRepository.reassignCategory(id, categoryId) }

    private fun computeWindow(salaryDay: Int, offset: Int): CycleWindow {
        var window = CycleUtils.windowFor(LocalDate.now(DateUtils.ZONE), salaryDay)
        var remaining = offset
        while (remaining < 0) {
            window = CycleUtils.previousWindow(window, salaryDay); remaining++
        }
        while (remaining > 0) {
            window = CycleUtils.nextWindow(window, salaryDay); remaining--
        }
        return window
    }

    private fun buildState(
        window: CycleWindow,
        settings: SettingsState,
        query: String,
        items: List<ExpenseWithAllocations>,
        kindSums: List<KindSum>,
        carryBefore: Long,
    ): TransactionsUiState {
        val totals = SummaryTotals(
            income = kindSums.firstOrNull { it.kind == TxnKind.INCOME }?.total ?: 0,
            expense = kindSums.firstOrNull { it.kind == TxnKind.EXPENSE }?.total ?: 0,
            transfer = kindSums.firstOrNull { it.kind == TxnKind.TRANSFER }?.total ?: 0,
        )

        val trimmed = query.trim().lowercase()
        val filtered = if (trimmed.isEmpty()) items else items.filter { it.matches(trimmed) }

        val groups = filtered
            .groupBy { DateUtils.toLocalDate(it.expense.occurredAt) }
            .toSortedMap(compareByDescending { it })
            .map { (date, dayItems) ->
                val rows = dayItems
                    .sortedByDescending { it.expense.occurredAt }
                    .map { it.toRowUi() }
                DayGroupUi(
                    date = date,
                    headerLabel = DateUtils.formatDayHeader(date),
                    netSubtotal = dayItems.sumOf { it.signedBalanceContribution() },
                    rows = rows,
                )
            }

        return TransactionsUiState(
            loading = false,
            window = window,
            periodLabel = formatRange(window),
            canStepForward = periodOffset.value < 0,
            search = query,
            totals = totals,
            carryForward = if (settings.carryForwardEnabled) carryBefore else null,
            groups = groups,
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
        TxnKind.TRANSFER -> 0
    }

    private fun ExpenseWithAllocations.toRowUi(): TransactionRowUi {
        val chips = allocations.map {
            CategoryChipUi(
                categoryId = it.category.id,
                name = it.category.name,
                colorHex = it.category.colorHex,
                iconKey = it.category.iconKey,
                amountMinor = it.allocation.amountMinor,
            )
        }
        val title = expense.merchantRaw?.takeIf { it.isNotBlank() }
            ?: chips.firstOrNull()?.name
            ?: expense.note?.takeIf { it.isNotBlank() }
            ?: "Transaction"
        return TransactionRowUi(
            id = expense.id,
            amountMinor = expense.amountMinor,
            kind = expense.kind,
            title = title,
            note = expense.note,
            timeLabel = DateUtils.formatTime(expense.occurredAt),
            categories = chips,
        )
    }

    private fun formatRange(window: CycleWindow): String {
        val sameYear = window.start.year == window.endInclusive.year
        val startFmt = if (sameYear) rangeFormatter else rangeFormatterWithYear
        return "${startFmt.format(window.start)} – ${rangeFormatterWithYear.format(window.endInclusive)}"
    }
}
