package com.spends.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.money.Money
import com.spends.app.core.time.DateUtils
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.domain.model.TxnKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrashRowUi(
    val id: Long,
    val title: String,
    val amountLabel: String,
    val kind: TxnKind,
    val iconKey: String,
    val colorHex: String,
    val deletedLabel: String,
)

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
) : ViewModel() {

    val items: StateFlow<List<TrashRowUi>> = expenseRepository.observeTrashed()
        .map { list ->
            list.map { e ->
                val cat = e.allocations.firstOrNull()?.category
                val prefix = when (e.expense.kind) {
                    TxnKind.INCOME -> "+"
                    TxnKind.EXPENSE -> "-"
                    TxnKind.TRANSFER -> ""
                }
                TrashRowUi(
                    id = e.expense.id,
                    title = e.expense.merchantRaw?.takeIf { it.isNotBlank() } ?: cat?.name ?: "Transaction",
                    amountLabel = prefix + Money.formatRupees(e.expense.amountMinor),
                    kind = e.expense.kind,
                    iconKey = cat?.iconKey ?: "tag",
                    colorHex = cat?.colorHex ?: "#78716C",
                    deletedLabel = e.expense.deletedAt?.let { "Deleted ${DateUtils.formatDay(it)}" } ?: "",
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(id: Long) = viewModelScope.launch { expenseRepository.restore(id) }

    fun deleteForever(id: Long) = viewModelScope.launch { expenseRepository.deleteForever(id) }
}
