package com.spends.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Demo mode control (Settings → Data & Trash).
 *
 * Both directions ask first, because both restart the app — an unexplained relaunch mid-demo would look like
 * a crash. The copy is deliberately concrete about the safety guarantee, since "hide all my real data" is a
 * frightening thing to tap without knowing what happens to it.
 */
@Composable
fun DemoModeSection(viewModel: DemoModeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val working by viewModel.working.collectAsStateWithLifecycle()
    val resetDone by viewModel.resetDone.collectAsStateWithLifecycle()
    val switchFailed by viewModel.switchFailed.collectAsStateWithLifecycle()
    var confirmSwitch by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var justReset by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(resetDone) {
        if (resetDone) {
            justReset = true
            viewModel.consumeResetDone()
        }
    }

    SettingsSection("Demo mode") {
        SwitchRow(
            title = "Demo mode",
            subtitle = if (viewModel.demoActive) {
                "On — you're looking at sample data. Your real data is untouched."
            } else {
                "Explore a sample account with 14 months of data. Your real data is hidden, not changed."
            },
            checked = viewModel.demoActive,
            onChange = { confirmSwitch = true },
        )
        if (viewModel.demoActive) {
            RowDivider()
            ClickableRow(
                title = "Reset demo data",
                value = when {
                    working -> "Rebuilding…"
                    justReset -> "Rebuilt, dated to today"
                    else -> "Start the sample account over"
                },
                onClick = { if (!working) confirmReset = true },
            )
        }
    }

    if (confirmSwitch) {
        val entering = !viewModel.demoActive
        AlertDialog(
            onDismissRequest = { confirmSwitch = false },
            title = { Text(if (entering) "Turn on demo mode?" else "Leave demo mode?") },
            text = {
                Column {
                    Text(
                        if (entering) {
                            "Spends will restart and open a sample account: 14 months of made-up transactions, " +
                                "cards, recurring bills and a review queue, so you can show every feature."
                        } else {
                            "Spends will restart and your real account comes back exactly as you left it."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (entering) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Your real data is not copied, changed or deleted — the app simply doesn't open it " +
                                "while demo mode is on. Backup, restore and automatic capture from SMS and " +
                                "notifications are paused until you turn it off.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.switchMode(context, intoDemo = entering) }) {
                    Text(if (entering) "Turn on and restart" else "Leave and restart")
                }
            },
            dismissButton = { TextButton(onClick = { confirmSwitch = false }) { Text("Cancel") } },
        )
    }

    if (switchFailed) {
        AlertDialog(
            onDismissRequest = { viewModel.consumeSwitchFailed() },
            title = { Text("Couldn't switch") },
            text = {
                Text(
                    "Spends couldn't restart itself, so nothing has changed — you're still in the mode you " +
                        "were in. Close Spends completely and open it again, then try once more.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.consumeSwitchFailed() }) { Text("OK") } },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset demo data?") },
            text = {
                Text(
                    "The sample account is rebuilt from scratch and dated to today. Anything you changed " +
                        "while demoing is discarded. Your real data is not involved.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    justReset = false
                    viewModel.resetDemoData()
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
