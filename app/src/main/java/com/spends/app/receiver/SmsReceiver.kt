package com.spends.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.spends.app.data.capture.CaptureNotifier
import com.spends.app.data.capture.RecentCaptureGuard
import com.spends.app.data.capture.SmsCaptureRepository
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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val receivedAt = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        if (body.isBlank()) return

        val entry = EntryPointAccessors.fromApplication(context.applicationContext, SmsCaptureEntryPoint::class.java)
        val capture = entry.captureRepository()
        val settings = entry.settingsRepository()
        val notifier = entry.captureNotifier()
        val guard = entry.recentCaptureGuard()

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Review-only: never auto-add. A parseable bank SMS always prompts the user (Add/Edit/Ignore).
                if (settings.settings.first().smsCaptureEnabled) {
                    capture.preview(sender, body, receivedAt)?.let { preview ->
                        // #7: if the user has ignored this exact pattern enough times, stop nagging — drop it
                        // silently into the review queue instead, so it's reviewable but never lost.
                        if (capture.isPatternSuppressed(sender, body, receivedAt)) {
                            capture.queueForReview(sender, body, receivedAt)
                        } else if (capture.isKnownHash(preview.dedupeHash)) {
                            // Already in the ledger or the review queue (e.g. the notification twin of
                            // this alert got there first, Phase 4) — a prompt would only invite a
                            // double-add attempt the hash guards would then have to swallow.
                        } else if (guard.checkAndMark(
                                guard.promptKey(preview.dedupeHash),
                                RecentCaptureGuard.PROMPT_TTL_MILLIS,
                            )
                        ) {
                            notifier.postCapturePrompt(sender, body, receivedAt, preview)
                        }
                        // else: the notification listener prompted this exact transaction moments ago
                        // (the SMS + Messages/Truecaller twins of one alert) — one prompt is the contract.
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
