package com.spends.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.spends.app.data.capture.CaptureNotifier
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.SmsCaptureMode
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

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val s = settings.settings.first()
                if (s.smsCaptureEnabled) {
                    when (s.smsCaptureMode) {
                        SmsCaptureMode.AUTO_ADD -> runCatching { capture.capture(sender, body, receivedAt) }
                        SmsCaptureMode.REVIEW_PROMPT -> {
                            // Don't save yet — prompt the user with Add / Edit / Ignore.
                            capture.preview(sender, body, receivedAt)?.let {
                                notifier.postCapturePrompt(sender, body, receivedAt, it)
                            }
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
