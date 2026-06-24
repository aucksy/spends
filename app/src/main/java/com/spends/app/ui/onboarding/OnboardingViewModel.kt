package com.spends.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _salaryDay = MutableStateFlow(1)
    val salaryDay: StateFlow<Int> = _salaryDay

    fun setSalaryDay(day: Int) = _salaryDay.update { day.coerceIn(1, 31) }

    /**
     * Persist the chosen salary day immediately. Called when advancing past the salary step so that
     * an importing user (who finishes onboarding via the import flow, not [finish]) still gets their
     * salary cycle configured rather than silently left on the default.
     */
    fun persistSalaryDay() {
        viewModelScope.launch { settingsRepository.setSalaryCycleStartDay(_salaryDay.value) }
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setSalaryCycleStartDay(_salaryDay.value)
            settingsRepository.setOnboardingComplete(true)
            onDone()
        }
    }
}
