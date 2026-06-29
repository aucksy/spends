package com.spends.app.ui.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.time.CycleUtils
import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** One managed card, with its CURRENT billing-cycle spend (computed for the screen). */
data class CardUi(
    val id: Long,
    val label: String,
    val institution: String?,
    val last4: String?,
    val colorHex: String,
    val billingDay: Int?,
    val dueDay: Int?,
    val cycleSpendMinor: Long,
    val txnCount: Int,
    val cycleLabel: String, // e.g. "17 Jun – 16 Jul"
    val billsLabel: String?, // e.g. "Bills 17th"; null when no billing day set yet
)

/** An auto-discovered card awaiting review ("Cards to review"). */
data class CandidateUi(
    val id: Long,
    val label: String,
    val institution: String?,
    val last4: String?,
    val colorHex: String,
)

data class CardsUiState(
    val cards: List<CardUi> = emptyList(),
    val candidates: List<CandidateUi> = emptyList(),
    val loading: Boolean = true,
    val scanning: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class CardsViewModel @Inject constructor(
    private val paymentMethodRepository: PaymentMethodRepository,
    private val expenseRepository: ExpenseRepository,
    private val settingsRepository: SettingsRepository,
    private val smsCaptureRepository: SmsCaptureRepository,
) : ViewModel() {

    private data class Banner(val scanning: Boolean = false, val message: String? = null)

    private val banner = MutableStateFlow(Banner())

    // 70 days back covers the longest possible billing cycle; the per-card window is recomputed live.
    private val since = DateUtils.startOfDayMillis(LocalDate.now(DateUtils.ZONE).minusDays(70))

    val state: StateFlow<CardsUiState> = combine(
        paymentMethodRepository.observeConfirmed(),
        paymentMethodRepository.observeCandidates(),
        expenseRepository.observeCardExpensesSince(since),
        settingsRepository.settings,
        banner,
    ) { confirmed, candidates, cardExpenses, settings, b ->
        val today = LocalDate.now(DateUtils.ZONE)
        val salaryDay = settings.salaryCycleStartDay
        CardsUiState(
            cards = confirmed.map { card ->
                val anchor = card.billingDay ?: salaryDay
                val window = CycleUtils.windowFor(today, anchor)
                val start = window.startMillis()
                val end = window.endExclusiveMillis()
                val inWindow = cardExpenses.filter {
                    it.paymentMethodId == card.id && it.occurredAt >= start && it.occurredAt < end
                }
                CardUi(
                    id = card.id,
                    label = card.label,
                    institution = card.institution,
                    last4 = card.last4,
                    colorHex = card.colorHex,
                    billingDay = card.billingDay,
                    dueDay = card.dueDay,
                    cycleSpendMinor = inWindow.sumOf { it.amountMinor },
                    txnCount = inWindow.size,
                    cycleLabel = "${dayFmt.format(window.start)} – ${dayFmt.format(window.endInclusive)}",
                    billsLabel = card.billingDay?.let { "Bills ${ordinal(it)}" },
                )
            },
            candidates = candidates.map { CandidateUi(it.id, it.label, it.institution, it.last4, it.colorHex) },
            loading = false,
            scanning = b.scanning,
            message = b.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardsUiState())

    fun addCard(label: String, last4: String?, institution: String?, billingDay: Int?, dueDay: Int?) =
        viewModelScope.launch {
            paymentMethodRepository.addManual(
                label = label,
                institution = institution,
                last4 = last4,
                billingDay = billingDay,
                dueDay = dueDay,
            )
        }

    fun updateCard(id: Long, label: String, last4: String?, institution: String?, billingDay: Int?, dueDay: Int?) =
        viewModelScope.launch {
            val existing = paymentMethodRepository.getById(id) ?: return@launch
            paymentMethodRepository.updateCard(
                existing.copy(
                    label = label.ifBlank { existing.label },
                    last4 = last4?.ifBlank { null },
                    institution = institution?.ifBlank { null },
                    billingDay = billingDay,
                    dueDay = dueDay,
                ),
            )
        }

    /** Confirm a discovered candidate, applying any edits the user made on the review card. */
    fun confirmCandidate(id: Long, label: String, last4: String?, institution: String?, billingDay: Int?, dueDay: Int?) =
        viewModelScope.launch {
            val existing = paymentMethodRepository.getById(id) ?: return@launch
            paymentMethodRepository.confirmCandidate(
                existing.copy(
                    label = label.ifBlank { existing.label },
                    last4 = last4?.ifBlank { null },
                    institution = institution?.ifBlank { null },
                    billingDay = billingDay,
                    dueDay = dueDay,
                ),
            )
        }

    fun dismissCandidate(id: Long) = viewModelScope.launch { paymentMethodRepository.dismissCandidate(id) }

    fun deleteCard(id: Long) = viewModelScope.launch { paymentMethodRepository.delete(id) }

    fun mergeCards(sourceId: Long, targetId: Long) = viewModelScope.launch {
        paymentMethodRepository.merge(sourceId, targetId)
    }

    /** "Scan past SMS for cards" — reads the last [monthsBack] months of SMS and proposes new cards. */
    fun scanForCards(monthsBack: Int = 12) = viewModelScope.launch {
        banner.update { it.copy(scanning = true, message = null) }
        val end = DateUtils.nowMillis()
        val start = DateUtils.startOfDayMillis(LocalDate.now(DateUtils.ZONE).minusMonths(monthsBack.toLong()))
        val added = runCatching { smsCaptureRepository.scanInboxForCards(start, end) }.getOrDefault(0)
        banner.update {
            it.copy(
                scanning = false,
                message = if (added > 0) "Found $added new card${if (added == 1) "" else "s"} to review" else "No new cards found in SMS",
            )
        }
    }

    fun clearMessage() = banner.update { it.copy(message = null) }

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
        val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    }
}
