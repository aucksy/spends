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
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.cards.CardOption
import com.spends.app.ui.cards.PaymentState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
                cards = cards.map { CardOption(it.id, it.label, it.last4, it.colorHex) },
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

    fun nowMillis(): Long = DateUtils.nowMillis()
}
