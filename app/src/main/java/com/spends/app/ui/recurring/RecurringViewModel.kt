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

    fun save(input: RecurringInput, editingId: Long?, applyToPast: Boolean = false) {
        viewModelScope.launch {
            if (editingId == null) {
                recurringRepository.add(input)
            } else {
                recurringRepository.update(editingId, input, applyToPast)
            }
            // Materialise immediately so a past start date backfills NOW (#9) instead of waiting for the next
            // launch / 9am worker; also lets an edit's forward roll reflect right away.
            recurringRepository.materializeDue(com.spends.app.core.time.DateUtils.nowMillis())
        }
    }

    fun setActive(id: Long, active: Boolean) =
        viewModelScope.launch { recurringRepository.setActive(id, active) }

    fun delete(id: Long) = viewModelScope.launch { recurringRepository.delete(id) }
}
