package com.spends.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.spends.app.data.capture.SmsIntake
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Every 15 minutes (WorkManager's shortest period), look in the inbox for bank texts the live receiver
 * never got — see [SmsIntake.catchUp]. A phone that stops Spends in the background usually stops this
 * too, so the app also sweeps on every open; this one covers the hours in between when it can.
 */
@HiltWorker
class SmsCatchUpWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val smsIntake: SmsIntake,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runCatching { smsIntake.catchUp() }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "sms-catch-up"

        /** KEEP: idempotent at every launch, never resets a running schedule. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SmsCatchUpWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
