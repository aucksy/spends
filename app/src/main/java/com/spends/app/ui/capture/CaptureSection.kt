package com.spends.app.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CaptureSection(
    onOpenReview: () -> Unit,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showScan by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* if denied, live prompts just won't show */ }

    fun requestNotifIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.READ_SMS] == true && result[Manifest.permission.RECEIVE_SMS] == true
        if (granted) {
            viewModel.enableWithGrant()
            requestNotifIfNeeded()
        } else {
            viewModel.permissionDenied()
        }
    }

    fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Icon(Icons.Filled.Sms, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Capture from bank SMS", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Never added automatically — you review and confirm each one. New texts ask you; " +
                        "use \"Scan past SMS\" for older ones. Parsed locally, nothing is uploaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.working) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { turnOn ->
                        if (turnOn) {
                            if (hasSmsPermission()) {
                                viewModel.enableWithGrant(); requestNotifIfNeeded()
                            } else {
                                permissionLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                            }
                        } else {
                            viewModel.disable()
                        }
                    },
                )
            }
        }

        if (state.enabled) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenReview).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Review queue", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (state.pendingCount > 0) "${state.pendingCount} waiting for you to confirm" else "Nothing waiting",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            OutlinedButton(onClick = { showScan = true }, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
                Text("Scan past SMS")
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hide bulk-scanned in timeline", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Keeps transactions from a past-SMS scan out of the timeline list (still counted in totals). " +
                            "Live notification adds always stay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.hideCaptured, onCheckedChange = viewModel::setHideCaptured)
            }

            // A proper (outlined) button, greyed only when there's genuinely nothing to delete — neither a
            // review-queue item NOR an already-added scanned transaction (#7). Gating on the queue alone
            // would hide the "delete added" path once the queue drains.
            OutlinedButton(
                onClick = { showDelete = true },
                enabled = !state.working && (state.pendingCount > 0 || state.capturedCount > 0),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Delete scanned SMS data")
            }
        }

        state.message?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
        }
    }

    if (showScan) {
        AlertDialog(
            onDismissRequest = { showScan = false },
            title = { Text("Scan past SMS") },
            text = {
                Column {
                    Text(
                        "Everything found is queued in the review list — nothing is added automatically, " +
                            "and you'll need to confirm each one. Choose how far back to look:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    ScanOption("This month") { viewModel.scanLastMonths(1); showScan = false }
                    ScanOption("Last 3 months") { viewModel.scanLastMonths(3); showScan = false }
                    ScanOption("Last 6 months") { viewModel.scanLastMonths(6); showScan = false }
                    ScanOption("Last 12 months") { viewModel.scanLastMonths(12); showScan = false }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showScan = false }) { Text("Cancel") } },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete scanned SMS data") },
            text = {
                Column {
                    Text(
                        "Choose what to clear. Live notification adds and your manual entries are always kept. " +
                            "This can't be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    DeleteOption(
                        title = "Clear review queue only",
                        subtitle = if (state.pendingCount > 0) {
                            "${state.pendingCount} waiting — removes them from review without adding"
                        } else {
                            "Nothing waiting to review"
                        },
                        enabled = state.pendingCount > 0,
                    ) { showDelete = false; viewModel.clearReviewQueue() }
                    DeleteOption(
                        title = "Clear queue + delete added",
                        subtitle = if (state.capturedCount > 0) {
                            "Also deletes ${state.capturedCount} scan-added transaction${if (state.capturedCount == 1) "" else "s"} from the timeline"
                        } else {
                            "Also deletes transactions already added to the timeline from past-SMS scans"
                        },
                        enabled = state.pendingCount > 0 || state.capturedCount > 0,
                    ) { showDelete = false; viewModel.clearQueueAndDeleteAdded() }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeleteOption(title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    val titleColor = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = titleColor)
    }
}

@Composable
private fun ScanOption(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
