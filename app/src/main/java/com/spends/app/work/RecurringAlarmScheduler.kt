package com.spends.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.spends.app.core.time.DateUtils
import com.spends.app.receiver.RecurringAlarmReceiver
import java.time.Instant

/**
 * Schedules the daily recurring-materialisation pass at an EXACT time (#4). WorkManager's periodic job was
 * inexact — Doze/App-Standby batching legitimately deferred the 9 AM run by up to a couple of hours, which
 * the user noticed. An AlarmManager exact alarm fires on-the-minute (backed by USE_EXACT_ALARM /
 * SCHEDULE_EXACT_ALARM). Exact alarms are one-shot, so the receiver re-arms tomorrow's alarm after it fires,
 * and [com.spends.app.receiver.BootReceiver] re-arms it after a reboot (alarms are cleared on boot).
 *
 * The app ALSO materialises silently on launch (MainViewModel), so this alarm is the backstop for when the
 * app isn't opened — and the one that posts the "recurring added" notification.
 */
object RecurringAlarmScheduler {

    /** Default add + notify time when the user hasn't chosen one — 09:00 local. */
    const val DEFAULT_MINUTE = 9 * 60

    private const val REQUEST_CODE = 71_010

    /**
     * (Re)arm the daily recurring alarm at [minuteOfDay] local. Re-arming is idempotent and self-healing: it
     * always computes the NEXT occurrence (today if still ahead, else tomorrow) and replaces any existing
     * alarm with the same PendingIntent — so calling it at launch, on a time change, after firing, or after
     * boot all converge on the right next run without ever double-firing.
     */
    fun schedule(context: Context, minuteOfDay: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = nextTriggerMillis(minuteOfDay)
        val pending = pendingIntent(context)
        // With USE_EXACT_ALARM (API 33+) / SCHEDULE_EXACT_ALARM (31/32) this is always permitted; guard
        // anyway so a revoked permission degrades to an inexact alarm instead of a SecurityException crash.
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RecurringAlarmReceiver::class.java).apply {
            action = RecurringAlarmReceiver.ACTION_FIRE
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Epoch millis of the next [minuteOfDay] in the app's local zone (today if still ahead, else tomorrow). */
    private fun nextTriggerMillis(minuteOfDay: Int): Long {
        val zone = DateUtils.ZONE
        val now = Instant.ofEpochMilli(DateUtils.nowMillis()).atZone(zone)
        val safeMinute = minuteOfDay.coerceIn(0, 1439)
        val todaysTarget = now.toLocalDate().atTime(safeMinute / 60, safeMinute % 60).atZone(zone)
        val target = if (todaysTarget.isAfter(now)) todaysTarget else todaysTarget.plusDays(1)
        return target.toInstant().toEpochMilli()
    }
}
