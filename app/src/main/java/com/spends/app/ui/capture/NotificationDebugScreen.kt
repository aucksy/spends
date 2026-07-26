package com.spends.app.ui.capture

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.NotificationCaptureApps
import com.spends.app.data.capture.NotificationDebugLog
import com.spends.app.service.NotificationListenerControl
import com.spends.app.ui.components.SectionLabel
import com.spends.app.ui.components.SpendsCard
import kotlinx.coroutines.launch

/**
 * TEMPORARY owner-facing diagnostic for notification capture.
 *
 * Capture fails silently in four different places, so "nothing appeared" carries no information. This
 * shows what the notification reader actually received and where each message stopped — readable on
 * the phone, with a Copy button so it can be pasted straight back into chat. Nothing here is stored on
 * disk or backed up; it all disappears when the app's process restarts.
 *
 * Remove this screen, [NotificationDebugLog] and `NotificationCapture.diagnose` once the root cause is
 * fixed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationDebugScreen(
    onBack: () -> Unit,
    viewModel: NotificationDebugViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // The grant is a system setting, so re-read it every time this screen comes back to the front
    // (the owner may have just toggled notification access in Android Settings).
    var accessGranted by remember { mutableStateOf(NotificationListenerControl.hasAccess(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessGranted = NotificationListenerControl.hasAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val log = state.log

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification debug") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "Temporary tool. Reproduce a bank alert, then read this page (or tap Copy report and " +
                    "paste it into chat). Nothing here is saved to your phone or your backups — it " +
                    "clears when the app restarts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            // ---- The verdict: the one line that says which link is broken ----
            SpendsCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        verdictOf(accessGranted, state.captureEnabled, state.watchedApps.isEmpty(), log),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    StatusRow("Notification access granted", yesNo(accessGranted))
                    StatusRow("Reader connected right now", yesNo(log.connected))
                    StatusRow(
                        "Last connected",
                        log.lastConnectedAt?.let { DateUtils.formatDayTime(it) } ?: "never this session",
                    )
                    StatusRow("Capture switch on", yesNo(state.captureEnabled))
                    StatusRow(
                        "Watching",
                        state.watchedApps
                            .joinToString(", ") { NotificationCaptureApps.displayName(it) ?: it }
                            .ifBlank { "nothing ticked" },
                    )
                    StatusRow("Notifications seen (all apps)", log.totalSeen.toString())
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(viewModel.buildReport(accessGranted)))
                        scope.launch { snackbarHost.showSnackbar("Report copied") }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Copy report") }
                OutlinedButton(
                    onClick = {
                        NotificationListenerControl.requestRebind(context)
                        scope.launch { snackbarHost.showSnackbar("Asked Android to reconnect the reader") }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Reconnect") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = viewModel::clear, modifier = Modifier.fillMaxWidth()) {
                Text("Clear what's recorded")
            }

            // ---- Which apps are posting at all: proves whether Truecaller reaches us ----
            Spacer(Modifier.height(20.dp))
            SectionLabel("Apps that posted notifications")
            Spacer(Modifier.height(6.dp))
            if (log.packageCounts.isEmpty()) {
                Text(
                    "Nothing yet. If the reader says connected, unlock your phone and trigger any " +
                        "notification — this should start filling up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                log.packageCounts.forEach { (pkg, n) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            pkg,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Text("$n", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---- Per-message detail for the watched apps ----
            Spacer(Modifier.height(20.dp))
            SectionLabel("Alerts from the apps you're watching (${log.entries.size})")
            Spacer(Modifier.height(6.dp))
            if (log.entries.isEmpty()) {
                Text(
                    "Nothing from Google Messages or Truecaller yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            log.entries.forEach { e ->
                Spacer(Modifier.height(8.dp))
                SpendsCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${DateUtils.formatDayTime(e.timeMillis)} · ${e.packageName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(plainOutcome(e.outcome), style = MaterialTheme.typography.bodyMedium)
                        e.detail?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        DebugField("title", e.title)
                        DebugField("text", e.text)
                        DebugField("bigText", e.bigText)
                        DebugField("senders", e.messageSenders.joinToString(" | ").ifBlank { null })
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DebugField(label: String, value: String?) {
    Text(
        "$label: ${value ?: "(none)"}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

private fun yesNo(v: Boolean) = if (v) "Yes" else "No"

/** Plain-English name for where a message stopped. */
private fun plainOutcome(o: NotificationDebugLog.Outcome): String = when (o) {
    NotificationDebugLog.Outcome.SKIPPED_SHAPE -> "Skipped — not a message notification"
    NotificationDebugLog.Outcome.NO_READABLE_TEXT -> "No readable text (this is the RCS limit — nothing to parse)"
    NotificationDebugLog.Outcome.SENDER_NOT_RECOGNISED -> "Text was readable, but the sender isn't a bank Spends knows"
    NotificationDebugLog.Outcome.TOO_OLD -> "Skipped — older than the capture window"
    NotificationDebugLog.Outcome.ALREADY_SEEN -> "Already handled this exact message"
    NotificationDebugLog.Outcome.NOT_A_TRANSACTION -> "Read it, but it isn't a transaction (OTP / promo / statement)"
    NotificationDebugLog.Outcome.DUPLICATE -> "A transaction we already have"
    NotificationDebugLog.Outcome.QUEUED -> "✅ Queued in your review list"
    NotificationDebugLog.Outcome.PROMPTED -> "✅ Showed the Review & Add prompt"
}

/** The single sentence that says which link in the chain is broken. */
private fun verdictOf(
    accessGranted: Boolean,
    captureEnabled: Boolean,
    nothingTicked: Boolean,
    log: NotificationDebugLog.Snapshot,
): String = when {
    !accessGranted ->
        "Android hasn't given Spends notification access, so nothing can be read. " +
            "Settings → Apps → Special app access → Notification access → Spends."
    !captureEnabled ->
        "Notification access is granted, but the \"Detect from app notifications\" switch is off."
    nothingTicked ->
        "Capture is on but no apps are ticked, so there's nothing to watch."
    !log.connected ->
        "Access is granted but Android has NOT connected the reader — this is the bug. Tap Reconnect."
    log.totalSeen == 0 ->
        "Reader is connected but hasn't been handed a single notification yet. Trigger any " +
            "notification to confirm it's really working."
    log.entries.isEmpty() ->
        "The reader is working (${log.totalSeen} notifications seen), but nothing has arrived from " +
            "the apps you're watching. Check the package list below for the app your bank alert came from."
    else ->
        "The reader is seeing your watched apps. Each alert below says where it stopped."
}
