package com.spends.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.NotificationCaptureApps
import com.spends.app.data.capture.NotificationDebugLog
import com.spends.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class NotificationDebugUiState(
    val captureEnabled: Boolean = false,
    val watchedApps: Set<String> = emptySet(),
    val log: NotificationDebugLog.Snapshot =
        NotificationDebugLog.Snapshot(false, null, 0, emptyList(), emptyList()),
)

/**
 * TEMPORARY: backs the owner-facing "Notification debug" screen. Read-only over
 * [NotificationDebugLog] (in-memory, never persisted) plus the capture settings. Remove with the log.
 */
@HiltViewModel
class NotificationDebugViewModel @Inject constructor(
    private val debugLog: NotificationDebugLog,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<NotificationDebugUiState> =
        combine(debugLog.state, settingsRepository.settings) { log, s ->
            NotificationDebugUiState(
                captureEnabled = s.notificationCaptureEnabled,
                watchedApps = s.notificationCaptureApps,
                log = log,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationDebugUiState())

    fun clear() = debugLog.clear()

    /**
     * The whole picture as plain text, for the "Copy report" button — the owner pastes this straight
     * back into chat, which is the entire point of the screen.
     */
    fun buildReport(accessGranted: Boolean): String {
        val s = state.value
        val log = s.log
        return buildString {
            appendLine("SPENDS — NOTIFICATION DEBUG")
            appendLine("Notification access granted: ${yesNo(accessGranted)}")
            appendLine("Reader connected: ${yesNo(log.connected)}")
            appendLine("Last connected: ${log.lastConnectedAt?.let { DateUtils.formatDayTime(it) } ?: "never this session"}")
            appendLine("Capture switch on: ${yesNo(s.captureEnabled)}")
            appendLine(
                "Watching: " + (
                    s.watchedApps.joinToString(", ") { NotificationCaptureApps.displayName(it) ?: it }
                        .ifBlank { "(nothing ticked)" }
                    ),
            )
            appendLine("Notifications seen (all apps): ${log.totalSeen}")
            appendLine()
            appendLine("APPS THAT POSTED NOTIFICATIONS (${log.packageCounts.size})")
            if (log.packageCounts.isEmpty()) {
                appendLine("  (none)")
            } else {
                log.packageCounts.forEach { (pkg, n) -> appendLine("  $n × $pkg") }
            }
            appendLine()
            appendLine("WATCHED-APP EVENTS (${log.entries.size}, newest first)")
            if (log.entries.isEmpty()) appendLine("  (none)")
            log.entries.forEach { e ->
                appendLine("---")
                appendLine("  ${DateUtils.formatDayTime(e.timeMillis)} · ${e.packageName}")
                appendLine("  outcome : ${e.outcome}")
                e.detail?.let { appendLine("  detail  : $it") }
                appendLine("  title   : ${e.title ?: "(none)"}")
                appendLine("  text    : ${e.text ?: "(none)"}")
                appendLine("  bigText : ${e.bigText ?: "(none)"}")
                appendLine("  senders : ${e.messageSenders.joinToString(" | ").ifBlank { "(no messaging style)" }}")
            }
        }
    }

    private fun yesNo(v: Boolean) = if (v) "YES" else "NO"
}
