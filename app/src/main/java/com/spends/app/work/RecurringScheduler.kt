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

    /** Default auto-add + notification time when the user hasn't chosen one — 09:00 local (#15). */
    const val DEFAULT_MINUTE = 9 * 60

    /**
     * (Re)schedule the daily recurring pass for [minuteOfDay] (local). [replace]=true when the user changes
     * the time (UPDATE to the new time); false at launch (KEEP the persisted schedule so a process restart
     * never disturbs the user's chosen time — the DEFAULT only applies on a first-ever creation). Mirrors
     * [BackupScheduler]. The worker self-gates the *notification* on the recurring-notify toggle.
     */
    fun schedule(context: Context, minuteOfDay: Int = DEFAULT_MINUTE, replace: Boolean = false) {
        val request = PeriodicWorkRequestBuilder<RecurringWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMinutes(minuteOfDay), TimeUnit.MINUTES)
            .build()
        val policy = if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(RecurringWorker.UNIQUE_NAME, policy, request)
    }

    /** Minutes from now until the next [minuteOfDay] local; at least 1 so it never enqueues "now". */
    private fun initialDelayMinutes(minuteOfDay: Int): Long {
        val zone = DateUtils.ZONE
        val now = Instant.ofEpochMilli(DateUtils.nowMillis()).atZone(zone)
        val safeMinute = minuteOfDay.coerceIn(0, 1439)
        val todaysTarget = now.toLocalDate().atTime(safeMinute / 60, safeMinute % 60).atZone(zone)
        val target = if (todaysTarget.isAfter(now)) todaysTarget else todaysTarget.plusDays(1)
        return Duration.between(now, target).toMinutes().coerceAtLeast(1)
    }
}
