package com.spends.app.core

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.work.BackupScheduler
import com.spends.app.work.RecurringAlarmScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point. Hilt root + a WorkManager [Configuration.Provider] so @HiltWorker workers
 * (the daily Drive snapshot) can be constructed with injected dependencies. The default WorkManager
 * initializer is removed in the manifest so this configuration is used.
 */
@HiltAndroidApp
class SpendsApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settingsRepository: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Recurring materialisation now fires on an EXACT alarm at the user's chosen time (default 9 AM
        // local) instead of an inexact WorkManager job that Doze could defer by hours (#4). Re-arming at
        // launch is idempotent + self-healing (always computes the next occurrence). Read the persisted
        // minute so a non-default time survives a process restart. The app also materialises silently on
        // launch (MainViewModel); this alarm is the backstop for when the app isn't opened + posts the
        // "recurring added" notification (self-gated on the notify toggle).
        appScope.launch {
            val minute = runCatching { settingsRepository.settings.first().recurringNotifyMinute }
                .getOrDefault(RecurringAlarmScheduler.DEFAULT_MINUTE)
            RecurringAlarmScheduler.schedule(this@SpendsApp, minute)
        }
        // Migration off the old inexact WorkManager recurring job so it can't also fire (double-notify).
        runCatching { WorkManager.getInstance(this).cancelUniqueWork(LEGACY_RECURRING_WORK) }

        // Daily Drive auto-backup runs near a user-chosen time (#11); the worker self-gates on the toggle.
        // KEEP so a process restart never disturbs the persisted schedule (which already holds the user's
        // chosen time). The DEFAULT_MINUTE only applies on a first-ever creation; Settings UPDATEs the time.
        BackupScheduler.schedule(this, BackupScheduler.DEFAULT_MINUTE, replace = false)
    }

    private companion object {
        /** The unique name of the now-removed WorkManager recurring job (was RecurringWorker.UNIQUE_NAME). */
        const val LEGACY_RECURRING_WORK = "recurring-daily"
    }
}
