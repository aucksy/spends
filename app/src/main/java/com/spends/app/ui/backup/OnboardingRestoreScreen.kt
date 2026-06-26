package com.spends.app.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.data.backup.DriveFile

/**
 * Onboarding "Restore from Google Drive" flow (#2): sign in, pick a backup, restore. A successful
 * restore brings back the user's settings (incl. onboardingComplete), so we go straight Home. Reuses
 * [BackupViewModel] and the shared [DriveBackupList] / [PasswordField].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingRestoreScreen(
    onBack: () -> Unit,
    onRestored: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingRestore by remember { mutableStateOf<DriveFile?>(null) }
    // Once a restore is actually running, show a full-screen "Restoring…" state (#5).
    var restoreInitiated by remember { mutableStateOf(false) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onConsentResult(result.data) }

    LaunchedEffect(Unit) {
        viewModel.consentRequests.collect { intentSender ->
            consentLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }
    }

    // When a restore finishes, drop the user into the app with their data.
    LaunchedEffect(state.restoreComplete) {
        if (state.restoreComplete) {
            viewModel.consumeRestoreComplete()
            onRestored()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Restore your data") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (restoreInitiated && state.working) {
                // The restore is actively running — a clear themed state so it doesn't look frozen (#5).
                CircularProgressIndicator()
                Spacer(Modifier.height(20.dp))
                Text("Restoring your data…", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Bringing back your transactions, categories and settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                Icon(
                    Icons.Filled.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                Text("Restore from Google Drive", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sign in with the same Google account you backed up with, then pick a backup. Your " +
                        "transactions, categories and settings will be restored to this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                if (state.working) {
                    CircularProgressIndicator()
                } else {
                    // Reset the flag before (re)listing so the "Restoring…" overlay can't misfire during a
                    // mere backup-list fetch after a failed/cancelled first attempt.
                    Button(onClick = { restoreInitiated = false; viewModel.openRestore() }, modifier = Modifier.height(52.dp)) {
                        Text("Choose a Drive backup")
                    }
                }
                state.message?.let { msg ->
                    Spacer(Modifier.height(16.dp))
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                }
            }
        }
    }

    val backups = state.backups
    if (backups != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRestore,
            title = { Text("Choose a backup") },
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
            title = { Text("Restore this backup?") },
            text = { Text("This loads the backup's transactions, categories and settings onto this phone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.restore(file.id); restoreInitiated = true; pendingRestore = null }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }

    if (state.passwordRestore != null) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::cancelPasswordRestore,
            title = { Text("Enter backup password") },
            text = {
                Column {
                    Text(
                        "This backup was encrypted on another device. Enter the backup password you set to restore it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    PasswordField(
                        value = password,
                        onValueChange = { password = it; if (state.message != null) viewModel.clearMessage() },
                        label = "Backup password",
                        isError = state.message != null,
                    )
                    // Surface a wrong-password error INSIDE the dialog (the bottom-of-screen message is
                    // occluded by this popup).
                    state.message?.let { msg ->
                        Spacer(Modifier.height(8.dp))
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { restoreInitiated = true; viewModel.restoreWithPassword(password) }, enabled = password.isNotEmpty()) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelPasswordRestore) { Text("Cancel") } },
        )
    }
}
