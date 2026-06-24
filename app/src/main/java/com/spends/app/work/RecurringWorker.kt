package com.spends.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spends.app.data.repo.RecurringRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Daily background pass that materialises any due recurring rules (PRD §4.8). The app also runs a
 * best-effort pass on launch, so this is the backstop for when the app isn't opened for a while.
 */
@HiltWorker
class RecurringWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val recurringRepository: RecurringRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        recurringRepository.materializeDue(System.currentTimeMillis())
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "recurring-daily"
    }
}
