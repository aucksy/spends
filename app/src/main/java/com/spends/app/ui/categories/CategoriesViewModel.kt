package com.spends.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.repo.CategoryDeleteResult
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.domain.model.CategoryUsage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoriesUiState(
    val expense: List<CategoryEntity> = emptyList(),
    val income: List<CategoryEntity> = emptyList(),
    val archived: List<CategoryEntity> = emptyList(),
)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    val state: StateFlow<CategoriesUiState> = categoryRepository.observeAll()
        .map { all ->
            val active = all.filterNot { it.isArchived }
            CategoriesUiState(
                expense = active.filter { it.usage == CategoryUsage.EXPENSE || it.usage == CategoryUsage.BOTH },
                income = active.filter { it.usage == CategoryUsage.INCOME || it.usage == CategoryUsage.BOTH },
                archived = all.filter { it.isArchived },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    fun add(name: String, usage: CategoryUsage) {
        viewModelScope.launch { categoryRepository.addCustom(name, usage) }
    }

    fun rename(id: Long, newName: String) {
        viewModelScope.launch { categoryRepository.rename(id, newName) }
    }

    fun deleteOrArchive(id: Long, onResult: (CategoryDeleteResult) -> Unit) {
        viewModelScope.launch { onResult(categoryRepository.deleteOrArchive(id)) }
    }

    fun restore(id: Long) {
        viewModelScope.launch { categoryRepository.setArchived(id, false) }
    }
}
