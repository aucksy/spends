package com.spends.app.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.period.PeriodRange
import com.spends.app.core.period.PeriodResolver
import com.spends.app.core.period.PeriodSelection
import com.spends.app.core.period.PeriodType
import com.spends.app.core.period.ResolvedPeriod
import com.spends.app.core.period.SmartCycleDetector
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.ExpenseWithAllocations
import com.spends.app.data.db.entity.KindSum
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.data.settings.SettingsState
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
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

    private val selection = MutableStateFlow(PeriodSelection())
    val periodSelection: StateFlow<PeriodSelection> = selection

    private val searchQuery = MutableStateFlow("")

    // Resolve (type, range) into a concrete window, reacting to salary-day, the auto-detected smart day,
    // and the earliest transaction (for "All").
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
            ) { items, kindSums, carryBeforePeriod, carryBeforeAnchor ->
                buildState(resolved, settings, query, items, kindSums, carryBeforePeriod, carryBeforeAnchor, anchorMillis)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    fun applySelection(sel: PeriodSelection) = selection.update { sel }

    fun setSearch(value: String) = searchQuery.update { value }

    fun moveToTrash(id: Long) = viewModelScope.launch { expenseRepository.moveToTrash(id) }

    fun restore(id: Long) = viewModelScope.launch { expenseRepository.restore(id) }

    fun changeCategory(id: Long, categoryId: Long) =
        viewModelScope.launch { expenseRepository.reassignCategory(id, categoryId) }

    fun bulkMoveToTrash(ids: Set<Long>) = viewModelScope.launch { ids.forEach { expenseRepository.moveToTrash(it) } }

    fun bulkRestore(ids: Set<Long>) = viewModelScope.launch { ids.forEach { expenseRepository.restore(it) } }

    fun bulkChangeCategory(ids: Set<Long>, categoryId: Long) =
        viewModelScope.launch { ids.forEach { expenseRepository.reassignCategory(it, categoryId) } }

    private fun currentSalaryCycle(): ResolvedPeriod = PeriodResolver.resolve(
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
            transfer = kindSums.firstOrNull { it.kind == TxnKind.TRANSFER }?.total ?: 0,
        )

        val trimmed = query.trim().lowercase()
        val searched = if (trimmed.isEmpty()) items else items.filter { it.matches(trimmed) }
        // #4: optionally keep SMS-captured transactions out of the timeline list (still in the totals/balance).
        val filtered = if (settings.hideCapturedInLists) searched.filter { it.expense.source != TxnSource.SMS } else searched

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
            window = null,
            periodLabel = resolved.label,
            canStepForward = false,
            search = query,
            totals = totals,
            carryForward = when {
                !settings.carryForwardEnabled -> null
                // Suppress only for periods that start STRICTLY before the anchor; a period starting on
                // the anchor carries in the opening balance (opening + 0).
                anchorMillis > 0 && resolved.startMillis < anchorMillis -> null
                else -> settings.carryForwardOpeningMinor + carryBeforePeriod - carryBeforeAnchor
            },
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
            source = expense.source,
            categories = chips,
        )
    }
}
