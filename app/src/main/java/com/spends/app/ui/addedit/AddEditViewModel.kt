package com.spends.app.ui.addedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.money.Money
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.repo.AllocationInput
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.TransactionInput
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
)

@HiltViewModel
class AddEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    private val expenseId: Long = savedStateHandle[Routes.ARG_EXPENSE_ID] ?: Routes.NO_EXPENSE_ID
    val isEdit: Boolean = expenseId != Routes.NO_EXPENSE_ID

    // Most-used categories first, so the picker surfaces the user's frequent ones at the top.
    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeActiveByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Null until the initial form is ready (immediately for new, after load for edit). */
    private val _initial = MutableStateFlow<AddEditInitial?>(null)
    val initial: StateFlow<AddEditInitial?> = _initial

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished

    init {
        if (isEdit) {
            viewModelScope.launch {
                val e = expenseRepository.getById(expenseId)
                _initial.value = if (e != null) {
                    AddEditInitial(
                        amountText = Money.toEditString(e.expense.amountMinor),
                        kind = e.expense.kind,
                        categoryId = e.allocations.firstOrNull()?.category?.id,
                        merchant = e.expense.merchantRaw.orEmpty(),
                        note = e.expense.note.orEmpty(),
                        occurredAt = e.expense.occurredAt,
                    )
                } else {
                    newInitial()
                }
            }
        } else {
            _initial.value = newInitial()
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

    fun addCategory(name: String, usage: CategoryUsage, onCreated: (Long) -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = categoryRepository.addCustom(name, usage)
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
    ) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            val input = TransactionInput(
                amountMinor = amountMinor,
                kind = kind,
                occurredAt = occurredAt,
                merchantRaw = merchant.ifBlank { null },
                note = note.ifBlank { null },
                allocations = listOf(AllocationInput(categoryId, amountMinor)),
            )
            if (isEdit) expenseRepository.update(expenseId, input) else expenseRepository.create(input)
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
