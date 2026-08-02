package com.spends.app.ui.capture

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.spends.app.data.capture.SilencedAlert
import com.spends.app.data.capture.SmsCaptureRepository

/**
 * The undo for learn-from-ignore (#7): every alert Spends has stopped asking about, and a one-tap way
 * to start asking again. Before this screen the suppression was invisible and permanent — three
 * accidental taps on "Ignore" silenced a genuine recurring alert for good.
 *
 * Patterns that are only part-way to the threshold are listed too. That is the whole warning the owner
 * gets: "ignored twice" is the last moment at which the decision is still reversible by doing nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SilencedAlertsScreen(
    onBack: () -> Unit,
    onOpenReview: () -> Unit,
    viewModel: SilencedAlertsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmUnsilenceAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBarWithBack(title = "Silenced alerts", onBack = onBack)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                "When you tap \"Ignore\" on the same alert " +
                    "${SmsCaptureRepository.IGNORE_SUPPRESS_THRESHOLD} times, Spends stops asking about " +
                    "it. Those transactions are still saved to your review list — they are never lost, " +
                    "you just aren't interrupted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.loaded && state.isEmpty) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Nothing is silenced.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Anything you silence by mistake will show up here, with a button to switch it back on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.silenced.isNotEmpty()) {
                SectionHeader("Not asking any more")
                state.silenced.forEach { alert ->
                    SilencedAlertRow(
                        alert = alert,
                        actionLabel = "Ask me again",
                        onAction = { viewModel.unsilence(alert) },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "These still land in your review list. Open it to add anything you actually want.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenReview, modifier = Modifier.padding(top = 2.dp)) {
                    Text("Open review list")
                }
            }

            if (state.approaching.isNotEmpty()) {
                SectionHeader("Close to being silenced")
                state.approaching.forEach { alert ->
                    SilencedAlertRow(
                        alert = alert,
                        actionLabel = "Reset",
                        onAction = { viewModel.unsilence(alert) },
                    )
                }
            }

            if (!state.isEmpty) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { confirmUnsilenceAll = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ask me about everything again") }
            }

            state.message?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmUnsilenceAll) {
        AlertDialog(
            onDismissRequest = { confirmUnsilenceAll = false },
            title = { Text("Ask me about everything again") },
            text = {
                Text(
                    "Spends will start asking about every alert it had stopped asking about, and forgets " +
                        "how many times you ignored each one. Nothing on your timeline changes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsilenceAll = false
                    viewModel.unsilenceAll()
                }) { Text("Ask me again") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnsilenceAll = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopAppBarWithBack(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HorizontalDivider(Modifier.padding(top = 4.dp))
}

@Composable
private fun SilencedAlertRow(alert: SilencedAlert, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(alert.title(), style = MaterialTheme.typography.bodyLarge)
            // What the row actually covers. Since v1.69.0 a row is EVERY amount from this source in this
            // direction, not one figure, and that has to be visible before the owner taps Reset.
            Text(
                alert.scopeLine(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                statusLine(alert),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}

/** Plain-English state of one pattern: what happened, and what happens next if nothing changes. */
private fun statusLine(alert: SilencedAlert): String {
    val last = "last ignored ${DateUtils.formatDay(alert.lastIgnoredAt)}"
    return if (alert.isSilenced) {
        "Ignored ${alert.ignoreCount} times · $last"
    } else {
        val remaining = alert.ignoresUntilSilenced
        val more = if (remaining == 1) "1 more Ignore" else "$remaining more Ignores"
        "Ignored ${times(alert.ignoreCount)} · $more and Spends stops asking · $last"
    }
}

private fun times(n: Int): String = when (n) {
    1 -> "once"
    2 -> "twice"
    else -> "$n times"
}
