package com.spends.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spends.app.ui.backup.BackupSection

/**
 * "Backup & Restore" settings sub-page: encrypted backup to Drive / local file, restore, and the daily
 * auto-backup schedule — all provided by [BackupSection], lifted verbatim from the old settings page.
 */
@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
) {
    SettingsSubScaffold(title = "Backup & Restore", onBack = onBack) {
        SettingsSection("Backup") {
            BackupSection()
        }
        Spacer(Modifier.height(24.dp))
    }
}
