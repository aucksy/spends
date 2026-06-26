package com.spends.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spends.app.R
import com.spends.app.core.MainActivity
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
        // Auto-backup is ON but no recovery password is set, so a backup would write nothing. Don't fail
        // silently (the trap behind #7) — tell the user their daily backups are paused until they set one.
        if (!backupRepository.isBackupProtected()) {
            notifyBackupsPaused()
            return Result.success()
        }
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

    /** Post a quiet, deduped reminder that automatic backups can't run without a recovery password. */
    private fun notifyBackupsPaused() {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Backup health", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Warns when automatic backups can't run."
            }
            applicationContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Auto-backup is paused")
            .setContentText("Set a backup password in Spends so your daily backups can run.")
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
        try {
            manager.notify(NOTIF_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify — ignore.
        }
    }

    companion object {
        const val UNIQUE_NAME = "drive-auto-backup"
        private const val CHANNEL_ID = "backup_health"
        private const val NOTIF_ID = 90_210
    }
}
