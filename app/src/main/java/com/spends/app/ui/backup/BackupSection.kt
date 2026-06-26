package com.spends.app.ui.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
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
import com.spends.app.ui.importer.ImportColumnsHelpDialog

@Composable
fun BackupSection(viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingRestore by remember { mutableStateOf<DriveFile?>(null) }
    var pendingFileRestore by remember { mutableStateOf<Uri?>(null) }
    var showSetPassword by remember { mutableStateOf(false) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onConsentResult(result.data) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
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
        // Encryption — backups are unreadable without this password if the file is ever accessed by someone
        // else, AND a password is required before any backup can run (so a backup can never silently no-op).
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Icon(
                if (state.hasBackupPassword) Icons.Filled.Lock else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (state.hasBackupPassword) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Backup encryption", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (state.hasBackupPassword) {
                        "On — backups are encrypted. You'll need this password to restore on a new phone."
                    } else {
                        "Backups are OFF. Set a password first — it encrypts your backups and lets you restore on a new phone."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.hasBackupPassword) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = { showSetPassword = true }) {
                Text(if (state.hasBackupPassword) "Change" else "Set password")
            }
        }

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
            Button(
                // Require a password first so a backup always writes real, recoverable bytes (#7).
                onClick = { if (state.hasBackupPassword) viewModel.backupNow() else showSetPassword = true },
                enabled = !state.working,
                modifier = Modifier.weight(1f),
            ) { Text("Back up now") }
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
            Switch(
                checked = state.autoBackupEnabled,
                // Can't enable daily backups without a password, or they'd silently do nothing (#7).
                onCheckedChange = { enabled ->
                    if (enabled && !state.hasBackupPassword) showSetPassword = true else viewModel.setAutoBackup(enabled)
                },
            )
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
                // Gate the file picker on a password so it never pre-creates a doc we then leave empty (#7).
                onClick = { if (state.hasBackupPassword) exportLauncher.launch(viewModel.exportFileName) else showSetPassword = true },
                enabled = !state.working,
                modifier = Modifier.weight(1f),
            ) { Text("Export file", maxLines = 1) }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/gzip", "*/*")) },
                enabled = !state.working,
                modifier = Modifier.weight(1f),
            ) { Text("Restore file", maxLines = 1) }
        }

        state.message?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
        }
    }

    // Drive restore picker — tapping a tile dismisses this list and opens the confirm dialog, so the
    // picker never pops straight back open (#12).
    val backups = state.backups
    if (backups != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRestore,
            title = { Text("Restore from Drive") },
            text = {
                DriveBackupList(
                    backups = backups,
                    onPick = { file ->
                        pendingRestore = file
                        viewModel.dismissRestore()
                    },
                )
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

    if (showSetPassword) {
        SetBackupPasswordDialog(
            changing = state.hasBackupPassword,
            onConfirm = { pw -> viewModel.setBackupPassword(pw); showSetPassword = false },
            onDismiss = { showSetPassword = false },
        )
    }

    if (state.passwordRestore != null) {
        RestorePasswordDialog(
            onConfirm = viewModel::restoreWithPassword,
            onCancel = viewModel::cancelPasswordRestore,
        )
    }

    // A LOUD, must-acknowledge failure (e.g. a backup that produced no bytes) — never a calm toast (#7).
    state.blockingError?.let { err ->
        AlertDialog(
            onDismissRequest = viewModel::clearBlockingError,
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Backup didn't save") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = viewModel::clearBlockingError) { Text("OK") } },
        )
    }
}

/**
 * Spreadsheet export/import — kept visually separate from Backup (#3). Export writes a readable .xlsx;
 * import goes through the merge-and-dedupe flow ([onImport] opens it). A help link explains the columns.
 */
@Composable
fun SpreadsheetSection(
    onImport: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showHelp by remember { mutableStateOf(false) }
    val excelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri -> uri?.let(viewModel::exportExcel) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Export a readable spreadsheet, or import transactions from one. Importing adds new rows and skips duplicates.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            OutlinedButton(onClick = { excelLauncher.launch(viewModel.excelFileName) }, enabled = !state.working, modifier = Modifier.weight(1f)) {
                Text("Export Excel", maxLines = 1)
            }
            OutlinedButton(onClick = onImport, enabled = !state.working, modifier = Modifier.weight(1f)) {
                Text("Import Excel/CSV", maxLines = 1)
            }
        }
        TextButton(onClick = { showHelp = true }) { Text("Which columns does import need?") }
    }

    if (showHelp) ImportColumnsHelpDialog(onDismiss = { showHelp = false })
}

@Composable
private fun SetBackupPasswordDialog(changing: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val tooShort = password.length < 6
    val mismatch = confirm.isNotEmpty() && password != confirm
    val valid = !tooShort && password == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (changing) "Change backup password" else "Set a backup password") },
        text = {
            Column {
                Text(
                    "You'll only need this when restoring on a new phone. Keep it safe — without it, an encrypted backup can't be recovered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(vertical = 6.dp))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password (min 6 characters)",
                )
                Spacer(Modifier.padding(vertical = 4.dp))
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = "Confirm password",
                    isError = mismatch,
                )
                if (mismatch) {
                    Text("Passwords don't match", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RestorePasswordDialog(onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Enter backup password") },
        text = {
            Column {
                Text(
                    "This backup was encrypted on another device. Enter the backup password you set to restore it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(vertical = 6.dp))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Backup password",
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
