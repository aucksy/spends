package com.spends.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/**
 * The "Dismiss" action of a "recurring added" notification (#3).
 *
 * It clears the notification and does nothing else — deliberately. The transaction has already been added
 * to the ledger by the time the notification exists, so a Dismiss that also deleted it would be a destructive
 * action hiding behind a word that everywhere else in Android means "hide this". Deleting a scheduled
 * transaction stays where it has always been: open it and use Delete.
 */
class RecurringActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISS) return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        if (notifId == 0) return
        runCatching { NotificationManagerCompat.from(context).cancel(notifId) }
    }

    companion object {
        const val ACTION_DISMISS = "com.spends.app.recurring.DISMISS"
        const val EXTRA_NOTIF_ID = "notif_id"
    }
}
