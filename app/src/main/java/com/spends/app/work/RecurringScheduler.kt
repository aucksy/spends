package com.spends.app.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.spends.app.core.time.DateUtils
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Schedules the daily recurring-materialisation worker for ~9 AM in the device's local time zone (#3).
 * The app ALSO materialises on launch (immediate + silent, in MainViewModel); this worker is the backstop
 * for when the app isn't opened, and it's the one that posts the "recurring added" notification for what
 * it created. Uses [DateUtils.ZONE] (the device time zone) so it follows wherever the user is.
 */
object RecurringScheduler {

    /** Recurring auto-add + its notification target 09:00 local. */
    private const val TARGET_MINUTE = 9 * 60

    fun schedule(context: Context, replace: Boolean = false) {
        val request = PeriodicWorkRequestBuilder<RecurringWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMinutes(), TimeUnit.MINUTES)
            .build()
        val policy = if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(RecurringWorker.UNIQUE_NAME, policy, request)
    }

    /** Minutes from now until the next 09:00 local; at least 1 so it never enqueues "now". */
    private fun initialDelayMinutes(): Long {
        val zone = DateUtils.ZONE
        val now = Instant.ofEpochMilli(DateUtils.nowMillis()).atZone(zone)
        val todaysTarget = now.toLocalDate().atTime(TARGET_MINUTE / 60, TARGET_MINUTE % 60).atZone(zone)
        val target = if (todaysTarget.isAfter(now)) todaysTarget else todaysTarget.plusDays(1)
        return Duration.between(now, target).toMinutes().coerceAtLeast(1)
    }
}
