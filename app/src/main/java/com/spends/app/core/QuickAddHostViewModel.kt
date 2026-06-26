package com.spends.app.core

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.data.settings.SettingsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Minimal state for [QuickAddActivity] — just the theme settings so the overlay matches the app's theme. */
@HiltViewModel
class QuickAddHostViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<SettingsState> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())
}
