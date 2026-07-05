package com.spends.app.ui.quickadd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.repo.AllocationInput
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.repo.TransactionInput
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.PaymentMethodType
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.cards.PaymentState
import com.spends.app.ui.cards.toCardOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One slice of a split at save time: a category, its amount (paise), and its own note (#5). */
data class SplitSlice(val categoryId: Long, val amountMinor: Long, val note: String?)

/** Backs the half-screen quick-add sheet. Reuses the same repositories as the full editor. */
@HiltViewModel
class QuickAddViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val paymentMethodRepository: PaymentMethodRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeActiveByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether "Paid with" should show (Smart Cycle on) and the available cards. */
    val paymentState: StateFlow<PaymentState> =
        combine(settingsRepository.settings, paymentMethodRepository.observeConfirmed()) { s, cards ->
            PaymentState(
                enabled = s.smartCycleEnabled,
                cards = cards.map { it.toCardOption() },
                defaultId = if (s.smartCycleEnabled) s.defaultPaymentMethodId else null,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaymentState())

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    fun addCategory(name: String, usage: CategoryUsage, iconKey: String?, onCreated: (Long) -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = categoryRepository.addCustom(name, usage, iconKey = iconKey)
            onCreated(id)
        }
    }

    /**
     * Add a bank/card straight from the quick-add "Paid with" picker (#3), then hand back its new id so the
     * caller can auto-select it — the sheet never closes, so the in-progress entry is preserved. Banks ride
     * the salary cycle (no billing day); cards keep their own.
     */
    fun addInstrument(
        label: String,
        last4: String?,
        institution: String?,
        billingDay: Int?,
        dueDay: Int?,
        isBank: Boolean,
        onCreated: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            val id = paymentMethodRepository.addManual(
                label = label,
                institution = institution,
                last4 = last4,
                type = if (isBank) PaymentMethodType.BANK_ACCOUNT else PaymentMethodType.CREDIT_CARD,
                billingDay = if (isBank) null else billingDay,
                dueDay = dueDay,
            )
            onCreated(id)
        }
    }

    /** Persist the transaction, then invoke [onSaved] on the main thread for the caller to dismiss. */
    fun save(
        amountMinor: Long,
        kind: TxnKind,
        categoryId: Long,
        note: String,
        occurredAt: Long,
        paymentMethodId: Long? = null,
        onSaved: () -> Unit,
    ) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            expenseRepository.create(
                TransactionInput(
                    amountMinor = amountMinor,
                    kind = kind,
                    occurredAt = occurredAt,
                    merchantRaw = null,
                    note = note.ifBlank { null },
                    allocations = listOf(AllocationInput(categoryId, amountMinor)),
                    paymentMethodId = paymentMethodId,
                ),
            )
            _saving.value = false
            onSaved()
        }
    }

    /**
     * Persist a split: each slice becomes its own normal transaction in the timeline (BAU), sharing the same
     * kind / date / instrument but carrying its OWN note (#5). Written atomically (all-or-nothing). The UI
     * guarantees the slices sum to the entered total and every slice is valid; we re-check defensively and
     * drop any zero/negative slice.
     */
    fun saveSplit(
        kind: TxnKind,
        occurredAt: Long,
        paymentMethodId: Long?,
        slices: List<SplitSlice>,
        onSaved: () -> Unit,
    ) {
        if (_saving.value) return
        val valid = slices.filter { it.amountMinor > 0 }
        if (valid.isEmpty()) return
        _saving.value = true
        viewModelScope.launch {
            expenseRepository.createAll(
                valid.map { slice ->
                    TransactionInput(
                        amountMinor = slice.amountMinor,
                        kind = kind,
                        occurredAt = occurredAt,
                        merchantRaw = null,
                        note = slice.note?.ifBlank { null },
                        allocations = listOf(AllocationInput(slice.categoryId, slice.amountMinor)),
                        paymentMethodId = paymentMethodId,
                    )
                },
            )
            _saving.value = false
            onSaved()
        }
    }

    fun nowMillis(): Long = DateUtils.nowMillis()
}
