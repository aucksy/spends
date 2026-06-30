package com.spends.app.core

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.spends.app.work.BackupScheduler
import com.spends.app.work.RecurringScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Hilt root + a WorkManager [Configuration.Provider] so @HiltWorker workers
 * (recurring generation, and later daily Drive snapshot / capture health-check) can be constructed
 * with injected dependencies. The default WorkManager initializer is removed in the manifest so this
 * configuration is used.
 */
@HiltAndroidApp
class SpendsApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Materialise recurring rules at ~9 AM local + notify (#3). The app ALSO runs a silent pass on
        // launch (MainViewModel), so this worker is the backstop for when the app isn't opened — it's the
        // one that posts the "recurring added" notification. UPDATE re-anchors to the next 9 AM each launch,
        // which is correct: if you open the app daily, the launch pass handles it and this never needs to fire.
        RecurringScheduler.schedule(this, replace = true)

        // Daily Drive auto-backup runs near a user-chosen time (#11); the worker self-gates on the toggle.
        // KEEP so a process restart never disturbs the persisted schedule (which already holds the user's
        // chosen time). The DEFAULT_MINUTE only applies on a first-ever creation; Settings UPDATEs the time.
        BackupScheduler.schedule(this, BackupScheduler.DEFAULT_MINUTE, replace = false)
    }
}
