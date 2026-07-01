package com.spends.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spends.app.R
import com.spends.app.core.MainActivity
import com.spends.app.core.time.DateUtils
import com.spends.app.data.repo.RecurringRepository
import com.spends.app.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * The ~9 AM daily pass that materialises any due recurring rules (PRD §4.8) and, when it adds anything,
 * posts a notification so the user knows scheduled transactions landed while they weren't in the app (#3).
 * The app also runs a silent best-effort pass on launch, so this is the backstop for when it isn't opened.
 */
@HiltWorker
class RecurringWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val recurringRepository: RecurringRepository,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        // Auto-add always runs; the NOTIFICATION is gated on the user's toggle (#15). Materialisation is the
        // core feature the user relies on — only the "recurring added" heads-up is optional.
        val created = recurringRepository.materializeDue(DateUtils.nowMillis())
        if (created > 0 && settingsRepository.settings.first().recurringNotifyEnabled) notify(created)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    /** Tell the user scheduled transactions were added (only when this worker actually created some). */
    private fun notify(count: Int) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Recurring transactions",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Tells you when scheduled transactions (rent, EMIs, subscriptions) are added." }
                (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }
            val intent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                appContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = if (count == 1) "1 scheduled transaction was added" else "$count scheduled transactions were added"
            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Recurring added")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(appContext).notify(NOTIF_ID, notification)
        }
    }

    companion object {
        const val UNIQUE_NAME = "recurring-daily"
        private const val CHANNEL_ID = "recurring"
        private const val NOTIF_ID = 71_001
    }
}
