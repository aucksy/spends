package com.spends.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spends.app.data.backup.BackupRepository
import com.spends.app.data.backup.DriveAuthManager
import com.spends.app.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Daily Drive auto-backup (PRD §4.12). Self-gates on the user's toggle. Uses a silently-cached
 * authorization token; if Drive consent isn't granted yet (needs the interactive consent screen),
 * it no-ops — the user does one manual "Back up now" first to grant it.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val driveAuthManager: DriveAuthManager,
    private val backupRepository: BackupRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!settingsRepository.settings.first().autoBackupEnabled) return Result.success()
        // Backups are encrypted; without a recovery password set there's nothing to do (no retry storm).
        if (!backupRepository.isBackupProtected()) return Result.success()
        return try {
            when (val auth = driveAuthManager.authorize()) {
                is DriveAuthManager.AuthResult.Authorized -> {
                    backupRepository.backupNow(auth.accessToken)
                    Result.success()
                }
                // Background can't show the consent UI — wait until the user grants it interactively.
                is DriveAuthManager.AuthResult.NeedsConsent -> Result.success()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "drive-auto-backup"
    }
}
