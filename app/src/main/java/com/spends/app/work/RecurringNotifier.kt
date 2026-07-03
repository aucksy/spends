package com.spends.app.work

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

/**
 * Posts the "recurring added" heads-up when the daily pass materialises scheduled transactions while the
 * user isn't in the app (#3). Shared by the exact-alarm path ([com.spends.app.receiver.RecurringAlarmReceiver])
 * so there's a single channel definition + notification builder.
 */
object RecurringNotifier {

    private const val CHANNEL_ID = "recurring"
    private const val NOTIF_ID = 71_001

    /** Tell the user [count] scheduled transactions were added. Best-effort — never throws. */
    fun notify(context: Context, count: Int) {
        if (count <= 0) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Recurring transactions",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Tells you when scheduled transactions (rent, EMIs, subscriptions) are added." }
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = if (count == 1) "1 scheduled transaction was added" else "$count scheduled transactions were added"
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Recurring added")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        }
    }
}
