package com.spends.app.core

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.capture.CaptureDraftStore
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
    private val captureDraftStore: CaptureDraftStore,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = settingsRepository.settings
        .map { MainUiState(loading = false, settings = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = MainUiState(loading = true),
        )

    // Tapping a capture-prompt notification's "Edit" (or its body) parses the SMS into an UNSAVED draft
    // and signals the nav host to open the editor on it — NOTHING is written until the user Saves (#4).
    private val _pendingCaptureDraft = MutableStateFlow(false)
    val pendingCaptureDraft: StateFlow<Boolean> = _pendingCaptureDraft

    fun handleCaptureEdit(sender: String?, body: String?, receivedAt: Long, sourceApp: String? = null) {
        if (body.isNullOrBlank()) return
        viewModelScope.launch {
            val draft = runCatching { captureRepository.draftFor(sender, body, receivedAt, sourceApp) }.getOrNull()
            if (draft != null) {
                captureDraftStore.set(draft)
                _pendingCaptureDraft.value = true
            }
        }
    }

    fun consumeCaptureDraft() {
        _pendingCaptureDraft.value = false
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
            runCatching { captureRepository.pruneLearnedOrphans() }
        }
    }
}
