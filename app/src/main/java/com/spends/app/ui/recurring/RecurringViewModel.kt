package com.spends.app.ui.recurring

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.RecurringRuleEntity
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.repo.RecurringInput
import com.spends.app.data.repo.RecurringRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.data.settings.SettingsState
import com.spends.app.ui.cards.PaymentState
import com.spends.app.ui.cards.toCardOption
import com.spends.app.work.RecurringAlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecurringViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recurringRepository: RecurringRepository,
    private val settingsRepository: SettingsRepository,
    paymentMethodRepository: PaymentMethodRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    val rules: StateFlow<List<RecurringRuleEntity>> = recurringRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeActiveByUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "Paid with" for the recurring editor (#6) — the feature flag + the available instruments. */
    val paymentState: StateFlow<PaymentState> =
        combine(settingsRepository.settings, paymentMethodRepository.observeConfirmed()) { s, cards ->
            PaymentState(s.smartCycleEnabled, cards.map { it.toCardOption() })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaymentState())

    /** Recurring-notification prefs (#15): whether to notify when the daily worker adds rules, and at what time. */
    val settings: StateFlow<SettingsState> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun setNotifyEnabled(value: Boolean) = viewModelScope.launch {
        settingsRepository.setRecurringNotifyEnabled(value)
    }

    /** Change the daily recurring-add/notify time and re-arm the exact alarm to the next occurrence of it. */
    fun setNotifyTime(minuteOfDay: Int) = viewModelScope.launch {
        settingsRepository.setRecurringNotifyTime(minuteOfDay)
        RecurringAlarmScheduler.schedule(context, minuteOfDay)
    }

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
