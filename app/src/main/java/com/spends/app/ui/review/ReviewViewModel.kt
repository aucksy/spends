package com.spends.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.ExpenseWithAllocations
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.domain.model.TxnKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewRowUi(
    val id: Long,
    val amountMinor: Long,
    val kind: TxnKind,
    val title: String,
    val subtitle: String,
    val categoryId: Long?,
    val categoryName: String?,
    val iconKey: String?,
    val colorHex: String?,
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    val items: StateFlow<List<ReviewRowUi>> = expenseRepository.observeNeedsReview()
        .map { list -> list.map { it.toRow() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeActiveByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun confirm(id: Long) = viewModelScope.launch { expenseRepository.markReviewed(id) }

    fun changeCategoryAndConfirm(id: Long, categoryId: Long) = viewModelScope.launch {
        expenseRepository.reassignCategory(id, categoryId)
        expenseRepository.markReviewed(id)
    }

    private fun ExpenseWithAllocations.toRow(): ReviewRowUi {
        val primary = allocations.firstOrNull()
        val title = expense.merchantRaw?.takeIf { it.isNotBlank() }
            ?: primary?.category?.name
            ?: "Captured transaction"
        val subtitle = listOfNotNull(expense.note?.takeIf { it.isNotBlank() }, DateUtils.formatDay(expense.occurredAt))
            .joinToString(" · ")
        return ReviewRowUi(
            id = expense.id,
            amountMinor = expense.amountMinor,
            kind = expense.kind,
            title = title,
            subtitle = subtitle,
            categoryId = primary?.category?.id,
            categoryName = primary?.category?.name,
            iconKey = primary?.category?.iconKey,
            colorHex = primary?.category?.colorHex,
        )
    }
}
