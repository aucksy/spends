package com.spends.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.spends.app.data.capture.SmsCaptureRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the silent actions of a capture prompt notification (REVIEW_PROMPT mode):
 *  - [ACTION_ADD]    persists the parsed transaction (re-parses the carried SMS) without opening the app.
 *  - [ACTION_IGNORE] just dismisses the notification — nothing is saved.
 * (The "Edit" action launches MainActivity directly via a getActivity PendingIntent, so it isn't here.)
 */
class CaptureActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CaptureActionEntryPoint {
        fun captureRepository(): SmsCaptureRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        NotificationManagerCompat.from(context).cancel(notifId)
        if (intent.action != ACTION_ADD) return // ACTION_IGNORE (or anything else): just dismiss

        val sender = intent.getStringExtra(EXTRA_SENDER)
        val body = intent.getStringExtra(EXTRA_BODY) ?: return
        val receivedAt = intent.getLongExtra(EXTRA_RECEIVED_AT, System.currentTimeMillis())

        val capture = EntryPointAccessors
            .fromApplication(context.applicationContext, CaptureActionEntryPoint::class.java)
            .captureRepository()

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { capture.captureReturningId(sender, body, receivedAt) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ADD = "com.spends.app.capture.ADD"
        const val ACTION_IGNORE = "com.spends.app.capture.IGNORE"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_BODY = "body"
        const val EXTRA_RECEIVED_AT = "received_at"
        const val EXTRA_NOTIF_ID = "notif_id"
    }
}
