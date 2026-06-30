package com.spends.app.ui.breakdown

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.period.CardCycleInfo
import com.spends.app.core.period.CompositeCycleResolver
import com.spends.app.core.period.PeriodType
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.ExpenseWithAllocations
import com.spends.app.data.db.entity.PaymentMethodEntity
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.core.period.PeriodSelectionStore
import com.spends.app.domain.model.TxnKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/** One instrument's spend in its own current cycle: a card (own billing cycle) or the Bank/UPI bucket. */
data class InstrumentRowUi(
    val id: Long?, // null = the Bank / UPI bucket (salary cycle)
    val name: String,
    val colorHex: String,
    val sub: String,
    val amountMinor: Long,
    val isCard: Boolean,
)

data class CycleBreakdownUiState(
    val loading: Boolean = true,
    val label: String = "",
    val totalSpendMinor: Long = 0,
    val instrumentCount: Int = 0,
    val cardCount: Int = 0,
    val bankCount: Int = 0,
    val cards: List<InstrumentRowUi> = emptyList(),
    val banks: List<InstrumentRowUi> = emptyList(),
)

/**
 * The Smart Cycle "per-instrument breakdown" (design Screen 2). Resolves the FULL composite (every card on
 * its own billing cycle + the Bank/UPI bucket on the salary cycle) at the selection's current offset, then
 * slices the bounding-window EXPENSE rows into each instrument. The total = the composite SPEND (expense
 * only), so it reconciles with the Analytics donut centre / total expense — NOT the timeline balance hero
 * (which is income − expense).
 */
@HiltViewModel
class CycleBreakdownViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val settingsRepository: SettingsRepository,
    private val paymentMethodRepository: PaymentMethodRepository,
    periodSelectionStore: PeriodSelectionStore,
) : ViewModel() {

    val state: StateFlow<CycleBreakdownUiState> =
        combine(
            periodSelectionStore.selection,
            settingsRepository.settings,
            paymentMethodRepository.observeConfirmed(),
        ) { sel, settings, cards ->
            Triple(sel, settings, cards)
        }.flatMapLatest { (sel, settings, cards) ->
            val today = LocalDate.now(DateUtils.ZONE)
            val salaryDay = settings.salaryCycleStartDay
            // The breakdown is always the union of instruments — step it by the selection's offset, but
            // ignore any single-card narrowing (that's a separate view).
            val offset = if (sel.type == PeriodType.SMART_CYCLE) sel.cycleOffset else 0
            val infos = cards.map { CardCycleInfo(it.id, it.label, it.colorHex, it.last4, it.billingDay) }
            val composite = CompositeCycleResolver.resolveSmartCycle(infos, salaryDay, today, offset)
            expenseRepository.observeBetween(composite.boundingStartMillis, composite.boundingEndExclusiveMillis)
                .map { boundingItems -> build(composite.label, composite, cards, boundingItems) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CycleBreakdownUiState())

    private fun build(
        label: String,
        composite: com.spends.app.core.period.CompositePeriod,
        cards: List<PaymentMethodEntity>,
        boundingItems: List<ExpenseWithAllocations>,
    ): CycleBreakdownUiState {
        val expenses = boundingItems.filter {
            it.expense.kind == TxnKind.EXPENSE && composite.contains(it.expense.occurredAt, it.expense.paymentMethodId)
        }
        val byInstrument = expenses.groupBy { composite.instrumentIdFor(it.expense.paymentMethodId) }

        val cardRows = cards.map { card ->
            val its = byInstrument[card.id].orEmpty()
            InstrumentRowUi(
                id = card.id,
                name = card.label,
                colorHex = card.colorHex,
                sub = cardSub(card, its.size),
                amountMinor = its.sumOf { it.expense.amountMinor },
                isCard = true,
            )
        }.sortedByDescending { it.amountMinor }

        val bankItems = byInstrument[null].orEmpty()
        val bankRow = InstrumentRowUi(
            id = null,
            name = "Bank / UPI",
            colorHex = BANK_COLOR,
            sub = "Salary cycle · ${countLabel(bankItems.size)}",
            amountMinor = bankItems.sumOf { it.expense.amountMinor },
            isCard = false,
        )

        return CycleBreakdownUiState(
            loading = false,
            label = label,
            totalSpendMinor = expenses.sumOf { it.expense.amountMinor },
            instrumentCount = cardRows.size + 1,
            cardCount = cardRows.size,
            bankCount = 1,
            cards = cardRows,
            banks = listOf(bankRow),
        )
    }

    private fun cardSub(card: PaymentMethodEntity, txns: Int): String {
        val parts = buildList {
            card.last4?.let { add("·$it") }
            add(card.billingDay?.let { "Bills ${ordinal(it)}" } ?: "Salary cycle")
            add(countLabel(txns))
        }
        return parts.joinToString("  ·  ")
    }

    private fun countLabel(n: Int): String = "$n txn${if (n == 1) "" else "s"}"

    private fun ordinal(day: Int): String {
        val suffix = when {
            day in 11..13 -> "th"
            day % 10 == 1 -> "st"
            day % 10 == 2 -> "nd"
            day % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$day$suffix"
    }

    private companion object {
        const val BANK_COLOR = "#475569" // slate — the Bank/UPI bucket has no card accent
    }
}
