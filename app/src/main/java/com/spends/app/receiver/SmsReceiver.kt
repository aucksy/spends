package com.spends.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.spends.app.data.capture.CaptureNotifier
import com.spends.app.data.capture.RecentCaptureGuard
import com.spends.app.data.capture.SmsCaptureRepository
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
 */
class SmsReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SmsCaptureEntryPoint {
        fun captureRepository(): SmsCaptureRepository
        fun settingsRepository(): SettingsRepository
        fun captureNotifier(): CaptureNotifier
        fun recentCaptureGuard(): RecentCaptureGuard
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

        // Wrapped: this runs on the main looper inside a system broadcast, for EVERY SMS on the phone.
        // A Hilt graph that fails to build must not take the process down from here.
        val entry = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, SmsCaptureEntryPoint::class.java)
        }.getOrNull() ?: return

        // Demo mode points the whole app at the demo database. A real bank alert arriving now would be
        // captured into a throwaway sandbox and then destroyed by the next "Reset demo data" — a genuinely
        // lost transaction. Drop out entirely; the message stays in the inbox and a later scan can find it.
        if (DemoMode.isEnabled(context)) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages == null || messages.isEmpty()) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val receivedAt = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        if (body.isBlank()) return

        // Guarded for the same reason as the lookup above, and this is where the risk actually lives: in
        // Hilt the accessor is cheap and the graph is CONSTRUCTED inside the provision methods, so
        // captureRepository() is what opens the Room database and what throws on a corrupt file or a
        // failed migration. Wrapping only the lookup left the real hazard bare, on the main looper.
        val deps = runCatching {
            Deps(entry.captureRepository(), entry.settingsRepository(), entry.captureNotifier(), entry.recentCaptureGuard())
        }.getOrNull() ?: return
        val (capture, settings, notifier, guard) = deps

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Review-only: never auto-add. A parseable bank SMS always prompts the user (Add/Edit/Ignore).
                if (!settings.settings.first().smsCaptureEnabled) return@launch
                val preview = capture.preview(sender, body, receivedAt) ?: return@launch
                // #7: if the user has ignored this exact pattern enough times, stop nagging — drop it
                // silently into the review queue instead, so it's reviewable but never lost.
                if (capture.isPatternSuppressed(sender, body, receivedAt)) {
                    capture.queueForReview(sender, body, receivedAt)
                } else if (capture.isKnownHash(preview.dedupeHash)) {
                    // Already in the ledger or the review queue (e.g. the notification twin of
                    // this alert got there first, Phase 4) — a prompt would only invite a
                    // double-add attempt the hash guards would then have to swallow.
                } else if (guard.claimPrompt(preview.relaxedHash, preview.refNumber)) {
                    if (!notifier.postCapturePrompt(sender, body, receivedAt, preview)) {
                        // The phone will not show the prompt — the app's notifications are off, or the
                        // "Transaction detection" category alone was switched off. Park it in the review
                        // queue, exactly as the notification listener does, so a real transaction is
                        // never lost in silence.
                        capture.queueForReview(sender, body, receivedAt)
                    }
                }
                // else: a TWIN of this transaction already claimed the prompt — the SMS and notification
                // twins of one alert (even when one lost the reference number), or the same SMS delivered
                // twice by the phone.
            } finally {
                pending.finish()
            }
        }
    }
}
