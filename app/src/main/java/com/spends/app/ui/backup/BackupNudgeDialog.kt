package com.spends.app.ui.backup

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.backup.BackupNudge
import com.spends.app.data.backup.BackupRepository
import com.spends.app.data.demo.DemoMode
import com.spends.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class BackupNudgeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    backupRepository: BackupRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _show = MutableStateFlow(false)
    val show: StateFlow<Boolean> = _show

    init {
        viewModelScope.launch {
            if (DemoMode.isEnabled(context)) return@launch
            val opened = BackupNudge.onOpened(BackupNudge.read(context), LocalDate.now().toEpochDay())
            val now = System.currentTimeMillis()
            val due = BackupNudge.shouldShow(
                opened,
                hasBackup = backupRepository.lastBackupAt.first() != null,
                autoBackupOn = settingsRepository.settings.first().autoBackupEnabled,
                now = now,
            )
            BackupNudge.write(context, if (due) BackupNudge.onShown(opened, now) else opened)
            _show.value = due
        }
    }

    fun dismiss() {
        _show.value = false
    }
}

/** Asks once in a while (see [BackupNudge]) to turn on Google Drive backup; "Set up backup" opens Settings → Backup. */
@Composable
fun BackupNudgeDialog(onSetUp: () -> Unit, viewModel: BackupNudgeViewModel = hiltViewModel()) {
    val show by viewModel.show.collectAsState()
    if (!show) return
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text("Back up your spends") },
        text = {
            Text(
                "Right now your spends are only on this phone. If you lose or change your phone, they are gone.\n\n" +
                    "Turn on Google Drive backup, and Spends keeps a copy in your own Drive every day. It takes about a minute."
            )
        },
        confirmButton = {
            TextButton(onClick = { viewModel.dismiss(); onSetUp() }) { Text("Set up backup") }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismiss) { Text("Not now") }
        },
    )
}
