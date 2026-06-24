package com.spends.app.core

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.data.settings.SettingsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val loading: Boolean = true,
    val settings: SettingsState = SettingsState(),
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val expenseRepository: ExpenseRepository,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = settingsRepository.settings
        .map { MainUiState(loading = false, settings = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = MainUiState(loading = true),
        )

    init {
        // Best-effort Trash auto-purge on launch (idempotent; the daily WorkManager backstop lands
        // with the recurring/backup phases).
        viewModelScope.launch {
            runCatching {
                val days = settingsRepository.settings.first().trashRetentionDays
                expenseRepository.purgeTrashOlderThan(days)
            }
        }
    }
}
