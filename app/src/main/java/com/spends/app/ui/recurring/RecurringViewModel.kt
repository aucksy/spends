package com.spends.app.ui.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.RecurringRuleEntity
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.data.repo.RecurringInput
import com.spends.app.data.repo.RecurringRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val recurringRepository: RecurringRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    val rules: StateFlow<List<RecurringRuleEntity>> = recurringRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeActiveByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(input: RecurringInput, editingId: Long?) {
        viewModelScope.launch {
            if (editingId == null) recurringRepository.add(input) else recurringRepository.update(editingId, input)
        }
    }

    fun setActive(id: Long, active: Boolean) =
        viewModelScope.launch { recurringRepository.setActive(id, active) }

    fun delete(id: Long) = viewModelScope.launch { recurringRepository.delete(id) }
}
