package com.spends.app.ui.addedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.money.FxMath
import com.spends.app.core.money.Money
import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.CaptureDraft
import com.spends.app.data.capture.CaptureDraftStore
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.repo.AllocationInput
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.repo.TransactionInput
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.cards.PaymentState
import com.spends.app.ui.cards.toCardOption
import kotlinx.coroutines.flow.first
import com.spends.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The values used to seed the editable form (defaults for a new txn, or the loaded row for edit). */
data class AddEditInitial(
    val amountText: String,
    val kind: TxnKind,
    val categoryId: Long?,
    val merchant: String,
    val note: String,
    val occurredAt: Long,
    val paymentMethodId: Long? = null,
    /**
     * The one-line conversion receipt for a foreign-currency capture, shown above the amount field so the
     * user can see WHY the number in front of them is what it is — or that it has not been converted at
     * all and needs their attention. Null for the ordinary same-currency entry.
     */
    val conversionNote: String? = null,
    /** True when [amountText] is still in a foreign currency, so the note is a warning, not a receipt. */
    val unconvertedForeign: Boolean = false,
    /**
     * The currency code the alert arrived in ("MYR"), or null for an ordinary same-currency entry. The
     * editor needs it separately from [conversionNote] so it can label the amount field with the right
     * symbol while [unconvertedForeign] holds — the digits in that box are still ringgit, and heading them
     * with the ledger's ₹ made a foreign figure look like an already-correct rupee one.
     */
    val fxCurrency: String? = null,
)

/**
 * The receipt line for a capture that arrived in another currency, or null when it did not.
 *
 * Shared by the queued-capture and live-draft seeding paths so the two say the same thing about the same
 * situation — the review card, the editor and the saved transaction all describe a conversion identically.
 */
internal fun conversionNoteFor(fxCurrency: String?, fxAmountMinor: Long?, fxRateMicros: Long?, amountMinor: Long): String? =
    when {
        fxCurrency != null && fxAmountMinor != null && fxRateMicros != null ->
            FxMath.describe(fxAmountMinor, fxCurrency, amountMinor, fxRateMicros)
        // No rate, but we know what the alert arrived as. Two very different situations share that shape,
        // and telling them apart is the difference between a warning and a note:
        //
        //  - the amount on screen is STILL the foreign one (it equals the original) → nothing has been
        //    converted and nothing has been decided; this is the dangerous state, and it says so;
        //  - the amount differs → the user has already put in what it was worth. The origin is kept as a
        //    record of where the row came from, but warning them about a figure they chose themselves
        //    would be nonsense, so it reads as a fact instead.
        //
        // Worded as standing facts rather than "…before saving", because this same line appears on the
        // review card, in the editor, AND on the transaction long after it was saved.
        fxCurrency != null && fxAmountMinor != null && fxRateMicros == null ->
            if (amountMinor == fxAmountMinor) {
                "This alert was for ${Money.formatCode(fxAmountMinor, fxCurrency)} and no rate was " +
                    "available. The amount below is not converted — set it yourself."
            } else {
                "This alert was for ${Money.formatCode(fxAmountMinor, fxCurrency)}. " +
                    "You set the amount below yourself; no rate was used."
            }
        fxCurrency != null && fxRateMicros == null ->
            "This alert was in $fxCurrency and couldn't be converted. The amount below is not converted."
        else -> null
    }

/**
 * True while the editor is still showing a foreign amount the app could not convert, untouched.
 *
 * This is the editor's share of the guard every no-editor commit path already applies. An alert that
 * could not be converted opens with the FOREIGN figure pre-filled — RM250 sitting where a rupee amount
 * belongs — so the single most likely action on the screen (open the notification, tap Save) filed RM250
 * as ₹250. A pure function rather than a line inside the composable so the rule can be tested directly;
 * it is the kind of guard that must not be able to quietly stop holding.
 *
 * Comparing against the SEEDED text, not tracking an "edited" flag, is deliberate: clearing the box and
 * typing the same digits back means the user has looked at the number and stands behind it, and a blank
 * box is already refused by the amount check. Only an untouched foreign figure is blocked.
 */
internal fun isUntouchedForeignAmount(
    unconvertedForeign: Boolean,
    seededAmountText: String,
    currentAmountText: String,
): Boolean = unconvertedForeign && currentAmountText == seededAmountText

@HiltViewModel
class AddEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val captureRepository: SmsCaptureRepository,
    private val paymentMethodRepository: PaymentMethodRepository,
    private val settingsRepository: SettingsRepository,
    captureDraftStore: CaptureDraftStore,
) : ViewModel() {

    private val expenseId: Long = savedStateHandle[Routes.ARG_EXPENSE_ID] ?: Routes.NO_EXPENSE_ID
    val isEdit: Boolean = expenseId != Routes.NO_EXPENSE_ID

    // Reviewing a queued SMS capture in the full editor (#9): seed from the pending row, write on Save.
    private val pendingId: Long = savedStateHandle[Routes.ARG_PENDING_ID] ?: Routes.NO_PENDING_ID
    private val isPending: Boolean = !isEdit && pendingId != Routes.NO_PENDING_ID

    // Reviewing an unsaved live-capture draft (notification "Edit", #4): consume it once from the store.
    private val fromDraft: Boolean = savedStateHandle[Routes.ARG_FROM_DRAFT] ?: false
    private val draft: CaptureDraft? = if (fromDraft && !isEdit && !isPending) captureDraftStore.consume() else null
    private val isDraft: Boolean = draft != null

    /** Whether this is one of the review-and-add flows (a capture being confirmed for the first time). */
    private val isCapture: Boolean = isPending || isDraft

    val screenTitle: String = when {
        isEdit -> "Edit transaction"
        isCapture -> "Review & add"
        else -> "Add transaction"
    }

    val saveLabel: String = when {
        isEdit -> "Save changes"
        isCapture -> "Add transaction"
        else -> "Save"
    }

    // Most-used categories first, so the picker surfaces the user's frequent ones at the top.
    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeActiveByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether "Paid with" should show (Smart Cycle on) and the available cards. */
    val paymentState: StateFlow<PaymentState> =
        combine(settingsRepository.settings, paymentMethodRepository.observeConfirmed()) { s, cards ->
            PaymentState(s.smartCycleEnabled, cards.map { it.toCardOption() })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaymentState())

    /**
     * The editor always offers "Paid with" (subject to Smart Cycle being on + an expense). For a capture
     * review it's pre-filled with the instrument auto-matched from the SMS (last4, then bank name) so the
     * user can confirm or correct it before saving (#3).
     */
    val showPaidWith: Boolean = true

    /** Null until the initial form is ready (immediately for new, after load for edit). */
    private val _initial = MutableStateFlow<AddEditInitial?>(null)
    val initial: StateFlow<AddEditInitial?> = _initial

    /**
     * The recurring rule that CREATED this transaction, or null (#5) — the overwhelmingly common answer,
     * since only rows the scheduler materialised carry the link.
     *
     * Exposed so the editor can offer a way through to the rule itself. Editing the transaction still edits
     * only the transaction: this is a route, not a binding, so correcting one month's rent here never
     * silently rewrites the standing rule.
     */
    private val _recurringRuleId = MutableStateFlow<Long?>(null)
    val recurringRuleId: StateFlow<Long?> = _recurringRuleId

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished

    init {
        when {
            isEdit -> viewModelScope.launch {
                val e = expenseRepository.getById(expenseId)
                _recurringRuleId.value = e?.expense?.recurringRuleId
                _initial.value = if (e != null) {
                    AddEditInitial(
                        amountText = Money.toEditString(e.expense.amountMinor),
                        kind = e.expense.kind,
                        categoryId = e.allocations.firstOrNull()?.category?.id,
                        merchant = e.expense.merchantRaw.orEmpty(),
                        note = e.expense.note.orEmpty(),
                        occurredAt = e.expense.occurredAt,
                        paymentMethodId = e.expense.paymentMethodId,
                        // A saved converted transaction keeps explaining itself when reopened.
                        conversionNote = conversionNoteFor(
                            e.expense.fxCurrency, e.expense.fxAmountMinor, e.expense.fxRateMicros, e.expense.amountMinor,
                        ),
                        // A SAVED row is never treated as an untouched foreign amount: whatever is stored
                        // is the figure the user accepted, in the ledger's currency. Only the origin is
                        // carried through, so the row can still say what it arrived as.
                        fxCurrency = e.expense.fxCurrency,
                    )
                } else {
                    newInitial()
                }
            }
            // Seed from the queued capture — keep its SMS date (occurredAt) so it lands in the right place
            // in the timeline once added (#9), not at "today".
            isPending -> viewModelScope.launch {
                val p = captureRepository.getPending(pendingId)
                _initial.value = if (p != null) {
                    AddEditInitial(
                        amountText = Money.toEditString(p.amountMinor),
                        kind = p.kind,
                        categoryId = p.categoryId,
                        merchant = p.merchant.orEmpty(),
                        // Pre-fill the note the user last gave this merchant (learned memory; editable —
                        // fuzzy is fine here because the user reviews this screen before saving).
                        note = captureRepository.learnedNoteFor(p.merchant, allowFuzzy = true).orEmpty(),
                        occurredAt = p.occurredAt,
                        // Auto-match the instrument from the SMS so "Paid with" is pre-filled for review (#3).
                        paymentMethodId = paymentMethodRepository.matchInstrument(p.last4, p.institution),
                        conversionNote = conversionNoteFor(p.fxCurrency, p.fxAmountMinor, p.fxRateMicros, p.amountMinor),
                        unconvertedForeign = p.isUnconvertedForeign,
                        fxCurrency = p.fxCurrency,
                    )
                } else {
                    newInitial() // the row was confirmed/rejected elsewhere — fall back to a blank add
                }
            }
            // Seed from the unsaved live-capture draft (#4); the draft already carries the matched instrument (#3).
            isDraft -> _initial.value = draft!!.let {
                AddEditInitial(
                    amountText = Money.toEditString(it.amountMinor),
                    kind = it.kind,
                    categoryId = it.categoryId,
                    merchant = it.merchant.orEmpty(),
                    note = it.note.orEmpty(), // learned merchant note, pre-filled for review
                    occurredAt = it.occurredAt,
                    paymentMethodId = it.paymentMethodId,
                    conversionNote = conversionNoteFor(it.fxCurrency, it.fxAmountMinor, it.fxRateMicros, it.amountMinor),
                    unconvertedForeign = it.unconvertedForeign,
                    fxCurrency = it.fxCurrency,
                )
            }
            // A fresh manual add pre-selects the user's default instrument (#2) when Smart Cycle is on.
            else -> viewModelScope.launch {
                val s = settingsRepository.settings.first()
                val default = if (s.smartCycleEnabled) s.defaultPaymentMethodId else null
                _initial.value = newInitial().copy(paymentMethodId = default)
            }
        }
    }

    private fun newInitial() = AddEditInitial(
        amountText = "",
        kind = TxnKind.EXPENSE,
        categoryId = null,
        merchant = "",
        note = "",
        occurredAt = DateUtils.nowMillis(),
    )

    fun addCategory(name: String, usage: CategoryUsage, iconKey: String?, onCreated: (Long) -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = categoryRepository.addCustom(name, usage, iconKey = iconKey)
            onCreated(id)
        }
    }

    fun save(
        amountMinor: Long,
        kind: TxnKind,
        categoryId: Long,
        merchant: String,
        note: String,
        occurredAt: Long,
        paymentMethodId: Long? = null,
    ) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            // The instrument the user confirmed/corrected (#3) — only meaningful for an expense.
            val pmId = if (kind == TxnKind.EXPENSE) paymentMethodId else null
            when {
                // Confirm a queued capture (#9): keeps TxnSource.SMS + dedupe hash + merchant learning,
                // then removes the pending row. The ledger write happens HERE, on explicit Save only.
                isPending -> captureRepository.confirmPendingEdited(
                    pendingId, amountMinor, kind, categoryId, merchant, note, occurredAt, pmId,
                )
                // Save an unsaved live-capture draft (#4): tags TxnSource.NOTIFICATION + the dedupe hash.
                isDraft -> captureRepository.commitDraft(
                    amountMinor, kind, categoryId, merchant, note, occurredAt, draft!!.dedupeHash, pmId,
                    relaxedHash = draft!!.relaxedHash,
                    fromNotification = draft!!.fromNotification,
                    // Carry the conversion receipt so a saved converted transaction can still show what
                    // it was converted from. commitDraft drops it if the user edited the amount.
                    fx = draft!!.let {
                        SmsCaptureRepository.Fx(it.amountMinor, it.fxCurrency, it.fxAmountMinor, it.fxRateMicros)
                    },
                )
                else -> {
                    val input = TransactionInput(
                        amountMinor = amountMinor,
                        kind = kind,
                        occurredAt = occurredAt,
                        merchantRaw = merchant.ifBlank { null },
                        note = note.ifBlank { null },
                        allocations = listOf(AllocationInput(categoryId, amountMinor)),
                        paymentMethodId = paymentMethodId,
                    )
                    if (isEdit) {
                        expenseRepository.update(expenseId, input)
                        // Correcting a captured (SMS/NOTIFICATION) row in the editor is the user's main
                        // correction path since swipe went away — but teach the merchant memory ONLY from
                        // what the user actually CHANGED. A date/amount-only edit must not re-learn the
                        // row's (possibly guessed) category, and an untouched empty note field must not
                        // erase a remembered note ("field was visible" ≠ "user cleared it").
                        val loaded = _initial.value
                        val categoryChanged = loaded != null && loaded.categoryId != categoryId
                        val noteChanged = loaded != null && loaded.note.trim() != note.trim()
                        if (categoryChanged || noteChanged) {
                            // Pass the note/category ONLY as deliberate when the user changed them —
                            // an untouched row note must not displace a newer remembered note, and a
                            // note-only save must not re-learn the row's (possibly guessed) category.
                            captureRepository.learnFromTransaction(
                                expenseId, categoryId, note.takeIf { noteChanged },
                                noteShown = noteChanged, categoryDeliberate = categoryChanged,
                            )
                        }
                    } else {
                        expenseRepository.create(input)
                    }
                }
            }
            _saving.value = false
            _finished.value = true
        }
    }

    /** Move the edited transaction to Trash, then close the editor. */
    fun delete() {
        if (!isEdit || _saving.value) return
        _saving.value = true
        viewModelScope.launch {
            expenseRepository.moveToTrash(expenseId)
            _saving.value = false
            _finished.value = true
        }
    }
}
