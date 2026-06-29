package com.spends.app.ui.addedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.spends.app.ui.cards.CardOption
import com.spends.app.ui.cards.PaymentState
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
)

@HiltViewModel
class AddEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val captureRepository: SmsCaptureRepository,
    private val paymentMethodRepository: PaymentMethodRepository,
    settingsRepository: SettingsRepository,
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
            PaymentState(s.smartCycleEnabled, cards.map { CardOption(it.id, it.label, it.last4, it.colorHex) })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaymentState())

    /** The editor offers "Paid with" for a normal add/edit; capture reviews auto-tag from the SMS last4. */
    val showPaidWith: Boolean = !isCapture

    /** Null until the initial form is ready (immediately for new, after load for edit). */
    private val _initial = MutableStateFlow<AddEditInitial?>(null)
    val initial: StateFlow<AddEditInitial?> = _initial

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished

    init {
        when {
            isEdit -> viewModelScope.launch {
                val e = expenseRepository.getById(expenseId)
                _initial.value = if (e != null) {
                    AddEditInitial(
                        amountText = Money.toEditString(e.expense.amountMinor),
                        kind = e.expense.kind,
                        categoryId = e.allocations.firstOrNull()?.category?.id,
                        merchant = e.expense.merchantRaw.orEmpty(),
                        note = e.expense.note.orEmpty(),
                        occurredAt = e.expense.occurredAt,
                        paymentMethodId = e.expense.paymentMethodId,
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
                        note = "",
                        occurredAt = p.occurredAt,
                    )
                } else {
                    newInitial() // the row was confirmed/rejected elsewhere — fall back to a blank add
                }
            }
            // Seed from the unsaved live-capture draft (#4).
            isDraft -> _initial.value = draft!!.let {
                AddEditInitial(
                    amountText = Money.toEditString(it.amountMinor),
                    kind = it.kind,
                    categoryId = it.categoryId,
                    merchant = it.merchant.orEmpty(),
                    note = "",
                    occurredAt = it.occurredAt,
                )
            }
            else -> _initial.value = newInitial()
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
            when {
                // Confirm a queued capture (#9): keeps TxnSource.SMS + dedupe hash + merchant learning,
                // then removes the pending row. The ledger write happens HERE, on explicit Save only.
                isPending -> captureRepository.confirmPendingEdited(
                    pendingId, amountMinor, kind, categoryId, merchant, note, occurredAt,
                )
                // Save an unsaved live-capture draft (#4): tags TxnSource.NOTIFICATION + the dedupe hash.
                isDraft -> captureRepository.commitDraft(
                    amountMinor, kind, categoryId, merchant, note, occurredAt, draft!!.dedupeHash,
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
                    if (isEdit) expenseRepository.update(expenseId, input) else expenseRepository.create(input)
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
