package com.spends.app.ui.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.time.DateUtils
import com.spends.app.data.backup.DriveFile

@Composable
fun BackupSection(viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingRestore by remember { mutableStateOf<DriveFile?>(null) }
    var pendingFileRestore by remember { mutableStateOf<Uri?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onConsentResult(result.data) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip"),
    ) { uri -> uri?.let(viewModel::exportToFile) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingFileRestore = uri }

    LaunchedEffect(Unit) {
        viewModel.consentRequests.collect { intentSender ->
            consentLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Icon(Icons.Filled.CloudUpload, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Google Drive backup", style = MaterialTheme.typography.bodyLarge)
                Text(
                    state.lastBackupAt?.let { "Last backup: ${DateUtils.formatDay(it)} at ${DateUtils.formatTime(it)}" }
                        ?: "Saved to a \"Spends Backup\" folder in your Google Drive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.working) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Button(onClick = viewModel::backupNow, enabled = !state.working, modifier = Modifier.weight(1f)) {
                Text("Back up now")
            }
            OutlinedButton(onClick = viewModel::openRestore, enabled = !state.working, modifier = Modifier.weight(1f)) {
                Text("Restore")
            }
        }

        // Daily auto-backup.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Daily auto-backup", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Backs up to Drive once a day. Do one manual backup first to grant access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.autoBackupEnabled, onCheckedChange = viewModel::setAutoBackup)
        }

        // Local file backup (no account needed).
        Text(
            "On this device",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            OutlinedButton(
                onClick = { exportLauncher.launch(viewModel.exportFileName) },
                enabled = !state.working,
                modifier = Modifier.weight(1f),
            ) { Text("Export to file") }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/gzip", "application/octet-stream", "*/*")) },
                enabled = !state.working,
                modifier = Modifier.weight(1f),
            ) { Text("Restore from file") }
        }

        state.message?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
        }
    }

    // Drive restore picker
    val backups = state.backups
    if (backups != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRestore,
            title = { Text("Restore from Drive") },
            text = {
                if (backups.isEmpty()) {
                    Text("No backups found in Drive yet.")
                } else {
                    LazyColumn {
                        items(backups, key = { it.id }) { file ->
                            Column(
                                modifier = Modifier.fillMaxWidth().clickable { pendingRestore = file }.padding(vertical = 10.dp),
                            ) {
                                Text(file.modifiedTime?.take(16)?.replace("T", "  ") ?: file.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissRestore) { Text("Close") } },
        )
    }

    pendingRestore?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Replace all data?") },
            text = { Text("This replaces your current transactions, categories and settings with this backup. A safety copy of your current data is saved to Drive first.") },
            confirmButton = {
                TextButton(onClick = { viewModel.restore(file.id); pendingRestore = null }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }

    pendingFileRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingFileRestore = null },
            title = { Text("Replace all data?") },
            text = { Text("This replaces your current transactions, categories and settings with the contents of the chosen file. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.importFromFile(uri); pendingFileRestore = null }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingFileRestore = null }) { Text("Cancel") } },
        )
    }
}
