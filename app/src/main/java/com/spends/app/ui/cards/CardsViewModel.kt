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
import kotlinx.coroutines.flow.map
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
    // A statement-SMS-detected billing day (#9) — pre-fills the "Review & Add" editor so the user confirms it.
    val proposedBillingDay: Int? = null,
)

data class CardsUiState(
    val cards: List<CardUi> = emptyList(),
    val banks: List<CardUi> = emptyList(),
    val candidates: List<CandidateUi> = emptyList(),
    // The instrument new expenses pre-select (#2); null = generic Bank. [defaultLabel] is its display name.
    val defaultId: Long? = null,
    val defaultLabel: String = "Bank",
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
        fun toUi(pm: com.spends.app.data.db.entity.PaymentMethodEntity): CardUi {
            val anchor = pm.billingDay ?: salaryDay
            val window = CycleUtils.windowFor(today, anchor)
            val start = window.startMillis()
            val end = window.endExclusiveMillis()
            val inWindow = cardExpenses.filter {
                it.paymentMethodId == pm.id && it.occurredAt >= start && it.occurredAt < end
            }
            return CardUi(
                id = pm.id,
                label = pm.label,
                institution = pm.institution,
                last4 = pm.last4,
                colorHex = pm.colorHex,
                billingDay = pm.billingDay,
                dueDay = pm.dueDay,
                cycleSpendMinor = inWindow.sumOf { it.amountMinor },
                txnCount = inWindow.size,
                cycleLabel = "${dayFmt.format(window.start)} – ${dayFmt.format(window.endInclusive)}",
                billsLabel = pm.billingDay?.let { "Bills ${ordinal(it)}" },
            )
        }
        val defaultId = settings.defaultPaymentMethodId
        CardsUiState(
            cards = confirmed.filter { it.isCardInstrument() }.map { toUi(it) },
            banks = confirmed.filter { !it.isCardInstrument() }.map { toUi(it) },
            candidates = candidates.map { CandidateUi(it.id, it.label, it.institution, it.last4, it.colorHex, it.proposedBillingDay) },
            defaultId = defaultId,
            defaultLabel = confirmed.firstOrNull { it.id == defaultId }?.label ?: "Bank",
            loading = false,
            scanning = b.scanning,
            message = b.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardsUiState())

    /** Dismissed ("Not a card") instruments, shown in a restorable section (#14). */
    val dismissed: StateFlow<List<CandidateUi>> = paymentMethodRepository.observeDismissed()
        .map { list -> list.map { CandidateUi(it.id, it.label, it.institution, it.last4, it.colorHex) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addCard(label: String, last4: String?, institution: String?, billingDay: Int?, dueDay: Int?) =
        viewModelScope.launch {
            paymentMethodRepository.addManual(
                label = label,
                institution = institution,
                last4 = last4,
                type = com.spends.app.domain.model.PaymentMethodType.CREDIT_CARD,
                billingDay = billingDay,
                dueDay = dueDay,
            )
        }

    /** Add a bank / UPI account (#2) — no billing day; it rides the salary cycle. */
    fun addBank(label: String, last4: String?, institution: String?) =
        viewModelScope.launch {
            paymentMethodRepository.addManual(
                label = label,
                institution = institution,
                last4 = last4,
                type = com.spends.app.domain.model.PaymentMethodType.BANK_ACCOUNT,
                billingDay = null,
                dueDay = null,
            )
        }

    /** Set the default "Paid with" instrument for new expenses (#2); null = generic Bank. */
    fun setDefaultInstrument(id: Long?) = viewModelScope.launch { settingsRepository.setDefaultPaymentMethodId(id) }

    /** Apply one billing day to several cards at once (#10, replaces the old per-card Merge). */
    fun setCommonBillingDay(cardIds: Set<Long>, billingDay: Int) = viewModelScope.launch {
        cardIds.forEach { id ->
            val card = paymentMethodRepository.getById(id) ?: return@forEach
            // Setting a day explicitly clears any pending statement proposal (#13).
            paymentMethodRepository.updateCard(card.copy(billingDay = billingDay, proposedBillingDay = null))
        }
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
                    // If the user sets their own billing day, drop any stale statement proposal (#13).
                    proposedBillingDay = if (billingDay != null) null else existing.proposedBillingDay,
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

    /** X — remove a candidate row; a later scan can re-discover it (#14). */
    fun removeCandidate(id: Long) = viewModelScope.launch { paymentMethodRepository.removeCandidate(id) }

    /** Restore a dismissed instrument back to a review candidate (#14). */
    fun restoreDismissed(id: Long) = viewModelScope.launch { paymentMethodRepository.restoreDismissed(id) }

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
