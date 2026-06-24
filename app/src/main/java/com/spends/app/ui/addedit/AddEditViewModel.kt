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
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private data class AddEditForm(
    val amountText: String = "",
    val kind: TxnKind = TxnKind.EXPENSE,
    val selectedCategoryId: Long? = null,
    val merchant: String = "",
    val note: String = "",
    val occurredAt: Long = DateUtils.nowMillis(),
    val saving: Boolean = false,
    val finished: Boolean = false,
)

data class AddEditUiState(
    val isEdit: Boolean = false,
    val amountText: String = "",
    val kind: TxnKind = TxnKind.EXPENSE,
    val selectedCategoryId: Long? = null,
    val merchant: String = "",
    val note: String = "",
    val occurredAt: Long = DateUtils.nowMillis(),
    val categories: List<CategoryEntity> = emptyList(),
    val saving: Boolean = false,
    val finished: Boolean = false,
) {
    val amountMinor: Long? get() = Money.parseRupeesToMinor(amountText)?.takeIf { it > 0 }
    val canSave: Boolean get() = amountMinor != null && selectedCategoryId != null && !saving
}

@HiltViewModel
class AddEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    private val expenseId: Long = savedStateHandle[Routes.ARG_EXPENSE_ID] ?: Routes.NO_EXPENSE_ID
    private val isEdit: Boolean = expenseId != Routes.NO_EXPENSE_ID

    private val form = MutableStateFlow(AddEditForm())

    val uiState: StateFlow<AddEditUiState> =
        combine(form, categoryRepository.observeActive()) { f, categories ->
            AddEditUiState(
                isEdit = isEdit,
                amountText = f.amountText,
                kind = f.kind,
                selectedCategoryId = f.selectedCategoryId,
                merchant = f.merchant,
                note = f.note,
                occurredAt = f.occurredAt,
                categories = categories,
                saving = f.saving,
                finished = f.finished,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddEditUiState(isEdit = isEdit))

    init {
        if (isEdit) {
            viewModelScope.launch {
                expenseRepository.getById(expenseId)?.let { e ->
                    form.update {
                        it.copy(
                            amountText = Money.toEditString(e.expense.amountMinor),
                            kind = e.expense.kind,
                            selectedCategoryId = e.allocations.firstOrNull()?.category?.id,
                            merchant = e.expense.merchantRaw.orEmpty(),
                            note = e.expense.note.orEmpty(),
                            occurredAt = e.expense.occurredAt,
                        )
                    }
                }
            }
        }
    }

    fun setAmount(value: String) = form.update { it.copy(amountText = value.filter { c -> c.isDigit() || c == '.' }) }
    fun setKind(kind: TxnKind) = form.update { it.copy(kind = kind) }
    fun selectCategory(id: Long) = form.update { it.copy(selectedCategoryId = id) }
    fun setMerchant(value: String) = form.update { it.copy(merchant = value) }
    fun setNote(value: String) = form.update { it.copy(note = value) }
    fun setDate(millis: Long) = form.update { it.copy(occurredAt = millis) }

    fun addCategory(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = categoryRepository.addCustom(name)
            form.update { it.copy(selectedCategoryId = id) }
        }
    }

    fun save() {
        val current = uiState.value
        val amount = current.amountMinor ?: return
        val categoryId = current.selectedCategoryId ?: return
        if (current.saving) return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val input = TransactionInput(
                amountMinor = amount,
                kind = current.kind,
                occurredAt = current.occurredAt,
                merchantRaw = current.merchant.ifBlank { null },
                note = current.note.ifBlank { null },
                allocations = listOf(AllocationInput(categoryId, amount)),
            )
            if (isEdit) expenseRepository.update(expenseId, input) else expenseRepository.create(input)
            form.update { it.copy(saving = false, finished = true) }
        }
    }
}
