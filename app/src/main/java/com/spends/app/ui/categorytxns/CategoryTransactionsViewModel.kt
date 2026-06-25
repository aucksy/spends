package com.spends.app.ui.categorytxns

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.ExpenseWithAllocations
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

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
    val totalMinor: Long = 0,
    val count: Int = 0,
    val rows: List<CategoryTxnRow> = emptyList(),
)

@HiltViewModel
class CategoryTransactionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    expenseRepository: ExpenseRepository,
) : ViewModel() {

    private val categoryId: Long = savedStateHandle[Routes.ARG_CATEGORY_ID] ?: -1L
    private val categoryName: String = savedStateHandle[Routes.ARG_CATEGORY_NAME] ?: ""
    private val startMillis: Long = savedStateHandle[Routes.ARG_PERIOD_START] ?: 0L
    private val endExclusiveMillis: Long = savedStateHandle[Routes.ARG_PERIOD_END] ?: 0L

    val state: StateFlow<CategoryTxnsUiState> =
        expenseRepository.observeByCategoryBetween(categoryId, startMillis, endExclusiveMillis)
            .map { items ->
                val rows = items.map { it.toRow() }
                CategoryTxnsUiState(
                    loading = false,
                    categoryName = categoryName,
                    totalMinor = rows.sumOf { it.amountMinor },
                    count = rows.size,
                    rows = rows,
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                CategoryTxnsUiState(categoryName = categoryName),
            )

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
