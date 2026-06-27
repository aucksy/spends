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
 * Posts the "Add / Edit / Ignore" heads-up notification for REVIEW_PROMPT capture mode. Add and Ignore
 * fire silent broadcasts ([CaptureActionReceiver]); Edit opens the app prefilled (a getActivity intent
 * into [MainActivity]). No full-screen intent is used (Play-compliant).
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

    fun postCapturePrompt(sender: String?, body: String, receivedAt: Long, preview: SmsCaptureRepository.CapturePreview) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return // POST_NOTIFICATIONS not granted — nothing to show
        ensureChannel()

        val notifId = body.hashCode() * 31 + receivedAt.hashCode() // stable per message (dedup re-delivery)
        val kindLabel = when (preview.kind) {
            TxnKind.INCOME -> "Income "
            TxnKind.TRANSFER -> "Transfer "
            TxnKind.EXPENSE -> "Expense "
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(kindLabel + Money.formatRupees(preview.amountMinor))
            .setContentText(preview.title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(editIntent(sender, body, receivedAt, notifId, 0))
            .addAction(0, "Add", broadcast(CaptureActionReceiver.ACTION_ADD, sender, body, receivedAt, notifId, 1))
            .addAction(0, "Edit", editIntent(sender, body, receivedAt, notifId, 3))
            .addAction(0, "Ignore", broadcast(CaptureActionReceiver.ACTION_IGNORE, sender, body, receivedAt, notifId, 2))
            .build()

        try {
            manager.notify(notifId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify — ignore.
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

    private fun editIntent(sender: String?, body: String, receivedAt: Long, notifId: Int, req: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_CAPTURE_EDIT, true)
            putExtra(CaptureActionReceiver.EXTRA_SENDER, sender)
            putExtra(CaptureActionReceiver.EXTRA_BODY, body)
            putExtra(CaptureActionReceiver.EXTRA_RECEIVED_AT, receivedAt)
            putExtra(CaptureActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        return PendingIntent.getActivity(context, notifId + req, intent, PENDING_FLAGS)
    }

    companion object {
        const val CHANNEL_ID = "capture"
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
