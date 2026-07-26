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
import kotlinx.coroutines.flow.onStart
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
        }
            // The log skips publishing while nobody is watching, so pull a fresh snapshot on subscribe.
            .onStart { debugLog.refresh() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationDebugUiState())

    fun clear() = debugLog.clear()

    /**
     * The whole picture as plain text, for the "Copy report" button — the owner pastes this straight
     * back into chat, which is the entire point of the screen.
     *
     * **Redaction.** The on-screen view shows everything (it never leaves the phone), but this report
     * does. A watched app is Google Messages, so an entry whose sender did NOT resolve to a tracked
     * bank is very likely a PERSONAL message — its body is replaced by a length marker here. Diagnosing
     * those cases only needs the sender strings that were tried, which are kept in full. Entries that
     * DID resolve to a bank are genuine bank alerts, so their text is exported intact — that's what
     * makes a parse failure diagnosable.
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
                val redact = e.outcome in REDACTED_OUTCOMES
                appendLine("---")
                appendLine("  ${DateUtils.formatDayTime(e.timeMillis)} · ${e.packageName}")
                appendLine("  outcome : ${e.outcome}")
                e.detail?.let { appendLine("  detail  : $it") }
                appendLine("  title   : ${e.title ?: "(none)"}")
                appendLine("  text    : ${body(e.text, redact)}")
                appendLine("  bigText : ${body(e.bigText, redact)}")
                appendLine("  senders : ${e.messageSenders.joinToString(" | ").ifBlank { "(no messaging style)" }}")
            }
        }
    }

    private fun body(value: String?, redact: Boolean): String = when {
        value == null -> "(none)"
        redact -> "(withheld — ${value.length} chars; sender didn't match a bank, so this may be a personal message)"
        else -> value
    }

    private fun yesNo(v: Boolean) = if (v) "YES" else "NO"

    private companion object {
        /** Outcomes reached WITHOUT the sender resolving to a tracked bank — body withheld from export. */
        val REDACTED_OUTCOMES = setOf(
            NotificationDebugLog.Outcome.SENDER_NOT_RECOGNISED,
            NotificationDebugLog.Outcome.NO_READABLE_TEXT,
            NotificationDebugLog.Outcome.MESSAGES_SHADOWED_BIG_TEXT,
            NotificationDebugLog.Outcome.SKIPPED_SHAPE,
        )
    }
}
