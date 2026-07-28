package com.spends.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.SmsDebugLog
import com.spends.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SmsDebugUiState(
    val captureEnabled: Boolean = false,
    val log: SmsDebugLog.Snapshot = SmsDebugLog.Snapshot(0, null, 0, emptyList()),
)

/**
 * TEMPORARY: backs the owner-facing "SMS debug" screen. Read-only over [SmsDebugLog] (in-memory, never
 * persisted) plus the capture switch. Remove with the log.
 *
 * The Android-side facts — the SMS permission grant and whether a prompt could actually be shown — are
 * read by the SCREEN and passed in, because both can be changed in Android's settings while this screen
 * is open and must be re-read every time it comes back to the front.
 */
@HiltViewModel
class SmsDebugViewModel @Inject constructor(
    private val debugLog: SmsDebugLog,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<SmsDebugUiState> =
        combine(debugLog.state, settingsRepository.settings) { log, s ->
            SmsDebugUiState(captureEnabled = s.smsCaptureEnabled, log = log)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmsDebugUiState())

    fun clear() = debugLog.clear()

    /**
     * The whole picture as plain text for the "Copy report" button — the owner pastes this straight back
     * into chat, which is the entire point of the screen.
     *
     * No redaction pass is needed here: [SmsDebugLog] refuses to store a message body unless its sender
     * resolved to a tracked bank, and masks senders that carry no letters. What reaches this method is
     * already safe to paste, which is a stronger guarantee than redacting on the way out.
     */
    fun buildReport(smsPermissionGranted: Boolean, promptsCanBeSeen: Boolean): String {
        val s = state.value
        val log = s.log
        return buildString {
            appendLine("SPENDS — SMS DEBUG")
            appendLine("SMS permission granted: ${yesNo(smsPermissionGranted)}")
            appendLine("\"Detect from bank SMS\" switch on: ${yesNo(s.captureEnabled)}")
            appendLine("Prompt can be shown: ${yesNo(promptsCanBeSeen)}")
            appendLine("SMS delivered to Spends (this session): ${log.totalReceived}")
            appendLine("Last SMS reached the app: ${log.lastReceivedAt?.let { DateUtils.formatDayTime(it) } ?: "never this session"}")
            appendLine("Of those, from a bank we recognise: ${log.fromKnownBanks}")
            appendLine()
            appendLine("MESSAGES (${log.entries.size}, newest first)")
            if (log.entries.isEmpty()) appendLine("  (none)")
            log.entries.forEach { e ->
                appendLine("---")
                appendLine("  ${DateUtils.formatDayTime(e.timeMillis)}")
                appendLine("  sender  : ${e.sender}")
                appendLine("  bank    : ${e.institution ?: "(not recognised)"}")
                appendLine("  outcome : ${e.outcome}")
                e.detail?.let { appendLine("  detail  : $it") }
                appendLine("  body    : ${e.body ?: "(withheld — sender isn't a known bank, so this may be personal)"}")
            }
        }
    }

    private fun yesNo(v: Boolean) = if (v) "YES" else "NO"
}

/**
 * The one line that says which link is broken. Pure so every branch is directly testable — the verdict is
 * the whole point of the screen and it must not be able to contradict the counters printed beneath it.
 *
 * Order matters: each check assumes the ones above it passed.
 */
fun smsVerdictOf(
    permissionGranted: Boolean,
    captureEnabled: Boolean,
    promptsCanBeSeen: Boolean,
    log: SmsDebugLog.Snapshot,
): String = when {
    !permissionGranted ->
        "Android hasn't given Spends permission to read SMS, so nothing can arrive. " +
            "Settings → Apps → Spends → Permissions → SMS."
    !captureEnabled ->
        "The SMS permission is granted, but the \"Detect from bank SMS\" switch is off."
    log.totalReceived == 0 ->
        "Not one SMS has reached Spends since the app started. If texts have arrived on your phone " +
            "in that time, Android is not delivering them to the app — nothing inside Spends can be " +
            "the cause. Open the app, then send yourself a text and check this number again."
    log.fromKnownBanks == 0 ->
        "Spends is receiving your SMS (${log.totalReceived} so far), but none came from a sender it " +
            "recognises as a bank. If a bank alert IS in the list below, its sender name has changed " +
            "and needs adding — that's a one-line fix."
    !promptsCanBeSeen ->
        "Bank alerts are arriving and being read, but your phone won't show the \"Review & Add\" " +
            "prompt — either Spends' notifications are off, or just the \"Transaction detection\" " +
            "category is. They're being put in the review queue instead, so nothing is lost."
    else ->
        "SMS is reaching Spends and bank senders are being recognised. Each message below says " +
            "exactly where it stopped."
}
