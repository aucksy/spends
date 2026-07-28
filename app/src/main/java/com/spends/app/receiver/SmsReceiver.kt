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
import com.spends.app.data.capture.SmsParser
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

    /** Everything provisioned in one guarded step, so a graph failure is caught once rather than four
     *  times — and destructured below to keep the call sites unchanged. */
    private data class Deps(
        val capture: SmsCaptureRepository,
        val settings: SettingsRepository,
        val notifier: CaptureNotifier,
        val guard: RecentCaptureGuard,
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Wrapped: this runs on the main looper inside a system broadcast, and it now runs for EVERY SMS
        // on the phone rather than only capture-eligible ones. A Hilt graph that fails to build must not
        // take the process down from here.
        val entry = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, SmsCaptureEntryPoint::class.java)
        }.getOrNull() ?: run {
            // Counted in a plain object, not the injected log — the log is the thing we just failed to
            // obtain. Swallowing this silently would leave the counter at zero and let the debug screen
            // assert "Android is not delivering… nothing inside Spends can be the cause" in the one case
            // where Spends IS the cause.
            SmsDebugLog.ReceiverFailures.recordGraphFailure()
            return
        }
        val debug = runCatching { entry.smsDebugLog() }.getOrNull() ?: run {
            SmsDebugLog.ReceiverFailures.recordGraphFailure()
            return
        }
        // Counted for EVERY message, before ANY other check — including in demo mode and with capture
        // switched off. If this stays at zero while texts are visibly arriving, Android is not delivering
        // the broadcast to Spends at all and nothing inside the app can be the cause. No other surface on
        // the phone reveals that. Content is never recorded here, only the count and the time.
        runCatching { debug.recordReceived() }

        // Demo mode points the whole app at the demo database. A real bank alert arriving now would be
        // captured into a throwaway sandbox and then destroyed by the next "Reset demo data" — a genuinely
        // lost transaction. Drop out entirely; the message stays in the inbox and a later scan can find it.
        if (DemoMode.isEnabled(context)) {
            runCatching { debug.record(System.currentTimeMillis(), null, null, SmsDebugLog.Outcome.DEMO_MODE) }
            return
        }
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages == null || messages.isEmpty()) {
            runCatching { debug.record(System.currentTimeMillis(), null, null, SmsDebugLog.Outcome.NO_MESSAGE_DATA) }
            return
        }

        val sender = messages.firstOrNull()?.displayOriginatingAddress
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val receivedAt = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        if (body.isBlank()) {
            runCatching { debug.record(receivedAt, sender, null, SmsDebugLog.Outcome.BLANK_BODY) }
            return
        }

        // Guarded for the same reason as the lookup above, and this is where the risk actually lives: in
        // Hilt the accessor is cheap and the graph is CONSTRUCTED inside the provision methods, so
        // captureRepository() is what opens the Room database and what throws on a corrupt file or a
        // failed migration. Wrapping only the lookup left the real hazard bare, on the main looper.
        val deps = runCatching {
            Deps(entry.captureRepository(), entry.settingsRepository(), entry.captureNotifier(), entry.recentCaptureGuard())
        }.getOrNull() ?: run {
            SmsDebugLog.ReceiverFailures.recordGraphFailure()
            // Recorded, unlike the two failures above it, because here the log IS in hand. Leaving it
            // to the counter alone left the message list empty beside a non-zero delivered count, and
            // the verdict then blamed the sender allowlist for a database that would not open.
            runCatching { debug.record(receivedAt, sender, body, SmsDebugLog.Outcome.APP_NOT_READY) }
            return
        }
        val (capture, settings, notifier, guard) = deps

        // Separates "we don't know this bank's header" from "we know it but the text wasn't a
        // transaction" — the two are indistinguishable from `preview == null` alone. It is NOT the
        // privacy gate: SmsDebugLog resolves the sender itself and ignores anything a caller claims.
        val recognised = SenderAllowlist.lookup(sender) != null

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Typed figures, never a pre-built string: SmsDebugLog renders the amount itself so nothing
            // a caller passes can carry an identifier past the mask. `note` is free text and IS masked.
            fun note(
                outcome: SmsDebugLog.Outcome,
                amountMinor: Long? = null,
                kind: String? = null,
                text: String? = null,
            ) = runCatching {
                debug.record(receivedAt, sender, body, outcome, amountMinor, kind, text)
            }
            try {
                // Review-only: never auto-add. A parseable bank SMS always prompts the user (Add/Edit/Ignore).
                if (!settings.settings.first().smsCaptureEnabled) {
                    note(SmsDebugLog.Outcome.CAPTURE_OFF)
                    return@launch
                }
                val preview = capture.preview(sender, body, receivedAt)
                if (preview == null) {
                    if (recognised) {
                        // The parser's own verdict, and ONLY that. An earlier version also reported
                        // "amount found / no amount matched" and the direction — both dead: reaching
                        // here means the result was not TRANSACTION, and every such Parsed carries a
                        // null amount and kind. Worse, "no amount matched" was usually false, because
                        // the OTP/promo/declined/mandate rejects fire BEFORE the amount regex ever runs.
                        // A diagnostic asserting a cause it cannot know is the defect this round exists
                        // to remove, so it now says only what it actually knows.
                        val p = SmsParser.parse(sender, body, receivedAt)
                        note(SmsDebugLog.Outcome.NOT_A_TRANSACTION, text = "read as ${p.result.name.lowercase()}")
                    } else {
                        note(SmsDebugLog.Outcome.SENDER_NOT_RECOGNISED)
                    }
                    return@launch
                }
                // The merchant is a verbatim substring of the bank's text — it has carried phone
                // numbers, reference numbers and OTPs — so it goes through `note`, which is masked.
                val amount = preview.amountMinor
                val kind = preview.kind.name.lowercase()
                val merchant = preview.title
                // #7: if the user has ignored this exact pattern enough times, stop nagging — drop it
                // silently into the review queue instead, so it's reviewable but never lost.
                if (capture.isPatternSuppressed(sender, body, receivedAt)) {
                    // queueForReview returns null when its own dedupe nets already hold this — reported
                    // as what actually happened, not as what was attempted. A diagnostic that says
                    // "queued" for a row that was never inserted is the same class of defect as the
                    // scan message this round is fixing.
                    // The outcome stays PATTERN_SUPPRESSED even when the row was already held: the
                    // suppression is STICKY state (ignored 3+ times) that the owner may need to reset,
                    // and it is one of the things this screen exists to reveal. Reporting it as a plain
                    // duplicate would hide it. The duplication goes in the detail instead.
                    val queued = capture.queueForReview(sender, body, receivedAt)
                    note(
                        SmsDebugLog.Outcome.PATTERN_SUPPRESSED,
                        amount, kind,
                        if (queued != null) merchant else "$merchant · already held",
                    )
                } else if (capture.isKnownHash(preview.dedupeHash)) {
                    // Already in the ledger or the review queue (e.g. the notification twin of
                    // this alert got there first, Phase 4) — a prompt would only invite a
                    // double-add attempt the hash guards would then have to swallow.
                    note(SmsDebugLog.Outcome.ALREADY_KNOWN, amount, kind, merchant)
                } else if (guard.claimPrompt(preview.relaxedHash, preview.refNumber)) {
                    if (notifier.postCapturePrompt(sender, body, receivedAt, preview)) {
                        note(SmsDebugLog.Outcome.PROMPTED, amount, kind, merchant)
                    } else {
                        // The phone will not show the prompt — the app's notifications are off, or the
                        // "Transaction detection" category alone was switched off. Until now this path
                        // simply returned and the transaction was gone: not shown, not queued, not
                        // recorded anywhere. Park it in the review queue, exactly as the notification
                        // listener already does, so a real transaction is never lost in silence.
                        val queued = capture.queueForReview(sender, body, receivedAt)
                        if (queued != null) {
                            note(SmsDebugLog.Outcome.PROMPT_BLOCKED, amount, kind, "$merchant · queued for review instead")
                        } else {
                            note(SmsDebugLog.Outcome.ALREADY_KNOWN, amount, kind, "$merchant · prompt blocked, and already held")
                        }
                    }
                } else {
                    // The notification listener prompted a TWIN of this transaction moments ago (the SMS
                    // + Messages/Truecaller twins of one alert, even when one text lost the reference
                    // number) — one prompt is the contract.
                    note(SmsDebugLog.Outcome.TWIN_ALREADY_PROMPTED, amount, kind, merchant)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
