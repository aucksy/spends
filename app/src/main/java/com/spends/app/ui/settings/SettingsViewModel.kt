package com.spends.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.data.settings.SettingsState
import com.spends.app.domain.model.DefaultLanding
import com.spends.app.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<SettingsState> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    fun setDynamicColor(value: Boolean) = viewModelScope.launch { settingsRepository.setDynamicColor(value) }
    fun setSalaryDay(day: Int) = viewModelScope.launch { settingsRepository.setSalaryCycleStartDay(day) }
    fun setDefaultLanding(landing: DefaultLanding) = viewModelScope.launch { settingsRepository.setDefaultLanding(landing) }
    fun setCarryForward(value: Boolean) = viewModelScope.launch { settingsRepository.setCarryForwardEnabled(value) }
    fun setTrashRetentionDays(days: Int) = viewModelScope.launch { settingsRepository.setTrashRetentionDays(days) }
}
