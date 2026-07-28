package com.spends.app.ui.capture

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.CaptureNotifier
import com.spends.app.data.capture.SmsDebugLog
import com.spends.app.data.demo.DemoMode
import com.spends.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SmsDebugUiState(
    val captureEnabled: Boolean = false,
    val demoMode: Boolean = false,
    val log: SmsDebugLog.Snapshot = SmsDebugLog.Snapshot(0, null, 0, emptyList()),
    /** Live, not resume-scoped: a graph failure happens while the owner is watching this screen, which
     *  is precisely what the delivery verdict tells them to sit and wait for. */
    val graphFailures: Int = 0,
)

/**
 * TEMPORARY: backs the owner-facing "SMS debug" screen. Read-only over [SmsDebugLog] (in-memory, never
 * persisted) plus the capture switch. Remove with the log.
 *
 * The Android-side facts — the SMS permission grants and whether a prompt could actually be shown — are
 * read by the SCREEN and passed in, because both can be changed in Android's settings while this screen
 * is open and must be re-read every time it comes back to the front.
 */
@HiltViewModel
class SmsDebugViewModel @Inject constructor(
    private val debugLog: SmsDebugLog,
    settingsRepository: SettingsRepository,
    captureNotifier: CaptureNotifier,
    @ApplicationContext context: Context,
) : ViewModel() {

    // Read once: flipping demo mode restarts the process (DemoMode.restartInto), so it cannot change
    // underneath this screen.
    private val demoMode = DemoMode.isEnabled(context)

    init {
        // The prompt channel is otherwise created lazily by the first prompt ever posted. Until then the
        // "Transaction detection" category does not exist in Android's settings — so an owner sent here
        // to check whether it is switched off would find nothing to look at. Creating it is idempotent
        // and never resurrects a category the owner switched off.
        captureNotifier.ensureChannel()
    }

    val state: StateFlow<SmsDebugUiState> =
        combine(
            debugLog.state,
            settingsRepository.settings,
            SmsDebugLog.ReceiverFailures.graphFailures,
        ) { log, s, failures ->
            SmsDebugUiState(
                captureEnabled = s.smsCaptureEnabled,
                demoMode = demoMode,
                log = log,
                graphFailures = failures,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmsDebugUiState(demoMode = demoMode))

    fun clear() = debugLog.clear()

    /**
     * The whole picture as plain text for the "Copy report" button — the owner pastes this straight back
     * into chat, which is the entire point of the screen.
     *
     * No redaction pass happens here, and that is a deliberate stance rather than an omission:
     * [SmsDebugLog] refuses to STORE anything it would have to redact. What reaches this method is
     * already safe to paste, which is stronger than the notification screen's redact-on-the-way-out —
     * that depends on keeping an outcome allow-list correct forever.
     */
    fun buildReport(receiveGranted: Boolean, readGranted: Boolean, promptsCanBeSeen: Boolean): String {
        val s = state.value
        val graphFailures = s.graphFailures
        val log = s.log
        return buildString {
            appendLine("SPENDS — SMS DEBUG")
            if (s.demoMode) appendLine("DEMO MODE IS ON — real bank texts are deliberately ignored")
            appendLine("Receive SMS permission: ${yesNo(receiveGranted)}")
            appendLine("Read SMS permission (Scan past SMS only): ${yesNo(readGranted)}")
            appendLine("\"Detect from bank SMS\" switch on: ${yesNo(s.captureEnabled)}")
            appendLine("Prompt can be shown: ${yesNo(promptsCanBeSeen)}")
            appendLine("App start-up failures handling an SMS: $graphFailures")
            appendLine("SMS delivered to Spends (this app run): ${log.totalReceived}")
            appendLine("Last SMS reached the app: ${log.lastReceivedAt?.let { DateUtils.formatDayTime(it) } ?: "never this app run"}")
            appendLine("Of those, from a bank we recognise (this app run): ${log.fromKnownBanks}")
            appendLine()
            appendLine("MESSAGES (${log.entries.size} kept, newest first)")
            if (log.entries.isEmpty()) appendLine("  (none)")
            log.entries.forEach { e ->
                appendLine("---")
                appendLine("  ${DateUtils.formatDayTime(e.timeMillis)}")
                appendLine("  sender  : ${e.sender}")
                appendLine("  bank    : ${e.institution ?: "(not recognised)"}")
                appendLine("  outcome : ${e.outcome}")
                e.detail?.let { appendLine("  detail  : $it") }
                appendLine("  body    : ${e.body ?: "(not kept)"}")
            }
        }
    }

    private fun yesNo(v: Boolean) = if (v) "YES" else "NO"
}

/**
 * What the empty message list means. Pure, and living here beside [smsVerdictOf] rather than inline in
 * the screen, because inline is how it came to contradict the verdict printed a few dp above it.
 *
 * It branched on `totalReceived` ALONE — the exact defect corrected in the verdict across four rounds —
 * so in two reachable states it asserted the opposite of the card above:
 *  - a graph failure records nothing and never calls `recordReceived()`, so the verdict correctly said
 *    "a fault inside Spends, not your phone" while this line said "Android isn't delivering SMS to
 *    Spends at all", which is the precise claim `ReceiverFailures` was built to prevent;
 *  - after "Clear what's recorded" the verdict correctly said "delivery was working" beside a kept
 *    timestamp, while this line said "Nothing yet".
 *
 * The lesson is the one this whole screen keeps teaching: a sentence that names a cause has to see
 * every input that bears on it, and it has to be somewhere a test can reach.
 */
fun smsEmptyStateOf(totalReceived: Int, graphFailures: Int, lastReceivedAt: Long?): String = when {
    totalReceived > 0 -> "Counted $totalReceived, but none recorded in detail yet."
    graphFailures > 0 -> "Nothing recorded — the app couldn't start up to handle what arrived. " +
        "See the line above; that's a fault inside Spends, not your phone."
    lastReceivedAt != null -> "Nothing since you cleared this. Send yourself a text — the last one " +
        "before you cleared did reach Spends, so delivery was working."
    else -> "Nothing yet. Leave the app open and send yourself any text — if this stays empty, " +
        "Android isn't delivering SMS to Spends at all, which is the answer in itself."
}

/**
 * The one line that says which link is broken. Pure so every branch is directly testable — the verdict
 * is the whole point of the screen and it must not be able to contradict the counters printed beneath it.
 *
 * Only [receiveGranted] is consulted: live capture needs RECEIVE_SMS alone. READ_SMS is used solely by
 * "Scan past SMS", and OEM permission managers (MIUI, ColorOS — the phones this app runs on) list the
 * two separately, so blaming a missing READ_SMS would send the owner to fix a permission that is not
 * the problem while capture is in fact working.
 *
 * Order matters: each check assumes the ones above it passed.
 */
fun smsVerdictOf(
    receiveGranted: Boolean,
    demoMode: Boolean,
    captureEnabled: Boolean,
    promptsCanBeSeen: Boolean,
    graphFailures: Int,
    log: SmsDebugLog.Snapshot,
): String = when {
    // First, and above the permission: in demo mode nothing else on this screen is a statement about
    // the owner's real setup, and every branch below would name a cause that isn't the cause.
    demoMode ->
        "Demo mode is on, so real bank texts are deliberately ignored — nothing here reflects your " +
            "actual setup. Turn demo mode off in Settings → Data & Trash, then try again."
    !receiveGranted ->
        "Android hasn't given Spends permission to receive SMS, so nothing can arrive. " +
            "Settings → Apps → Spends → Permissions → SMS."
    !captureEnabled ->
        "The SMS permission is granted, but the \"Detect from bank SMS\" switch is off."
    // Covers the failures so early that NOTHING could be recorded — the log itself was unobtainable, so
    // `recordReceived()` never ran either. Must sit above the branch below, which would otherwise assert
    // "Android is not delivering… nothing inside Spends can be the cause" in the one case where Spends
    // IS the cause. `lastReceivedAt == null` keeps it off the post-Clear state, where the count is zero
    // for a different reason and this sentence would claim texts arrived that this run never saw.
    graphFailures > 0 && log.totalReceived == 0 && log.lastReceivedAt == null ->
        "$graphFailures text${if (graphFailures == 1) "" else "s"} reached Spends but the app failed " +
            "to start up properly and couldn't handle ${if (graphFailures == 1) "it" else "them"}. " +
            "That's a fault inside Spends, not your phone — tell me this number."
    // Split from the branch below on lastReceivedAt, NOT on the count alone. Tapping "Clear what's
    // recorded" zeroes the count while deliberately keeping the timestamp, and the un-split version
    // then asserted "Android is not delivering" directly above a row showing when the last one arrived.
    log.totalReceived == 0 && log.lastReceivedAt == null ->
        "Not one SMS has reached Spends since this app run started. If texts have arrived on your " +
            "phone in that time, Android is not delivering them to the app — nothing inside Spends " +
            "can be the cause. Leave the app open, send yourself a text, and check this number again."
    log.totalReceived == 0 ->
        "Nothing has arrived since you cleared this screen. The last SMS before that did reach " +
            "Spends, so delivery was working — send yourself a text to confirm it still is."
    // ⭐ Keyed on EVIDENCE STILL ON SCREEN, not on the counter. Three rounds were spent moving a
    // `graphFailures > 0` test up and down this list, and each position broke something else: too high
    // it latched, because the counter never decays and one cold-start hiccup then suppressed every
    // actionable diagnosis for the rest of the run; too low the blocked-prompt branch claimed "nothing
    // is lost" while a corrupt database dropped every message. Counting APP_NOT_READY entries settles
    // both — it is true only while the failures are visible in the list beneath, and it decays with
    // "Clear what's recorded" and with the 60-entry ring exactly as the reader's own evidence does.
    log.entries.any { it.outcome == SmsDebugLog.Outcome.APP_NOT_READY } ->
        "Texts are reaching Spends, but the app cannot start up properly to handle them — the ⚠️ rows " +
            "below are ones it dropped. That's a fault inside Spends, not your phone. Tell me this."
    log.fromKnownBanks == 0 ->
        "Spends is receiving your SMS (${log.totalReceived} so far), but none came from a sender it " +
            "recognises as a bank. If a bank alert IS in the list below, its sender name has changed " +
            "and needs adding — that's a one-line fix."
    // ORDER is what protects this branch, not a guard on it. Every clause here asserts that recognised
    // bank alerts were read — false for a corrupt database delivering only personal texts, which is how
    // it once said "so nothing is lost" while every message was dropped. Both states are now taken
    // above: APP_NOT_READY rows first, then fromKnownBanks == 0. A `fromKnownBanks > 0 &&` term was
    // added here as well and removed again — it is unreachable-false, so no test could ever kill it,
    // and a guard no test can kill is indistinguishable from protection this file does not have.
    !promptsCanBeSeen ->
        "Bank alerts are arriving and being read, but your phone won't show the \"Review & Add\" " +
            "prompt — either Spends' notifications are off, or just the \"Transaction detection\" " +
            "category is. They're being put in the review queue instead, so nothing is lost."
    // Last before the all-clear: the fault is counted but no longer visible (cleared, or aged out of
    // the ring). Deliberately does NOT claim messages are being handled now — the counter carries no
    // timestamp, so a still-corrupt database reaches here too. What is true either way is that these
    // were missed.
    graphFailures > 0 ->
        "$graphFailures text${if (graphFailures == 1) "" else "s"} reached Spends while the app was " +
            "failing to start up properly, and ${if (graphFailures == 1) "it was" else "they were"} " +
            "missed. Tell me this number."
    else ->
        "SMS is reaching Spends and bank senders are being recognised. Each message below says " +
            "exactly where it stopped."
}
