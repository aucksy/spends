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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.time.DateUtils
import com.spends.app.data.backup.DriveFile
import com.spends.app.ui.importer.ImportColumnsHelpDialog

/**
 * Saves the chosen export window (start, end) across a config change / process-death that can happen while
 * the SAF "create document" picker is foreground. Without this the window would reset to null and the
 * returning picker result would skip the write, leaving a stray 0-byte .xlsx (#4).
 */
private val exportWindowSaver = listSaver<Pair<Long, Long>?, Long>(
    save = { p -> if (p == null) emptyList() else listOf(p.first, p.second) },
    restore = { l -> if (l.size == 2) l[0] to l[1] else null },
)

@Composable
fun BackupSection(viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingRestore by remember { mutableStateOf<DriveFile?>(null) }
    var pendingFileRestore by remember { mutableStateOf<Uri?>(null) }
    var showSetPassword by remember { mutableStateOf(false) }
    var showRemovePassword by remember { mutableStateOf(false) }
    var showBackupTimePicker by remember { mutableStateOf(false) }

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
        // Encryption is OPTIONAL (#8). Off by default — backups are plaintext and restore on ANY device
        // with no password, so a forgotten password can never lock the user out. Turn it on to encrypt;
        // then the password is needed to restore on a new phone.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Icon(
                if (state.hasBackupPassword) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Backup encryption (optional)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (state.hasBackupPassword) {
                        "On — backups are encrypted. You'll need this password to restore on a new phone."
                    } else {
                        "Off — backups are unencrypted and restore on any device with no password. Add a password to encrypt."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { if (state.hasBackupPassword) showRemovePassword = true else showSetPassword = true }) {
                Text(if (state.hasBackupPassword) "Remove" else "Add password")
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
                // No password gate (#8) — a backup always writes real bytes (encrypted or plaintext).
                onClick = viewModel::backupNow,
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
                onCheckedChange = { enabled -> viewModel.setAutoBackup(enabled) },
            )
        }

        // Time picker for the daily backup (#11) — only when it's on. Explains the offline behaviour: a
        // backup needs a connection, so an offline phone simply runs it later, once it's back online.
        if (state.autoBackupEnabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showBackupTimePicker = true }
                    .padding(vertical = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Backup time", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Runs around this time each day. If you're offline, it backs up later once you're back online.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    formatBackupTime(state.autoBackupMinuteOfDay),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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

    if (showBackupTimePicker) {
        BackupTimePickerDialog(
            initialMinute = state.autoBackupMinuteOfDay,
            onConfirm = { minute -> viewModel.setAutoBackupTime(minute); showBackupTimePicker = false },
            onDismiss = { showBackupTimePicker = false },
        )
    }

    if (showRemovePassword) {
        AlertDialog(
            onDismissRequest = { showRemovePassword = false },
            title = { Text("Remove backup password?") },
            text = {
                Text(
                    "Future backups will be unencrypted and restorable on any device with no password. " +
                        "Backups you already made stay encrypted and still open on this phone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.removeBackupPassword(); showRemovePassword = false }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { showRemovePassword = false }) { Text("Cancel") } },
        )
    }

    // Hide while the restore runs so the Drive-row spinner is visible; a wrong password leaves it pending,
    // so it reappears showing the error (#3).
    if (state.passwordRestore != null && !state.working) {
        RestorePasswordDialog(
            onConfirm = viewModel::restoreWithPassword,
            onCancel = viewModel::cancelPasswordRestore,
            errorMessage = state.message,
            onClearError = viewModel::clearMessage,
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
    val salaryDay by viewModel.salaryDay.collectAsStateWithLifecycle()
    val earliestDay by viewModel.earliestDay.collectAsStateWithLifecycle()
    var showHelp by remember { mutableStateOf(false) }
    var showExportCycle by remember { mutableStateOf(false) }
    // The chosen cycle window (start, end), held between launching the file picker and its result — the SAF
    // CreateDocument contract only carries a filename, not our window. rememberSaveable so it survives a
    // rotation / process-death while the picker is foreground (else the returning result would skip the
    // write and strand a 0-byte file).
    var pendingExport by rememberSaveable(stateSaver = exportWindowSaver) { mutableStateOf<Pair<Long, Long>?>(null) }
    val excelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri ->
        val window = pendingExport
        if (uri != null && window != null) viewModel.exportExcel(uri, window.first, window.second)
        pendingExport = null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Export a readable spreadsheet, or import transactions from one. Importing adds new rows and skips duplicates.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            OutlinedButton(onClick = { showExportCycle = true }, enabled = !state.working, modifier = Modifier.weight(1f)) {
                Text("Export Excel", maxLines = 1)
            }
            OutlinedButton(onClick = onImport, enabled = !state.working, modifier = Modifier.weight(1f)) {
                Text("Import Excel/CSV", maxLines = 1)
            }
        }
        TextButton(onClick = { showHelp = true }) { Text("Which columns does import need?") }
    }

    if (showExportCycle) {
        ExportCycleSheet(
            salaryDay = salaryDay,
            earliestDayMillis = earliestDay,
            onDismiss = { showExportCycle = false },
            onExport = { start, end, label ->
                showExportCycle = false
                pendingExport = start to end
                excelLauncher.launch(viewModel.excelFileNameFor(label))
            },
        )
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
private fun RestorePasswordDialog(
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    errorMessage: String? = null,
    onClearError: () -> Unit = {},
) {
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
                    onValueChange = { password = it; if (errorMessage != null) onClearError() },
                    label = "Backup password",
                    isError = errorMessage != null,
                )
                // Surface a wrong-password error inside the dialog (the section message is occluded by it).
                errorMessage?.let { msg ->
                    Spacer(Modifier.padding(vertical = 4.dp))
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Pick the time-of-day the daily Drive backup aims for (#11). Mirrors the auto-dark time picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupTimePickerDialog(initialMinute: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val timeState = rememberTimePickerState(
        initialHour = (initialMinute / 60).coerceIn(0, 23),
        initialMinute = (initialMinute % 60).coerceIn(0, 59),
        is24Hour = false,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily backup time") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                TimePicker(state = timeState)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(timeState.hour * 60 + timeState.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Minutes-of-day → a friendly "h:mm AM/PM" label. */
private fun formatBackupTime(minute: Int): String {
    val h = minute / 60
    val m = minute % 60
    val period = if (h < 12) "AM" else "PM"
    val h12 = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "%d:%02d %s".format(h12, m, period)
}
