package com.spends.app.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.spends.app.data.capture.CaptureNotifier
import com.spends.app.data.capture.LastAlertLog
import com.spends.app.data.capture.NotificationCapture
import com.spends.app.data.capture.RecentCaptureGuard
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.demo.DemoMode
import com.spends.app.data.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Notification capture (Phase 4): closes the RCS gap. Bank alerts that arrive as RCS/business chats
 * never hit the SMS receiver, but they DO post notifications in Google Messages / Truecaller — the
 * system binds this listener and hands us every posted notification, and we read ONLY the apps the
 * user ticked in Settings. Review-only like SMS capture: a parsed transaction either shows the same
 * "Review & Add / Ignore" prompt or lands silently in the `pending_captures` queue — NEVER the ledger.
 *
 * Patterns borrowed from NotDigest (battle-tested): [onListenerDisconnected] → [requestRebind]
 * self-heal when an OEM unbinds us; a shade sweep on (re)connect so alerts posted while we were dead
 * are still caught (silently queued — no prompt spam); group summaries and ongoing/foreground-service
 * notifications skipped. All content is parsed locally; nothing leaves the device.
 */
@AndroidEntryPoint
class CaptureNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var captureRepository: SmsCaptureRepository
    @Inject lateinit var captureNotifier: CaptureNotifier
    @Inject lateinit var guard: RecentCaptureGuard

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)

    // Settings snapshot for the binder-thread fast path (every notification on the device flows
    // through onNotificationPosted — the package check must not suspend).
    @Volatile private var enabled = false
    @Volatile private var watchedPackages: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate() // Hilt injects here — fields are ready after this line
        scope.launch {
            settingsRepository.settings.collect { s ->
                enabled = s.notificationCaptureEnabled
                watchedPackages = s.notificationCaptureApps
            }
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationListenerControl.setConnected(true)
        // Shade catch-up (owner-chosen): the only "history" notifications have. Whatever transaction
        // alerts are still sitting in the shade get queued for review — silently, never prompted, so
        // a reconnect can't dump a stack of heads-ups at once.
        scope.launch {
            // Demo mode: the shade sweep is the ONLY recovery path notifications have, so an alert captured
            // into the sandbox here is permanently lost when the demo resets. Skip the sweep entirely — the
            // alert stays in the shade and is picked up on the next real connect.
            if (DemoMode.isEnabled(this@CaptureNotificationListenerService)) return@launch
            val s = runCatching { settingsRepository.settings.first() }.getOrNull() ?: return@launch
            if (!s.notificationCaptureEnabled) return@launch
            val active = runCatching { activeNotifications }.getOrNull() ?: return@launch
            active.forEach { sbn ->
                if (sbn.packageName !in s.notificationCaptureApps) return@forEach
                if (!looksReadable(sbn)) return@forEach
                val payload = payloadOf(sbn)
                candidatesOf(payload).forEach { c ->
                    // The sweep gets the guard's full 7-day window (not the 72h live gate): the shade
                    // is bounded + user-visible, and it's the ONLY recovery path notifications have.
                    runCatching {
                        process(c, sbn.packageName, canPrompt = false, maxAgeMillis = RecentCaptureGuard.MESSAGE_TTL_MILLIS)
                    }
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationListenerControl.setConnected(false)
        // Self-heal (NotDigest pattern): the system unbound us (memory pressure / OEM killer). Ask to
        // be rebound instead of staying dead until the app is next opened. No-op if the user actually
        // revoked notification access.
        runCatching {
            requestRebind(ComponentName(this, CaptureNotificationListenerService::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!enabled) return
        // Same reasoning as SmsReceiver: while demo mode is on the app writes to the demo database, so a real
        // bank alert captured here would be wiped by the next demo reset. Ignore everything, record nothing.
        if (DemoMode.isEnabled(this)) return
        if (sbn.packageName !in watchedPackages) return
        val pkg = sbn.packageName
        if (!looksReadable(sbn)) return
        val payload = payloadOf(sbn)
        val candidates = candidatesOf(payload) // extras snapshot, cheap; allowlist filters here too
        if (candidates.isEmpty()) {
            // The "last alert" line under this app in Settings: say why a money-looking alert was dropped.
            val texts = arrayOf(payload.text, payload.bigText) + payload.messages.map { it.text }
            LastAlertLog.reasonWithoutCandidate(*texts)?.let { reason ->
                LastAlertLog.record(this, pkg, reason, payload.conversationTitle ?: payload.title)
            }
            return
        }
        scope.launch {
            candidates.forEach { c -> runCatching { process(c, pkg, canPrompt = true) } }
        }
    }

    private fun reasonFor(c: NotificationCapture.Candidate, d: SmsCaptureRepository.NotificationDecision) = when {
        LastAlertLog.looksHidden(c.body) -> LastAlertLog.Reason.HIDDEN_BY_ANDROID
        d == SmsCaptureRepository.NotificationDecision.NOT_TRANSACTION -> LastAlertLog.Reason.NO_AMOUNT
        d == SmsCaptureRepository.NotificationDecision.DUPLICATE -> LastAlertLog.Reason.ALREADY_IN_SPENDS
        d == SmsCaptureRepository.NotificationDecision.QUEUED -> LastAlertLog.Reason.ADDED_TO_REVIEW
        else -> LastAlertLog.Reason.ASKED_YOU
    }

    /** Skip our own notifications, group summaries ("2 new messages" carries no content of its own),
     *  and anything ongoing / foreground-service — none of those are a bank alert. */
    private fun looksReadable(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        if (!sbn.isClearable) return false
        val flags = sbn.notification.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        return true
    }

    /**
     * Snapshot the notification's text payload once. A messaging notification carries a MessagingStyle
     * list of the conversation's recent messages (each with its own sender + stable timestamp — and the
     * FULL text, unlike the possibly-truncated collapsed line); plain notifications fall back to
     * title + bigText/text.
     */
    private fun payloadOf(sbn: StatusBarNotification): Payload = runCatching {
        val extras = sbn.notification.extras
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
        Payload(
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            conversationTitle = style?.conversationTitle?.toString(),
            messages = style?.messages.orEmpty().map { m ->
                NotificationCapture.RawMessage(
                    sender = m.person?.name?.toString(),
                    text = m.text?.toString(),
                    timestamp = m.timestamp,
                )
            },
            postTime = sbn.postTime,
        )
    }.getOrElse { Payload(null, null, null, null, emptyList(), sbn.postTime) }

    /** Sender filtering (only tracked financial senders survive) happens inside [NotificationCapture]. */
    private fun candidatesOf(p: Payload): List<NotificationCapture.Candidate> = runCatching {
        NotificationCapture.candidates(p.title, p.text, p.bigText, p.conversationTitle, p.messages, p.postTime)
    }.getOrDefault(emptyList())

    /** One notification's text payload, read once. */
    private data class Payload(
        val title: String?,
        val text: String?,
        val bigText: String?,
        val conversationTitle: String?,
        val messages: List<NotificationCapture.RawMessage>,
        val postTime: Long,
    )

    /**
     * Handle one candidate message. Layered guards, in order:
     *  - too-old filter: a conversation notification reposts messages for days; anything older than
     *    [maxAgeMillis] (72h live, 7d for the shade sweep) is never (re)captured — with the matching
     *    message-guard TTL this also keeps a REJECTED capture from riding back in on a later repost;
     *  - repost guard: each (app, sender, body) processed once per guard window;
     *  - the repository's dedupe stack (exact hash vs ledger + queue, relaxed twin nets);
     *  - prompt claim: the SMS twin of this alert prompts once, not twice (claimed by both paths;
     *    ref-loss twins collide on the relaxed hash);
     *  - blocked-notifications fallback: a PROMPT that can't be shown (POST_NOTIFICATIONS denied)
     *    queues silently instead of evaporating — for an RCS-only alert there is no other chance.
     */
    private suspend fun process(
        c: NotificationCapture.Candidate,
        pkg: String,
        canPrompt: Boolean,
        maxAgeMillis: Long = MAX_LIVE_AGE_MILLIS,
    ) {
        if (System.currentTimeMillis() - c.timestamp > maxAgeMillis) return
        if (!guard.checkAndMark(guard.messageKey(pkg, c.sender, c.body), RecentCaptureGuard.MESSAGE_TTL_MILLIS)) {
            return
        }
        val outcome = captureRepository.handleNotificationAlert(c.sender, c.body, c.timestamp, pkg, canPrompt)
        LastAlertLog.record(this, pkg, reasonFor(c, outcome.decision), c.sender)
        if (outcome.decision != SmsCaptureRepository.NotificationDecision.PROMPT) return
        val preview = outcome.preview ?: return
        if (guard.claimPrompt(preview.relaxedHash, preview.refNumber)) {
            // The blocked-prompt fallback now hangs off postCapturePrompt's return value rather than a
            // separate areNotificationsEnabled() pre-check. That pre-check only saw the app's MASTER
            // notification toggle, so a prompt hidden by the "Transaction detection" category alone was
            // handed to Android, silently binned, and lost — the same hole the SMS path had. The notifier
            // now reports whether the prompt could actually be seen, and both paths queue when it can't.
            if (!captureNotifier.postCapturePrompt(c.sender, c.body, c.timestamp, preview, sourceApp = pkg)) {
                // queueForReview's own dedupe nets may refuse the row; either way nothing is lost.
                captureRepository.queueForReview(c.sender, c.body, c.timestamp, pkg)
            }
        }
        // A dropped prompt means a twin of this transaction (the SMS, or on an RCS-only alert another
        // notification) already claimed it; every write path re-checks the exact + relaxed hashes, so
        // nothing can double-add regardless.
    }

    companion object {
        /** Live capture only looks this far back; older messages in a repost are stale context. */
        private const val MAX_LIVE_AGE_MILLIS: Long = 72 * 60 * 60 * 1000
    }
}
