package com.spends.app.data.backup

import android.content.Context

/**
 * When to ask the owner to set up Google Drive backup. Onboarding only offers a RESTORE, so a new user can
 * use Spends for months with their only copy on the phone. The ask waits until they have opened the app on
 * [MIN_DAYS_USED] different days (they are invested by then), never shows once a backup exists or the daily
 * backup is on, comes back at most every [SNOOZE_MILLIS] after "Not now", and stops after [MAX_TIMES].
 * Device-local, not in the backup.
 */
object BackupNudge {

    const val MIN_DAYS_USED = 3
    const val MAX_TIMES = 3
    const val SNOOZE_MILLIS = 7L * 24 * 60 * 60 * 1000

    data class State(
        val daysUsed: Int = 0,
        val lastEpochDay: Long = -1,
        val timesShown: Int = 0,
        val lastShownAt: Long = 0,
    )

    /** Counts one more day of use the first time the app opens on [epochDay]. PURE. */
    fun onOpened(s: State, epochDay: Long): State =
        if (epochDay == s.lastEpochDay) s else s.copy(daysUsed = s.daysUsed + 1, lastEpochDay = epochDay)

    fun onShown(s: State, now: Long): State = s.copy(timesShown = s.timesShown + 1, lastShownAt = now)

    /** PURE. */
    fun shouldShow(s: State, hasBackup: Boolean, autoBackupOn: Boolean, now: Long): Boolean =
        !hasBackup && !autoBackupOn &&
            s.daysUsed >= MIN_DAYS_USED &&
            s.timesShown < MAX_TIMES &&
            (s.timesShown == 0 || now - s.lastShownAt >= SNOOZE_MILLIS)

    fun read(context: Context): State {
        val p = prefs(context)
        return State(
            daysUsed = p.getInt("days_used", 0),
            lastEpochDay = p.getLong("last_epoch_day", -1),
            timesShown = p.getInt("times_shown", 0),
            lastShownAt = p.getLong("last_shown_at", 0),
        )
    }

    fun write(context: Context, s: State) {
        prefs(context).edit()
            .putInt("days_used", s.daysUsed)
            .putLong("last_epoch_day", s.lastEpochDay)
            .putInt("times_shown", s.timesShown)
            .putLong("last_shown_at", s.lastShownAt)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("backup_nudge", Context.MODE_PRIVATE)
}
