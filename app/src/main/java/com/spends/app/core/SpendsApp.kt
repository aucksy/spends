package com.spends.app.core

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Hilt root + a WorkManager [Configuration.Provider] so future
 * @HiltWorker workers (recurring generation, daily Drive snapshot, capture health-check) can be
 * constructed with injected dependencies. The default WorkManager initializer is removed in the
 * manifest so this configuration is used.
 */
@HiltAndroidApp
class SpendsApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
