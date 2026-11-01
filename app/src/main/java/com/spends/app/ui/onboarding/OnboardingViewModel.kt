package com.spends.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** The answer on the salary step. */
sealed interface SalaryChoice {
    /** The day the last salary reached the bank (today or earlier). */
    data class OnDate(val date: LocalDate) : SalaryChoice
    data object NotYet : SalaryChoice
    data object NoSalary : SalaryChoice
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val captureRepository: SmsCaptureRepository,
) : ViewModel() {

    private val _salaryChoice = MutableStateFlow<SalaryChoice?>(null)
    val salaryChoice: StateFlow<SalaryChoice?> = _salaryChoice

    fun setSalaryChoice(choice: SalaryChoice) = _salaryChoice.update { choice }

    /**
     * Turn on live SMS capture from the onboarding permission step, and optionally queue the last
     * [months] months of past SMS into the review queue (nothing is auto-added — it lands in the
     * review queue). Called only after the SMS permission is granted.
     */
    fun enableCaptureAndScan(scanPast: Boolean, months: Int = 3) {
        viewModelScope.launch {
            settingsRepository.setSmsCaptureEnabled(true)
            if (scanPast) {
                val today = LocalDate.now(DateUtils.ZONE)
                val firstMonth = YearMonth.from(today).minusMonths((months - 1).toLong())
                val start = DateUtils.startOfDayMillis(firstMonth.atDay(1))
                val endExclusive = DateUtils.startOfDayMillis(YearMonth.from(today).plusMonths(1).atDay(1))
                runCatching { captureRepository.scanHistory(start, endExclusive) }
            }
        }
    }

    /**
     * Persist the chosen salary day immediately. Called when advancing past the salary step so that
     * an importing user (who finishes onboarding via the import flow, not [finish]) still gets their
     * salary cycle configured rather than silently left on the default.
     */
    fun persistSalaryDay() {
        viewModelScope.launch { saveSalaryChoice() }
    }

    /**
     * A picked date sets the usual day. "Hasn't come yet" runs on calendar months until the first salary
     * arrives, which then sets the day by itself (SalaryCalendar). "No monthly salary" stays on calendar
     * months and does not follow salary credits.
     */
    private suspend fun saveSalaryChoice() {
        when (val c = _salaryChoice.value) {
            is SalaryChoice.OnDate -> {
                settingsRepository.setSalaryCycleStartDay(c.date.dayOfMonth)
                settingsRepository.setSalaryDayPending(false)
                settingsRepository.setFollowSalaryDate(true)
            }
            SalaryChoice.NotYet -> {
                settingsRepository.setSalaryCycleStartDay(1)
                settingsRepository.setSalaryDayPending(true)
                settingsRepository.setFollowSalaryDate(true)
            }
            SalaryChoice.NoSalary -> {
                settingsRepository.setSalaryCycleStartDay(1)
                settingsRepository.setSalaryDayPending(false)
                settingsRepository.setFollowSalaryDate(false)
            }
            null -> Unit
        }
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            saveSalaryChoice()
            settingsRepository.setOnboardingComplete(true)
            onDone()
        }
    }
}
