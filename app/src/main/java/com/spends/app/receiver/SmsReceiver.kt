package com.spends.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.spends.app.data.capture.SmsIntake
import com.spends.app.data.capture.SmsSeen
import com.spends.app.data.demo.DemoMode
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Live SMS capture (PRD §4.1). A plain BroadcastReceiver (registered in the manifest); it pulls its
 * singleton dependencies through a Hilt [EntryPoint] rather than field injection, which avoids the
 * @AndroidEntryPoint/super.onReceive abstract-method pitfall. Only acts when capture is enabled;
 * work runs off the main thread under goAsync so the broadcast completes cleanly.
 *
 * The steps themselves live in [SmsIntake], shared with the catch-up sweep that finds texts Android never
 * delivered here. Every text that does arrive is fingerprinted in [SmsSeen] first, so the sweep skips it.
 */
class SmsReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SmsCaptureEntryPoint {
        fun smsIntake(): SmsIntake
    }

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
        // smsIntake() is what opens the Room database and what throws on a corrupt file or a failed
        // migration. Wrapping only the lookup left the real hazard bare, on the main looper.
        val intake = runCatching { entry.smsIntake() }.getOrNull() ?: return

        // Fingerprint it, and stamp the time Android last delivered a text, so
        // the settings line can tell "Android stopped delivering" from "delivered, but not a payment".
        // False = the catch-up sweep already handled this exact text (a battery manager held the
        // broadcast back until the app was opened) — a second prompt would only be a duplicate.
        // Taken only once the app is ready: a text that arrives while the database will not open stays
        // unfingerprinted, so the next sweep in a healthy process still picks it up.
        if (!SmsSeen.markDelivered(context.applicationContext, body)) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                intake.handle(sender, body, receivedAt, late = false)
            } finally {
                pending.finish()
            }
        }
    }
}
