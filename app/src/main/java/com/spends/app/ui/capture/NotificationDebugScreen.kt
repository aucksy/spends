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
                        val asked = NotificationListenerControl.requestRebind(context)
                        scope.launch {
                            snackbarHost.showSnackbar(
                                if (asked) {
                                    "Asked Android to reconnect. If it still says No, use Open Android settings."
                                } else {
                                    "Notification access isn't granted — there's nothing to reconnect."
                                },
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Reconnect") }
            }
            Text(
                "The copied report includes the list of apps that notify you, the text of alerts from " +
                    "senders Spends recognised as banks, and — for every watched app, including personal " +
                    "chats — the sender names and conversation titles it saw, which for an unknown " +
                    "number is that number. Only the message BODY of a non-bank alert is withheld. " +
                    "Have a quick look before you send it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { NotificationListenerControl.openAccessSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open Android settings (turn access off, then on)") }
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
                state.packagesByCount.forEach { (pkg, n) ->
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
internal fun plainOutcome(o: NotificationDebugLog.Outcome): String = when (o) {
    NotificationDebugLog.Outcome.SKIPPED_SHAPE -> "Skipped — not a message notification"
    // "this IS the RCS limit" asserted a cause the code cannot know: NO_READABLE_TEXT fires for any
    // notification carrying no text anywhere, and a custom-layout Truecaller alert lands here too.
    NotificationDebugLog.Outcome.NO_READABLE_TEXT ->
        "No readable text at all — nothing to parse. Often the RCS limit, but any custom-layout alert looks the same"
    NotificationDebugLog.Outcome.MESSAGES_SHADOWED_BIG_TEXT ->
        "⚠️ Readable text was there but Spends looked in the wrong place — this one is a bug we can fix"
    NotificationDebugLog.Outcome.SENDER_NOT_RECOGNISED -> "Text was readable, but the sender isn't a bank Spends knows"
    NotificationDebugLog.Outcome.TOO_OLD -> "Skipped — older than the capture window"
    // Open-ended, matching the SMS screen: both paths take this verdict from the same SmsParser, so the
    // closed list was false here for exactly the same reason — a real SBI UPI debit or an HDFC IMPS
    // transfer the parser cannot read lands here and is a genuine money movement.
    NotificationDebugLog.Outcome.NOT_A_TRANSACTION ->
        "Read it, but Spends didn't read it as a transaction (OTP, promo, declined, statement — or a format it can't parse yet)"
    // NOT "a transaction we already have". One of this outcome's three call sites is the twin race,
    // where claimPrompt lost to the SMS twin — and at that moment nothing is held at all: the winner
    // posted a heads-up the owner may simply dismiss, and wrote nothing. Same false claim the SMS
    // screen removed from TWIN_ALREADY_PROMPTED, worded to be true at all three sites.
    NotificationDebugLog.Outcome.DUPLICATE ->
        "Not prompted again — Spends already has this, or another copy of the same alert claimed the prompt first"
    // No green tick: this covers the blocked-prompt fallback, which the SMS screen flags with a warning
    // because the owner never saw a prompt and needs to know why.
    NotificationDebugLog.Outcome.QUEUED -> "Queued in your review list (no prompt was shown)"
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
    // Right after a cold launch we legitimately aren't bound yet, so don't cry bug for a few seconds.
    !log.connected && log.lastConnectedAt == null && log.totalSeen == 0 ->
        "Not connected yet. Give it a few seconds — if it still says No, tap Reconnect, and if that " +
            "doesn't do it, turn notification access off and on in Android settings."
    !log.connected ->
        "Access is granted but Android has NOT connected the reader — this is the bug. Tap Reconnect; " +
            "if it stays No, turn notification access off and on in Android settings."
    log.totalSeen == 0 ->
        "Reader is connected but hasn't been handed a single notification yet. Trigger any " +
            "notification to confirm it's really working."
    log.entries.isEmpty() ->
        "The reader is working (${log.totalSeen} notifications seen), but nothing has arrived from " +
            "the apps you're watching. Check the package list below for the app your bank alert came from."
    else ->
        "The reader is seeing your watched apps. Each alert below says where it stopped."
}
