package com.spends.app.data.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.spends.app.R
import com.spends.app.core.MainActivity
import com.spends.app.core.money.Money
import com.spends.app.domain.model.TxnKind
import com.spends.app.receiver.CaptureActionReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the "Review & Add / Ignore" heads-up notification for REVIEW_PROMPT capture mode (#8). "Review &
 * Add" opens the app prefilled (a getActivity intent into [MainActivity]) so the spend is reviewed before
 * it's added; "Ignore" fires a silent broadcast ([CaptureActionReceiver]). No full-screen intent (Play-compliant).
 */
@Singleton
class CaptureNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Transaction detection", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Prompts you to add a transaction when a bank SMS arrives."
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    /**
     * Post the "Review & Add / Ignore" prompt.
     *
     * **Returns whether the prompt was actually handed to Android**, and a `false` obliges the caller to
     * queue the capture for review instead. It is never safe to ignore: a parsed bank alert that is
     * neither shown nor queued is a real transaction lost in silence, with no record anywhere that it
     * ever existed. Both live paths now honour this (the SMS path used to drop it on the floor).
     *
     * [sourceApp] = the watched app's package when this alert came from the notification listener
     * (Phase 4); carried through the edit intent into the draft so Save can apply the notification-only
     * twin guard. Null for the SMS path.
     */
    fun postCapturePrompt(
        sender: String?,
        body: String,
        receivedAt: Long,
        preview: SmsCaptureRepository.CapturePreview,
        sourceApp: String? = null,
    ): Boolean {
        val manager = NotificationManagerCompat.from(context)
        // Created BEFORE the importance is read, or a first-ever prompt would look blocked. Re-creating a
        // channel never resurrects one the user switched off, so the check below stays meaningful.
        ensureChannel()
        if (!promptsCanBeSeen(context)) return false

        val notifId = body.hashCode() * 31 + receivedAt.hashCode() // stable per message (dedup re-delivery)
        val kindLabel = when (preview.kind) {
            TxnKind.INCOME -> "Income "
            TxnKind.EXPENSE -> "Expense "
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(kindLabel + Money.formatRupees(preview.amountMinor))
            .setContentText(preview.title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(editIntent(sender, body, receivedAt, notifId, 0, sourceApp))
            // Two actions only (#8): "Review & Add" opens the editor prefilled (review-then-add, never a
            // silent add — matches the user's review-only stance), "Ignore" dismisses. The old silent "Add"
            // broadcast action is gone.
            .addAction(0, "Review & Add", editIntent(sender, body, receivedAt, notifId, 3, sourceApp))
            .addAction(0, "Ignore", broadcast(CaptureActionReceiver.ACTION_IGNORE, sender, body, receivedAt, notifId, 2))
            .build()

        return try {
            manager.notify(notifId, notification)
            true
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify — the caller queues it instead.
            false
        }
    }

    private fun broadcast(action: String, sender: String?, body: String, receivedAt: Long, notifId: Int, req: Int): PendingIntent {
        val intent = Intent(context, CaptureActionReceiver::class.java).apply {
            this.action = action
            putExtra(CaptureActionReceiver.EXTRA_SENDER, sender)
            putExtra(CaptureActionReceiver.EXTRA_BODY, body)
            putExtra(CaptureActionReceiver.EXTRA_RECEIVED_AT, receivedAt)
            putExtra(CaptureActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        return PendingIntent.getBroadcast(context, notifId + req, intent, PENDING_FLAGS)
    }

    private fun editIntent(sender: String?, body: String, receivedAt: Long, notifId: Int, req: Int, sourceApp: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_CAPTURE_EDIT, true)
            putExtra(CaptureActionReceiver.EXTRA_SENDER, sender)
            putExtra(CaptureActionReceiver.EXTRA_BODY, body)
            putExtra(CaptureActionReceiver.EXTRA_RECEIVED_AT, receivedAt)
            putExtra(CaptureActionReceiver.EXTRA_NOTIF_ID, notifId)
            putExtra(CaptureActionReceiver.EXTRA_SOURCE_APP, sourceApp)
        }
        return PendingIntent.getActivity(context, notifId + req, intent, PENDING_FLAGS)
    }

    companion object {
        const val CHANNEL_ID = "capture"
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /**
         * Whether a posted prompt could actually be SEEN. Pure, so the rule is directly testable.
         *
         * Two independent switches hide it, and only one of them used to be checked:
         *  - [notificationsEnabled] — the app's master notification toggle / POST_NOTIFICATIONS;
         *  - [channelImportance] — the "Transaction detection" CATEGORY. A user long-pressing one prompt
         *    and tapping "turn these off", or an OEM notification manager, switches this off alone. The
         *    master toggle keeps reading ON, `notify()` is accepted and silently binned, and every bank
         *    SMS from that moment on vanishes. Nothing in the app or in Android surfaces the difference.
         *
         * A null importance (pre-O, or a channel not created yet) is NOT treated as blocked — there is no
         * per-channel switch to have been turned off in that case.
         */
        fun canPost(notificationsEnabled: Boolean, channelImportance: Int?): Boolean =
            notificationsEnabled && channelImportance != NotificationManager.IMPORTANCE_NONE

        /**
         * [canPost] answered for a live context — the single implementation, used both before posting and
         * by the SMS debug screen, so the screen can never report a different verdict from the one the
         * capture path actually acts on.
         */
        fun promptsCanBeSeen(context: Context): Boolean = canPost(
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
            channelImportance(context),
        )

        /** The live importance of our prompt channel, or null pre-O / when it doesn't exist yet. */
        private fun channelImportance(context: Context): Int? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
            return context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(CHANNEL_ID)
                ?.importance
        }
    }
}
