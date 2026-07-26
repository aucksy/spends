package com.spends.app.data.demo

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * **Demo mode** — a fully sealed sandbox for showing the app off without exposing (or risking) real money.
 *
 * ## The safety guarantee
 * Demo mode does **not** hide, filter or tag the user's real rows. It swaps the whole storage layer:
 *  - Room opens `spends-demo.db` instead of [com.spends.app.data.db.SpendsDatabase.NAME] (see `DatabaseModule`);
 *  - the settings DataStore opens `settings_demo.preferences_pb` instead of `settings.preferences_pb`
 *    (see `SettingsModule`).
 *
 * While the flag is on, **the live database file is never opened** — not read, not written, not migrated. That
 * is a far stronger promise than "every query remembered to filter", which is the only alternative and puts
 * demo rows in the same tables as real money.
 *
 * ## Why this is a SharedPreferences flag and not a DataStore one
 * The flag has to be readable **synchronously, before Hilt builds the object graph**, because it decides which
 * file the database and the DataStore open. DataStore is suspend-only and is itself one of the things being
 * switched, so it cannot hold this. [SharedPreferences] is loaded eagerly and read synchronously — the right
 * tool here. Its own file is never swapped, so the flag survives a mode change (which is the point).
 *
 * ## Why flipping it restarts the app
 * `SpendsDatabase` and `SettingsRepository` are `@Singleton`s: every repository, ViewModel and collected Flow
 * in the process holds a reference to the instance built at startup. There is no supported way to swap that
 * out underneath a live graph, and pretending otherwise would leave half the app reading one database and
 * half the other. Killing the process and coming straight back is honest and total: the new process reads the
 * new flag and builds one consistent graph. See [restartInto].
 */
object DemoMode {

    private const val PREFS = "spends_demo"
    private const val KEY_ENABLED = "demo_enabled"
    private const val KEY_SEEDED_ON = "demo_seeded_on_epoch_day"

    /** The Room file used while demo mode is on. Deliberately a different file from the live database. */
    const val DEMO_DB_NAME = "spends-demo.db"

    /** The settings DataStore file used while demo mode is on (no `.preferences_pb` suffix — DataStore adds it). */
    const val DEMO_SETTINGS_NAME = "settings_demo"

    /** The live settings DataStore file. Must stay byte-identical to the name the old `preferencesDataStore`
     *  delegate used, or every existing user silently loses their preferences on upgrade. */
    const val LIVE_SETTINGS_NAME = "settings"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Synchronous, safe to call from a Hilt provider or a BroadcastReceiver's `onReceive`. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /**
     * Whether the demo account needs (re)building.
     *
     * Demo data is generated **relative to today** — "this cycle", "last month", "3× your usual this week".
     * Data seeded a week ago would open on a cycle that ended a week ago, which is exactly the wrong thing to
     * put in front of an audience. So it re-seeds once per calendar day, and otherwise leaves the account
     * alone so anything added mid-demo survives an app restart. "Reset demo data" forces it any time.
     */
    fun needsSeed(context: Context, todayEpochDay: Long): Boolean =
        prefs(context).getLong(KEY_SEEDED_ON, Long.MIN_VALUE) != todayEpochDay

    fun markSeeded(context: Context, todayEpochDay: Long) {
        prefs(context).edit().putLong(KEY_SEEDED_ON, todayEpochDay).apply()
    }

    /**
     * Persist [enabled] and relaunch the app into that mode.
     *
     * `commit()` (not `apply()`) is deliberate: the process is killed on the next line, and `apply()`'s write
     * is asynchronous — it would be a coin flip whether the flag reached disk, and losing it means coming back
     * up in the wrong mode. `commit()` blocks until the write lands.
     *
     * The launch intent is started **before** the process dies: at that moment we are still the foreground app,
     * so the start is permitted (a start scheduled to fire after death would hit Android 10+'s background-
     * activity-start restrictions and silently do nothing). `startActivity` is a synchronous binder call — by
     * the time it returns the system has accepted the launch and will spawn a fresh process for us.
     *
     * Returns false — **without killing the process** — if the relaunch could not be started. The flag is
     * rolled back in that case, so the app carries on consistently in the mode it is already running rather
     * than vanishing with no explanation and coming back as something the user didn't ask for.
     */
    fun restartInto(context: Context, enabled: Boolean): Boolean {
        val app = context.applicationContext
        val intent: Intent = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: return false

        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit()
        if (runCatching { context.startActivity(intent) }.isFailure) {
            prefs(context).edit().putBoolean(KEY_ENABLED, !enabled).commit()
            return false
        }
        Runtime.getRuntime().exit(0)
        return true // unreachable; the process is gone
    }
}
