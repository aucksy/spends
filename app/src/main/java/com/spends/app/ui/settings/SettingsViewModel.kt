package com.spends.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.time.DateUtils
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.data.settings.SettingsState
import com.spends.app.domain.model.DefaultLanding
import com.spends.app.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<SettingsState> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    fun setAutoDarkWindow(startMinute: Int, endMinute: Int) =
        viewModelScope.launch { settingsRepository.setAutoDarkWindow(startMinute, endMinute) }
    // onSaved runs AFTER the new day is persisted, so a widget refresh re-reads the committed value and
    // shows the new cycle name/dates immediately — refreshing before the write would re-read the old day (#14).
    fun setSalaryDay(day: Int, onSaved: () -> Unit = {}) = viewModelScope.launch {
        settingsRepository.setSalaryCycleStartDay(day)
        onSaved()
    }
    fun setDefaultLanding(landing: DefaultLanding) = viewModelScope.launch { settingsRepository.setDefaultLanding(landing) }
    fun setCarryForward(value: Boolean) = viewModelScope.launch {
        settingsRepository.setCarryForwardEnabled(value)
        // Enabling needs an anchor, otherwise the running balance would fold in ALL incomplete old
        // history (the huge-negative bug). Default the anchor to today so it's valid immediately; the
        // user can move it back. Opening balance stays 0 until they set it.
        if (value) {
            val current = settingsRepository.settings.first()
            if (current.carryForwardAnchorEpochDay <= 0) {
                settingsRepository.setCarryForwardAnchor(LocalDate.now(DateUtils.ZONE).toEpochDay())
            }
        }
    }
    fun setCarryForwardAnchor(epochDay: Long) = viewModelScope.launch { settingsRepository.setCarryForwardAnchor(epochDay) }
    fun setCarryForwardOpening(minor: Long) = viewModelScope.launch { settingsRepository.setCarryForwardOpening(minor) }
    fun setTrashRetentionDays(days: Int) = viewModelScope.launch { settingsRepository.setTrashRetentionDays(days) }
    fun setSmartCycle(value: Boolean) = viewModelScope.launch { settingsRepository.setSmartCycleEnabled(value) }
    /** Persist the widget-eye setting, THEN run [onSaved] (the widget refresh) — so the refresh reads the
     *  just-committed value instead of racing the async DataStore write (#2: it wasn't updating instantly). */
    fun setWidgetEyeHidden(value: Boolean, onSaved: () -> Unit) = viewModelScope.launch {
        settingsRepository.setWidgetEyeHidden(value)
        onSaved()
    }
}
