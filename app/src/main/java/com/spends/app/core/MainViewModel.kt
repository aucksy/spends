package com.spends.app.core

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.RecurringRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.data.settings.SettingsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val recurringRepository: RecurringRepository,
    private val categoryRepository: CategoryRepository,
    private val captureRepository: SmsCaptureRepository,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = settingsRepository.settings
        .map { MainUiState(loading = false, settings = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = MainUiState(loading = true),
        )

    // When the "Edit" action of a capture-prompt notification is tapped, we persist the parsed SMS and
    // expose its new id so the nav host can open the editor for it.
    private val _pendingEditId = MutableStateFlow<Long?>(null)
    val pendingEditId: StateFlow<Long?> = _pendingEditId

    fun handleCaptureEdit(sender: String?, body: String?, receivedAt: Long) {
        if (body.isNullOrBlank()) return
        viewModelScope.launch {
            val id = runCatching { captureRepository.captureReturningId(sender, body, receivedAt) }.getOrNull()
            if (id != null) _pendingEditId.value = id
        }
    }

    fun consumePendingEdit() {
        _pendingEditId.value = null
    }

    // Set when the home-screen widget is tapped (#14) — the nav host opens the quick-add sheet.
    private val _pendingQuickAdd = MutableStateFlow(false)
    val pendingQuickAdd: StateFlow<Boolean> = _pendingQuickAdd

    fun requestQuickAdd() { _pendingQuickAdd.value = true }

    fun consumeQuickAdd() { _pendingQuickAdd.value = false }

    init {
        // Best-effort, idempotent launch chores. The daily WorkManager job is the backstop for both
        // when the app isn't opened for a while.
        viewModelScope.launch {
            runCatching {
                val days = settingsRepository.settings.first().trashRetentionDays
                expenseRepository.purgeTrashOlderThan(days)
            }
            runCatching { recurringRepository.materializeDue(System.currentTimeMillis()) }
            runCatching { categoryRepository.refreshAutoIcons() }
        }
    }
}
