package com.spends.app.core

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.CaptureDraftStore
import com.spends.app.data.backup.SecureKeyStore
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.demo.DemoDataSeeder
import com.spends.app.data.demo.DemoMode
import com.spends.app.data.repo.CategoryRepository
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.RecurringRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.data.settings.SettingsState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Provider

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
    private val secureKeyStore: SecureKeyStore,
    // A Provider so the seeder's object graph is never built for the overwhelming majority of launches,
    // which are not in demo mode.
    private val demoDataSeeder: Provider<DemoDataSeeder>,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /**
     * True only on the very first launch after switching into demo mode, while [DemoDataSeeder] builds the
     * sample account. It feeds [MainUiState.loading], which already holds the splash on screen — so the demo
     * fills in behind the splash instead of the user watching an empty app populate itself.
     */
    private val _demoPreparing = MutableStateFlow(
        DemoMode.isEnabled(context) && DemoMode.needsSeed(context, LocalDate.now(DateUtils.ZONE).toEpochDay()),
    )

    val uiState: StateFlow<MainUiState> = combine(settingsRepository.settings, _demoPreparing) { settings, preparing ->
        MainUiState(loading = preparing, settings = settings)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = MainUiState(loading = true),
        )

    /** Build the demo account, then let the UI through. */
    private fun seedDemoData() = viewModelScope.launch {
        runCatching { demoDataSeeder.get().seed() }
        // Whatever happened, never strand the app on the splash. A failure leaves the account unseeded but
        // usable (settings are written first), and the next launch retries.
        _demoPreparing.value = false
    }

    // Tapping a capture-prompt notification's "Edit" (or its body) parses the SMS into an UNSAVED draft
    // and signals the nav host to open the editor on it — NOTHING is written until the user Saves (#4).
    private val _pendingCaptureDraft = MutableStateFlow(false)
    val pendingCaptureDraft: StateFlow<Boolean> = _pendingCaptureDraft

    fun handleCaptureEdit(sender: String?, body: String?, receivedAt: Long, sourceApp: String? = null) {
        if (body.isNullOrBlank()) return
        // Same reasoning as CaptureActionReceiver: the "Edit" action of a prompt left over from before the
        // switch would open the editor on a REAL transaction that saves into the demo sandbox.
        if (DemoMode.isEnabled(context)) return
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

    // Set when a "recurring added" notification is tapped (#3) — the nav host opens that transaction in the
    // editor. Unlike a capture draft this is a saved row, so nothing needs staging: the id is enough.
    private val _pendingOpenExpenseId = MutableStateFlow<Long?>(null)
    val pendingOpenExpenseId: StateFlow<Long?> = _pendingOpenExpenseId

    fun requestOpenExpense(id: Long) {
        // A notification left over from before the switch would open a REAL transaction inside the demo
        // sandbox, where editing it changes fabricated data and the next reset destroys the edit.
        if (DemoMode.isEnabled(context)) return
        _pendingOpenExpenseId.value = id
    }

    fun consumeOpenExpense() { _pendingOpenExpenseId.value = null }

    // Set when the home-screen widget is tapped (#14) — the nav host opens the quick-add sheet.
    private val _pendingQuickAdd = MutableStateFlow(false)
    val pendingQuickAdd: StateFlow<Boolean> = _pendingQuickAdd

    fun requestQuickAdd() { _pendingQuickAdd.value = true }

    fun consumeQuickAdd() { _pendingQuickAdd.value = false }

    init {
        // First launch in demo mode: build the sample account before the app is revealed, and before the
        // chores below. They must not run against a database the seeder is mid-way through wiping —
        // refreshAutoIcons would re-icon categories about to be replaced, and the chores read a settings
        // file the seeder is concurrently writing.
        val seedJob = if (_demoPreparing.value) seedDemoData() else null

        // Best-effort, idempotent launch chores. The daily WorkManager job is the backstop for both
        // when the app isn't opened for a while.
        viewModelScope.launch {
            seedJob?.join()
            runCatching {
                val days = settingsRepository.settings.first().trashRetentionDays
                expenseRepository.purgeTrashOlderThan(days)
            }
            runCatching { recurringRepository.materializeDue(System.currentTimeMillis()) }
            runCatching { categoryRepository.refreshAutoIcons() }
            runCatching { captureRepository.pruneLearnedOrphans() }
            // v1.65.0: the AI helper is gone, so erase the API key it needed. It is a personal credential
            // the user can no longer see or delete themselves — the screen that managed it does not exist
            // any more — so leaving it encrypted on the phone forever is not an option. Guarded on
            // presence so the overwhelming majority of launches do no write at all.
            runCatching { if (secureKeyStore.hasApiKey()) secureKeyStore.clearApiKey() }
        }
    }
}
