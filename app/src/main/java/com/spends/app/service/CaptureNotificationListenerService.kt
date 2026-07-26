package com.spends.app.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.spends.app.data.capture.CaptureNotifier
import com.spends.app.data.capture.NotificationCapture
import com.spends.app.data.capture.NotificationDebugLog
import com.spends.app.data.capture.RecentCaptureGuard
import com.spends.app.data.capture.SmsCaptureRepository
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

    /** TEMPORARY: records what we saw + where it stopped, for the owner-facing debug screen. */
    @Inject lateinit var debugLog: NotificationDebugLog

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
        debugLog.setConnected(true)
        // Shade catch-up (owner-chosen): the only "history" notifications have. Whatever transaction
        // alerts are still sitting in the shade get queued for review — silently, never prompted, so
        // a reconnect can't dump a stack of heads-ups at once.
        scope.launch {
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
                        process(c, sbn.packageName, payload, canPrompt = false, maxAgeMillis = RecentCaptureGuard.MESSAGE_TTL_MILLIS)
                    }
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationListenerControl.setConnected(false)
        debugLog.setConnected(false)
        // Self-heal (NotDigest pattern): the system unbound us (memory pressure / OEM killer). Ask to
        // be rebound instead of staying dead until the app is next opened. No-op if the user actually
        // revoked notification access.
        runCatching {
            requestRebind(ComponentName(this, CaptureNotificationListenerService::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!enabled) return
        // Counted for EVERY app the listener is handed, package name only, no content (TEMPORARY
        // diagnostic): if this stays at zero the listener isn't bound at all, which no other signal on
        // the phone reveals. Gated on `enabled` so an owner who never turned capture on is never tallied.
        debugLog.recordSeen(sbn.packageName)
        if (sbn.packageName !in watchedPackages) return
        val pkg = sbn.packageName
        if (!looksReadable(sbn)) {
            // Every diagnostic read is wrapped: this runs on the main looper inside a system callback,
            // where an unparcelable Bundle would otherwise take the whole process down.
            runCatching { debugLog.record(shapeSkipEntry(pkg, sbn)) }
            return
        }
        val payload = payloadOf(sbn)
        val candidates = candidatesOf(payload) // extras snapshot, cheap; allowlist filters here too
        if (candidates.isEmpty()) {
            runCatching { debugLog.record(rejectionEntry(pkg, payload)) }
            return
        }
        scope.launch {
            candidates.forEach { c -> runCatching { process(c, pkg, payload, canPrompt = true) } }
        }
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
     * title + bigText/text. Read once here so [candidatesOf] and the debug entry share it.
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

    /** One notification's text payload, read once and reused for both capture and the debug entry. */
    private data class Payload(
        val title: String?,
        val text: String?,
        val bigText: String?,
        val conversationTitle: String?,
        val messages: List<NotificationCapture.RawMessage>,
        val postTime: Long,
    )

    // ---- TEMPORARY diagnostic entry builders (remove with NotificationDebugLog) ----

    /** Skipped on shape alone — read title/text only, never the (costlier) MessagingStyle. */
    private fun shapeSkipEntry(pkg: String, sbn: StatusBarNotification): NotificationDebugLog.Entry {
        val flags = sbn.notification.flags
        // Same order as looksReadable(), so the reason shown is the one that actually rejected it.
        val why = when {
            !sbn.isClearable -> "not clearable"
            flags and Notification.FLAG_GROUP_SUMMARY != 0 -> "group summary"
            flags and Notification.FLAG_ONGOING_EVENT != 0 -> "ongoing"
            else -> "foreground service"
        }
        val extras = runCatching { sbn.notification.extras }.getOrNull()
        return NotificationDebugLog.Entry(
            timeMillis = sbn.postTime,
            packageName = pkg,
            title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.clip(),
            text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.clip(),
            bigText = null,
            messageSenders = emptyList(),
            outcome = NotificationDebugLog.Outcome.SKIPPED_SHAPE,
            detail = why,
        )
    }

    /** Nothing survived sender/text filtering — say which, and which sender strings were tried. */
    private fun rejectionEntry(pkg: String, p: Payload): NotificationDebugLog.Entry {
        val d = NotificationCapture.diagnose(p.title, p.text, p.bigText, p.conversationTitle, p.messages, p.postTime)
        val outcome = when (d.rejection) {
            NotificationCapture.Rejection.NO_READABLE_TEXT -> NotificationDebugLog.Outcome.NO_READABLE_TEXT
            NotificationCapture.Rejection.MESSAGES_SHADOWED_BIG_TEXT ->
                NotificationDebugLog.Outcome.MESSAGES_SHADOWED_BIG_TEXT
            else -> NotificationDebugLog.Outcome.SENDER_NOT_RECOGNISED
        }
        return debugEntry(
            pkg, p, outcome,
            detail = d.sendersTried.takeIf { it.isNotEmpty() }?.joinToString(" | ") { it.clip() },
        )
    }

    private fun debugEntry(
        pkg: String,
        p: Payload,
        outcome: NotificationDebugLog.Outcome,
        detail: String?,
        timeMillis: Long = p.postTime,
    ): NotificationDebugLog.Entry = NotificationDebugLog.Entry(
        timeMillis = timeMillis,
        packageName = pkg,
        title = p.title?.clip(),
        text = p.text?.clip(),
        bigText = p.bigText?.clip(),
        messageSenders = p.messages.mapNotNull { it.sender?.takeIf { s -> s.isNotBlank() }?.clip() }.distinct(),
        outcome = outcome,
        detail = detail,
    )

    private fun String.clip(): String = if (length <= MAX_DEBUG_CHARS) this else take(MAX_DEBUG_CHARS) + "…"

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
        payload: Payload,
        canPrompt: Boolean,
        maxAgeMillis: Long = MAX_LIVE_AGE_MILLIS,
    ) {
        // Every `return` below also records where this message stopped (TEMPORARY diagnostic). Stamped
        // with the MESSAGE's own time and body, not the notification's — a repost carries days-old
        // messages, and "too old" next to today's timestamp with no body is indistinguishable from a
        // live alert being wrongly rejected. Safe to include the body here: the sender already resolved
        // to a tracked bank, so this is a bank alert, never a personal chat.
        fun note(outcome: NotificationDebugLog.Outcome, extra: String? = null) = runCatching {
            debugLog.record(
                debugEntry(
                    pkg, payload, outcome,
                    detail = "read as: ${c.sender}" + (extra?.let { " · $it" } ?: "") + " · ${c.body.clip()}",
                    timeMillis = c.timestamp,
                ),
            )
        }

        if (System.currentTimeMillis() - c.timestamp > maxAgeMillis) {
            note(NotificationDebugLog.Outcome.TOO_OLD)
            return
        }
        // Deliberately NOT recorded: a conversation notification reposts up to 25 retained messages on
        // every new message, so logging repeats would evict the whole ring and bury the one entry that
        // matters. The message was already recorded when it was first handled.
        if (!guard.checkAndMark(guard.messageKey(pkg, c.sender, c.body), RecentCaptureGuard.MESSAGE_TTL_MILLIS)) {
            return
        }
        val outcome = captureRepository.handleNotificationAlert(c.sender, c.body, c.timestamp, pkg, canPrompt)
        if (outcome.decision != SmsCaptureRepository.NotificationDecision.PROMPT) {
            note(
                when (outcome.decision) {
                    SmsCaptureRepository.NotificationDecision.QUEUED -> NotificationDebugLog.Outcome.QUEUED
                    SmsCaptureRepository.NotificationDecision.DUPLICATE -> NotificationDebugLog.Outcome.DUPLICATE
                    SmsCaptureRepository.NotificationDecision.NOT_TRANSACTION ->
                        NotificationDebugLog.Outcome.NOT_A_TRANSACTION
                    // Unreachable (guarded by the != above); exhaustive so a new enum value breaks the
                    // build instead of silently reporting as "not a transaction".
                    SmsCaptureRepository.NotificationDecision.PROMPT -> NotificationDebugLog.Outcome.PROMPTED
                },
            )
            return
        }
        val preview = outcome.preview ?: run {
            note(NotificationDebugLog.Outcome.NOT_A_TRANSACTION)
            return
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            captureRepository.queueForReview(c.sender, c.body, c.timestamp, pkg)
            note(NotificationDebugLog.Outcome.QUEUED, "queued instead (prompts are blocked)")
            return
        }
        if (guard.claimPrompt(preview.relaxedHash, preview.refNumber)) {
            captureNotifier.postCapturePrompt(c.sender, c.body, c.timestamp, preview, sourceApp = pkg)
            note(NotificationDebugLog.Outcome.PROMPTED)
        } else {
            note(NotificationDebugLog.Outcome.DUPLICATE, "SMS twin already prompted")
        }
        // A dropped prompt means the SMS receiver already prompted a twin of this transaction; every
        // write path re-checks the exact + relaxed hashes, so nothing can double-add regardless.
    }

    companion object {
        /** Live capture only looks this far back; older messages in a repost are stale context. */
        private const val MAX_LIVE_AGE_MILLIS: Long = 72 * 60 * 60 * 1000

        /** Cap on each recorded debug string, so the in-memory log stays small. */
        private const val MAX_DEBUG_CHARS = 500
    }
}
