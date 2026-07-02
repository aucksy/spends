package com.spends.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.spends.app.core.time.DateUtils
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Schedules the daily Drive auto-backup ([BackupWorker]) to run about once a day near a user-chosen
 * time-of-day (#11). The first run is delayed to the next occurrence of that time; WorkManager then
 * repeats every 24h. A CONNECTED network constraint means an offline phone simply defers the run and
 * catches up once it's back online — the user never loses a day's backup, it just lands late.
 *
 * Timing is approximate by design: WorkManager (and Doze) may shift a run by minutes, so the backup
 * happens "around" the chosen time, not to the second.
 */
object BackupScheduler {

    /** Default backup time when the user hasn't picked one — 02:00, a quiet overnight hour. */
    const val DEFAULT_MINUTE = 2 * 60

    /**
     * (Re)schedule the daily backup for [minuteOfDay].
     *
     * @param replace when true, the user changed the time (or turned auto-backup on) — CANCEL_AND_REENQUEUE
     *   so the new initial delay actually re-anchors the next run to [minuteOfDay]. (UPDATE keeps a running
     *   periodic job on its old anchor, so a time change silently never took effect — the reported bug.)
     *   When false, KEEP an existing schedule untouched — used at app launch so a process restart never
     *   disturbs the persisted schedule or its timing.
     */
    fun schedule(context: Context, minuteOfDay: Int, replace: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setInitialDelay(initialDelayMinutes(minuteOfDay), TimeUnit.MINUTES)
            .build()
        val policy = if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(BackupWorker.UNIQUE_NAME, policy, request)
    }

    /** Minutes from now until the next [minuteOfDay] (local app zone); at least 1 so it never enqueues "now". */
    private fun initialDelayMinutes(minuteOfDay: Int): Long {
        val zone = DateUtils.ZONE
        val now = Instant.ofEpochMilli(DateUtils.nowMillis()).atZone(zone)
        val safeMinute = minuteOfDay.coerceIn(0, 1439)
        val todaysTarget = now.toLocalDate().atTime(safeMinute / 60, safeMinute % 60).atZone(zone)
        val target = if (todaysTarget.isAfter(now)) todaysTarget else todaysTarget.plusDays(1)
        return Duration.between(now, target).toMinutes().coerceAtLeast(1)
    }
}
