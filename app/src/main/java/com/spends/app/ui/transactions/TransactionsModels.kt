package com.spends.app.ui.transactions

import com.spends.app.core.time.CycleWindow
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
import java.time.LocalDate

/** Period totals for the summary header (PRD §4.17). Balance = income − expense. */
data class SummaryTotals(
    val income: Long = 0,
    val expense: Long = 0,
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
    // The title is the (primary) CATEGORY name now (#2) — merchant is shown only in the editor.
    val title: String,
    val note: String?,
    // The primary category's total for the SELECTED CYCLE (#2): shown as "<Category> Total: X" under the
    // title. The same value appears on every row of that category in the period.
    val categoryTotalMinor: Long,
    // Null when the time is synthetic (e.g. an auto-generated recurring item is stamped at start-of-day,
    // so showing "12:00 AM" would be misleading). The row then shows just the category + source.
    val timeLabel: String?,
    val source: TxnSource,
    val categories: List<CategoryChipUi>,
) {
    val primary: CategoryChipUi? get() = categories.firstOrNull()
    val isSplit: Boolean get() = categories.size > 1
}

/** A day's worth of transactions with per-day Expense + Income subtotals (#1) and a net subtotal. */
data class DayGroupUi(
    val date: LocalDate,
    val headerLabel: String,
    val expenseSubtotal: Long,
    val incomeSubtotal: Long,
    val netSubtotal: Long,
    val rows: List<TransactionRowUi>,
)

data class TransactionsUiState(
    val loading: Boolean = true,
    val window: CycleWindow? = null,
    val periodLabel: String = "",
    val canStepForward: Boolean = false,
    // Card-billing-aware Smart Cycle: the NEXT cycle already holds spends that rolled forward past a card's
    // billing day, so the ‹›-forward arrow is enabled from the current cycle to reach them (normally forward
    // is capped at the present). [shiftedCardNames] names those cards for a one-per-card "moved to next" badge.
    val canGoForwardToNext: Boolean = false,
    val shiftedCardNames: List<String> = emptyList(),
    val search: String = "",
    val totals: SummaryTotals = SummaryTotals(),
    val carryForward: Long? = null,
    val groups: List<DayGroupUi> = emptyList(),
    // True for the Smart Cycle composite (Round B) — the header offers a "per-instrument breakdown" link.
    val isComposite: Boolean = false,
    // Single-Card (#7): overrides the balance hero with the SALARY cycle's remaining balance (income − all
    // expenses) + its label, so a single card doesn't read as a bare negative. null = use totals.balance.
    val headlineBalanceMinor: Long? = null,
    val headlineLabel: String? = null,
) {
    val isEmpty: Boolean get() = !loading && groups.isEmpty()
    val balanceWithCarry: Long? get() = carryForward?.let { it + totals.balance }
}
