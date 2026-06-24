package com.spends.app.ui.transactions

import com.spends.app.core.time.CycleWindow
import com.spends.app.domain.model.TxnKind
import java.time.LocalDate

/** Period totals for the summary header (PRD §4.17). Balance = income − expense; transfers ignored. */
data class SummaryTotals(
    val income: Long = 0,
    val expense: Long = 0,
    val transfer: Long = 0,
) {
    val balance: Long get() = income - expense
}

/** One category slice shown on a transaction row. */
data class CategoryChipUi(
    val categoryId: Long,
    val name: String,
    val colorHex: String,
    val iconKey: String,
    val amountMinor: Long,
)

/** A single transaction in the timeline. */
data class TransactionRowUi(
    val id: Long,
    val amountMinor: Long,
    val kind: TxnKind,
    val title: String,
    val note: String?,
    val timeLabel: String,
    val categories: List<CategoryChipUi>,
) {
    val primary: CategoryChipUi? get() = categories.firstOrNull()
    val isSplit: Boolean get() = categories.size > 1
}

/** A day's worth of transactions with a net subtotal (income − expense). */
data class DayGroupUi(
    val date: LocalDate,
    val headerLabel: String,
    val netSubtotal: Long,
    val rows: List<TransactionRowUi>,
)

data class TransactionsUiState(
    val loading: Boolean = true,
    val window: CycleWindow? = null,
    val periodLabel: String = "",
    val canStepForward: Boolean = false,
    val search: String = "",
    val totals: SummaryTotals = SummaryTotals(),
    val carryForward: Long? = null,
    val groups: List<DayGroupUi> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && groups.isEmpty()
    val balanceWithCarry: Long? get() = carryForward?.let { it + totals.balance }
}
