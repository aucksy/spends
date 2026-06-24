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

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setSalaryCycleStartDay(_salaryDay.value)
            settingsRepository.setOnboardingComplete(true)
            onDone()
        }
    }
}
