package com.spends.app.ui.breakdown

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.period.PeriodRange
import com.spends.app.core.period.PeriodResolver
import com.spends.app.core.period.PeriodType
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.ExpenseWithAllocations
import com.spends.app.data.db.entity.PaymentMethodEntity
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.core.period.PeriodSelectionStore
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.cards.isCardInstrument
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/** One instrument's spend inside the current Smart Cycle window: a card, a named bank, or Bank/UPI. */
data class InstrumentRowUi(
    val id: Long?, // null = the Bank / UPI bucket (untagged spends)
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
 * The Smart Cycle "per-instrument breakdown" (design Screen 2): where this cycle's spend went, per card /
 * bank. The cycle is the ONE contiguous Smart Cycle window (anchored on the user's reset day, default =
 * salary day, stepped by the selection's offset) — the same window the timeline balance and Analytics use,
 * so every number here reconciles with them. Expense rows are grouped by the instrument that paid them;
 * untagged spends fall into the generic Bank/UPI bucket.
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
            // The breakdown always shows the whole cycle — step it by the selection's offset, but ignore any
            // single-card narrowing (that's a separate view).
            val offset = if (sel.type == PeriodType.SMART_CYCLE) sel.cycleOffset else 0
            val window = PeriodResolver.resolve(
                type = PeriodType.SMART_CYCLE,
                range = PeriodRange.CURRENT,
                salaryDay = settings.salaryCycleStartDay,
                // A retained back-stack entry can reach this screen with the feature just turned off —
                // anchor on the salary day then, matching the coerced app-wide fallback.
                smartDay = if (settings.smartCycleEnabled) settings.effectiveSmartResetDay else settings.salaryCycleStartDay,
                today = today,
                earliestDataDay = null,
                customStartMillis = null,
                customEndExclusiveMillis = null,
                cycleOffset = offset,
            )
            expenseRepository.observeBetween(window.startMillis, window.endExclusiveMillis)
                .map { items -> build(window.label, cards, items) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CycleBreakdownUiState())

    private fun build(
        label: String,
        cards: List<PaymentMethodEntity>,
        items: List<ExpenseWithAllocations>,
    ): CycleBreakdownUiState {
        val expenses = items.filter { it.expense.kind == TxnKind.EXPENSE }
        // Group by the paying instrument; a txn tagged to an instrument that's no longer confirmed (deleted)
        // counts under the generic Bank/UPI bucket, so the rows always sum to the window's total spend.
        val knownIds = cards.map { it.id }.toSet()
        val byInstrument = expenses.groupBy { e ->
            e.expense.paymentMethodId?.takeIf { it in knownIds }
        }

        // Cards vs named banks — split by instrument type (#2).
        val cardEntities = cards.filter { it.isCardInstrument() }
        val bankEntities = cards.filter { !it.isCardInstrument() }

        val cardRows = cardEntities.map { card ->
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

        // Named banks first, then the generic "Bank / UPI" bucket (untagged spend) — shown when it has
        // spend, or when there are no named banks at all (the original single-bucket behaviour).
        val namedBankRows = bankEntities.map { bank ->
            val its = byInstrument[bank.id].orEmpty()
            InstrumentRowUi(
                id = bank.id,
                name = bank.label,
                colorHex = bank.colorHex,
                sub = bankSub(bank, its.size),
                amountMinor = its.sumOf { it.expense.amountMinor },
                isCard = false,
            )
        }
        val genericItems = byInstrument[null].orEmpty()
        val genericBankRow = InstrumentRowUi(
            id = null,
            name = "Bank / UPI",
            colorHex = BANK_COLOR,
            sub = countLabel(genericItems.size),
            amountMinor = genericItems.sumOf { it.expense.amountMinor },
            isCard = false,
        )
        val bankRows = buildList {
            addAll(namedBankRows)
            if (genericItems.isNotEmpty() || bankEntities.isEmpty()) add(genericBankRow)
        }.sortedByDescending { it.amountMinor }

        return CycleBreakdownUiState(
            loading = false,
            label = label,
            totalSpendMinor = expenses.sumOf { it.expense.amountMinor },
            instrumentCount = cardRows.size + bankRows.size,
            cardCount = cardRows.size,
            bankCount = bankRows.size,
            cards = cardRows,
            banks = bankRows,
        )
    }

    // The sub-line describes the CARD (its last4 + billing day) plus its txn count in THIS cycle window —
    // the count is per-window, not per-statement (statements get their own treatment in the dues feature).
    private fun cardSub(card: PaymentMethodEntity, txns: Int): String {
        val parts = buildList {
            card.last4?.let { add("·$it") }
            card.billingDay?.let { add("Bills ${ordinal(it)}") }
            add(countLabel(txns))
        }
        return parts.joinToString("  ·  ")
    }

    private fun bankSub(bank: PaymentMethodEntity, txns: Int): String {
        val parts = buildList {
            bank.last4?.let { add("·$it") }
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
