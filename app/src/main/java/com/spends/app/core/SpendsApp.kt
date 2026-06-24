package com.spends.app.core

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.spends.app.work.RecurringWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
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
        // Daily backstop for materialising recurring rules. KEEP keeps an existing schedule intact
        // across launches. (getInstance triggers on-demand WorkManager init using our config above.)
        val daily = PeriodicWorkRequestBuilder<RecurringWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RecurringWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            daily,
        )
    }
}
