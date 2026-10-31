package com.spends.app.data.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.spends.app.data.demo.DemoMode
import com.spends.app.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one review-only path a new bank SMS takes, shared by the live receiver and the catch-up sweep, so
 * the two can never drift apart. Every outcome is written to [LastAlertLog] under [LastAlertLog.SMS_KEY]:
 * since the SMS debug screen went in v1.74.0 the SMS route left no trace, and two missed alerts on
 * 31 Oct 2026 could not be explained from the phone.
 *
 * Why a sweep at all: Android delivers SMS_RECEIVED only to an app it has not stopped, and phone battery
 * managers (Vivo's above all) stop apps in the background. The text still lands in the inbox — which is
 * why "Scan past SMS" found what the live path missed. The sweep reads the inbox for texts the receiver
 * never got ([SmsSeen]) and puts them through exactly the same steps, so a miss becomes a late prompt.
 */
@Singleton
class SmsIntake @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capture: SmsCaptureRepository,
    private val settings: SettingsRepository,
    private val notifier: CaptureNotifier,
    private val guard: RecentCaptureGuard,
) {
    private val sweepMutex = Mutex()

    /**
     * Put one text through the review-only chain. [late] = found by the sweep, not delivered live.
     * [mayPrompt] = false sends a parseable text straight to the review queue (a sweep that found many).
     */
    suspend fun handle(sender: String?, body: String, receivedAt: Long, late: Boolean, mayPrompt: Boolean = true) {
        // Review-only: never auto-add. A parseable bank SMS always prompts the user (Add/Edit/Ignore).
        if (!settings.settings.first().smsCaptureEnabled) return
        val preview = capture.preview(sender, body, receivedAt)
        if (preview == null) {
            // Only a known bank's money-looking text earns a line; an OTP from a shop must not overwrite
            // the line a real bank alert left behind.
            if (SenderAllowlist.lookup(sender) != null && LastAlertLog.looksLikeMoney(body)) {
                record(LastAlertLog.Reason.NOT_A_PAYMENT, sender)
            }
            return
        }
        // #7: if the user has ignored this exact pattern enough times, stop nagging — drop it
        // silently into the review queue instead, so it's reviewable but never lost.
        if (capture.isPatternSuppressed(sender, body, receivedAt)) {
            val queued = capture.queueForReview(sender, body, receivedAt)
            record(if (queued != null) LastAlertLog.Reason.ADDED_TO_REVIEW else LastAlertLog.Reason.ALREADY_IN_SPENDS, sender)
        } else if (capture.isKnownHash(preview.dedupeHash)) {
            // Already in the ledger or the review queue (e.g. the notification twin of
            // this alert got there first, Phase 4) — a prompt would only invite a
            // double-add attempt the hash guards would then have to swallow.
            record(LastAlertLog.Reason.ALREADY_IN_SPENDS, sender)
        } else if (!mayPrompt) {
            val queued = capture.queueForReview(sender, body, receivedAt)
            record(if (queued != null) LastAlertLog.Reason.FOUND_LATE_TO_REVIEW else LastAlertLog.Reason.ALREADY_IN_SPENDS, sender)
        } else if (guard.claimPrompt(preview.relaxedHash, preview.refNumber)) {
            if (notifier.postCapturePrompt(sender, body, receivedAt, preview)) {
                record(if (late) LastAlertLog.Reason.FOUND_LATE else LastAlertLog.Reason.ASKED_YOU, sender)
            } else {
                // The phone will not show the prompt — the app's notifications are off, or the
                // "Transaction detection" category alone was switched off. Park it in the review
                // queue, exactly as the notification listener does, so a real transaction is
                // never lost in silence.
                capture.queueForReview(sender, body, receivedAt)
                record(LastAlertLog.Reason.PROMPT_BLOCKED, sender)
            }
        } else {
            // A TWIN of this transaction already claimed the prompt — the SMS and notification
            // twins of one alert (even when one lost the reference number), or the same SMS delivered
            // twice by the phone.
            record(LastAlertLog.Reason.ASKED_YOU, sender)
        }
    }

    /**
     * Read the inbox for texts the live receiver never got and handle each one. Safe to call often (app
     * open, the periodic worker): fingerprints make every text count once. Returns how many it handled.
     */
    suspend fun catchUp(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        // Demo mode: the app points at the demo database — same refusal as the receiver and the scan.
        if (DemoMode.isEnabled(context)) return@withContext 0
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return@withContext 0
        sweepMutex.withLock {
            val (start, end) = SmsSeen.window(SmsSeen.lastSweepAt(context), now)
            if (!settings.settings.first().smsCaptureEnabled) {
                // Off: move the mark forward so turning capture on later does not replay old texts as
                // "missed" — that is what "Scan past SMS" is for.
                SmsSeen.setLastSweepAt(context, end)
                return@withLock 0
            }
            val found = readInbox(start, end) ?: return@withLock 0 // could not read: keep the mark, retry later
            var handled = 0
            for ((sender, body, date) in found) {
                if (!SmsSeen.claim(context, body)) continue
                runCatching { handle(sender, body, date, late = true, mayPrompt = handled < MAX_LATE_PROMPTS) }
                    .onSuccess { handled++ }
            }
            SmsSeen.setLastSweepAt(context, end)
            handled
        }
    }

    private data class InboxText(val sender: String?, val body: String, val date: Long)

    /** Oldest first, so late prompts appear in the order the money moved. Null when the inbox can't be read. */
    private fun readInbox(start: Long, end: Long): List<InboxText>? = runCatching {
        val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ?"
        val args = arrayOf(start.toString(), end.toString())
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, cols, selection, args, "${Telephony.Sms.DATE} ASC")
            ?.use { c ->
                val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndex(Telephony.Sms.BODY)
                val iDate = c.getColumnIndex(Telephony.Sms.DATE)
                val out = ArrayList<InboxText>()
                while (c.moveToNext() && out.size < MAX_TEXTS_PER_SWEEP) {
                    val body = (if (iBody >= 0) c.getString(iBody) else null)?.takeIf { it.isNotBlank() } ?: continue
                    val sender = if (iAddr >= 0) c.getString(iAddr) else null
                    // Cheap filter first: only a known bank's text goes further (and gets a fingerprint).
                    if (SenderAllowlist.lookup(sender) == null) continue
                    out.add(InboxText(sender, body, if (iDate >= 0) c.getLong(iDate) else end))
                }
                out
            }
    }.getOrNull()

    private fun record(reason: LastAlertLog.Reason, sender: String?) =
        LastAlertLog.record(context, LastAlertLog.SMS_KEY, reason, SenderAllowlist.lookup(sender)?.name ?: sender)

    companion object {
        /** More missed texts than this in one sweep go to the review queue quietly instead of one prompt each. */
        const val MAX_LATE_PROMPTS = 5
        private const val MAX_TEXTS_PER_SWEEP = 500
    }
}
