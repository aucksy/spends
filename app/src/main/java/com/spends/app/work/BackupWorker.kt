package com.spends.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spends.app.data.backup.BackupRepository
import com.spends.app.data.backup.DriveAuthManager
import com.spends.app.data.demo.DemoMode
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
        // Demo mode: the app is pointed at the demo database, so a backup here would upload FAKE data to the
        // user's real Drive folder and sit in the restore picker looking like a genuine snapshot. Never run.
        // (The demo settings file has the toggle off anyway; this is the belt to that braces.)
        if (DemoMode.isEnabled(applicationContext)) return Result.success()
        if (!settingsRepository.settings.first().autoBackupEnabled) return Result.success()
        // Backups no longer require a password (#8) — a snapshot always produces real bytes (encrypted if a
        // password is set, otherwise plaintext), so the daily backup just runs.
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
