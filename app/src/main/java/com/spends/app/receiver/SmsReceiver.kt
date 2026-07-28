package com.spends.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.spends.app.data.capture.CaptureNotifier
import com.spends.app.data.capture.RecentCaptureGuard
import com.spends.app.data.capture.SenderAllowlist
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.capture.SmsDebugLog
import com.spends.app.data.demo.DemoMode
import com.spends.app.data.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Live SMS capture (PRD §4.1). A plain BroadcastReceiver (registered in the manifest); it pulls its
 * singleton dependencies through a Hilt [EntryPoint] rather than field injection, which avoids the
 * @AndroidEntryPoint/super.onReceive abstract-method pitfall. Only acts when capture is enabled;
 * work runs off the main thread under goAsync so the broadcast completes cleanly.
 *
 * Every exit below records where the message stopped in [SmsDebugLog] (TEMPORARY — remove with the
 * debug screen). All of them are silent from outside the app and the phone cannot tell them apart,
 * which is how live capture stayed broken for two days in July 2026 with no way to narrow it down.
 */
class SmsReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SmsCaptureEntryPoint {
        fun captureRepository(): SmsCaptureRepository
        fun settingsRepository(): SettingsRepository
        fun captureNotifier(): CaptureNotifier
        fun recentCaptureGuard(): RecentCaptureGuard

        /** TEMPORARY diagnostic. Remove with [SmsDebugLog]. */
        fun smsDebugLog(): SmsDebugLog
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val entry = EntryPointAccessors.fromApplication(context.applicationContext, SmsCaptureEntryPoint::class.java)
        val debug = entry.smsDebugLog()
        // Counted for EVERY message, before ANY other check — including in demo mode and with capture
        // switched off. If this stays at zero while texts are visibly arriving, Android is not delivering
        // the broadcast to Spends at all and nothing inside the app can be the cause. No other surface on
        // the phone reveals that. Content is never recorded here, only the count and the time.
        runCatching { debug.recordReceived() }

        // Demo mode points the whole app at the demo database. A real bank alert arriving now would be
        // captured into a throwaway sandbox and then destroyed by the next "Reset demo data" — a genuinely
        // lost transaction. Drop out entirely; the message stays in the inbox and a later scan can find it.
        if (DemoMode.isEnabled(context)) {
            runCatching { debug.record(System.currentTimeMillis(), null, null, null, SmsDebugLog.Outcome.DEMO_MODE) }
            return
        }
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages == null || messages.isEmpty()) {
            runCatching { debug.record(System.currentTimeMillis(), null, null, null, SmsDebugLog.Outcome.NO_MESSAGE_DATA) }
            return
        }

        val sender = messages.firstOrNull()?.displayOriginatingAddress
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val receivedAt = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        if (body.isBlank()) {
            runCatching {
                debug.record(receivedAt, sender, null, null, SmsDebugLog.Outcome.NO_MESSAGE_DATA, "empty body")
            }
            return
        }

        val capture = entry.captureRepository()
        val settings = entry.settingsRepository()
        val notifier = entry.captureNotifier()
        val guard = entry.recentCaptureGuard()

        // Resolved here rather than inside the parse so the diagnostic can separate "we don't know this
        // bank's header" from "we know it but the text wasn't a transaction" — and because it is also the
        // privacy gate: [SmsDebugLog] keeps a message body ONLY for a sender that resolved to a bank.
        val institution = SenderAllowlist.lookup(sender)?.name

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            fun note(outcome: SmsDebugLog.Outcome, detail: String? = null) = runCatching {
                debug.record(receivedAt, sender, institution, body, outcome, detail)
            }
            try {
                // Review-only: never auto-add. A parseable bank SMS always prompts the user (Add/Edit/Ignore).
                if (!settings.settings.first().smsCaptureEnabled) {
                    note(SmsDebugLog.Outcome.CAPTURE_OFF)
                    return@launch
                }
                val preview = capture.preview(sender, body, receivedAt)
                if (preview == null) {
                    note(
                        if (institution == null) {
                            SmsDebugLog.Outcome.SENDER_NOT_RECOGNISED
                        } else {
                            SmsDebugLog.Outcome.NOT_A_TRANSACTION
                        },
                    )
                    return@launch
                }
                val money = "${preview.kind.name.lowercase()} ${preview.amountMinor} paise · ${preview.title}"
                // #7: if the user has ignored this exact pattern enough times, stop nagging — drop it
                // silently into the review queue instead, so it's reviewable but never lost.
                if (capture.isPatternSuppressed(sender, body, receivedAt)) {
                    capture.queueForReview(sender, body, receivedAt)
                    note(SmsDebugLog.Outcome.PATTERN_SUPPRESSED, money)
                } else if (capture.isKnownHash(preview.dedupeHash)) {
                    // Already in the ledger or the review queue (e.g. the notification twin of
                    // this alert got there first, Phase 4) — a prompt would only invite a
                    // double-add attempt the hash guards would then have to swallow.
                    note(SmsDebugLog.Outcome.ALREADY_KNOWN, money)
                } else if (guard.claimPrompt(preview.relaxedHash, preview.refNumber)) {
                    if (notifier.postCapturePrompt(sender, body, receivedAt, preview)) {
                        note(SmsDebugLog.Outcome.PROMPTED, money)
                    } else {
                        // The phone will not show the prompt — the app's notifications are off, or the
                        // "Transaction detection" category alone was switched off. Until now this path
                        // simply returned and the transaction was gone: not shown, not queued, not
                        // recorded anywhere. Park it in the review queue, exactly as the notification
                        // listener already does, so a real transaction is never lost in silence.
                        capture.queueForReview(sender, body, receivedAt)
                        note(SmsDebugLog.Outcome.PROMPT_BLOCKED, "$money · queued instead")
                    }
                } else {
                    // The notification listener prompted a TWIN of this transaction moments ago (the SMS
                    // + Messages/Truecaller twins of one alert, even when one text lost the reference
                    // number) — one prompt is the contract.
                    note(SmsDebugLog.Outcome.TWIN_ALREADY_PROMPTED, money)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
