package com.spends.app.ui.capture

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.CaptureNotifier
import com.spends.app.data.capture.SmsDebugLog
import com.spends.app.ui.components.SectionLabel
import com.spends.app.ui.components.SpendsCard
import kotlinx.coroutines.launch

/**
 * TEMPORARY owner-facing diagnostic for **live SMS capture** — the sibling of [NotificationDebugScreen].
 *
 * Live capture fails silently at nine different points and the phone shows the same thing for all of
 * them: nothing. The notification side has had a screen like this since v1.57.0, which is why its
 * failures can be narrowed down in minutes; the SMS side had none, and a July 2026 investigation into
 * total capture loss ran out of road because of it.
 *
 * The load-bearing line is "SMS delivered to Spends". If that stays at zero while texts are visibly
 * arriving on the phone, Android is not handing them to the app and no change inside Spends can fix it.
 *
 * Nothing here is stored on disk or backed up; it all disappears when the app's process restarts.
 * Remove this screen and [SmsDebugLog] once the root cause is fixed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsDebugScreen(
    onBack: () -> Unit,
    viewModel: SmsDebugViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // All three are system settings the owner may have just changed in Android's own screens, so they
    // are re-read every time this screen comes back to the front rather than captured once.
    //
    // RECEIVE_SMS and READ_SMS are read SEPARATELY and never conflated. Live capture needs only
    // RECEIVE_SMS; READ_SMS exists for "Scan past SMS". They are requested together, so they normally
    // move together — but OEM permission managers (MIUI, ColorOS) list them as two switches, and
    // reporting a missing READ_SMS as "nothing can arrive" would blame a permission that isn't the
    // problem while live capture is working perfectly.
    fun readGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var receiveGranted by remember { mutableStateOf(readGranted(Manifest.permission.RECEIVE_SMS)) }
    var readSmsGranted by remember { mutableStateOf(readGranted(Manifest.permission.READ_SMS)) }
    var promptsVisible by remember { mutableStateOf(CaptureNotifier.promptsCanBeSeen(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                receiveGranted = readGranted(Manifest.permission.RECEIVE_SMS)
                readSmsGranted = readGranted(Manifest.permission.READ_SMS)
                promptsVisible = CaptureNotifier.promptsCanBeSeen(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val log = state.log

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMS debug") },
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
                "Temporary tool. Wait for a real bank text (or send yourself any text), then read this " +
                    "page — or tap Copy report and paste it into chat. Nothing here is saved to your " +
                    "phone or your backups; it clears when the app restarts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            // ---- The verdict: the one line that says which link is broken ----
            SpendsCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        smsVerdictOf(
                            receiveGranted = receiveGranted,
                            demoMode = state.demoMode,
                            captureEnabled = state.captureEnabled,
                            promptsCanBeSeen = promptsVisible,
                            graphFailures = state.graphFailures,
                            log = log,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    if (state.demoMode) SmsStatusRow("Demo mode", "ON — real texts ignored")
                    SmsStatusRow("Receive SMS permission", smsYesNo(receiveGranted))
                    SmsStatusRow("Read SMS (Scan past SMS only)", smsYesNo(readSmsGranted))
                    SmsStatusRow("\"Detect from bank SMS\" on", smsYesNo(state.captureEnabled))
                    SmsStatusRow("Prompt can be shown", smsYesNo(promptsVisible))
                    if (state.graphFailures > 0) SmsStatusRow("App start-up failures", state.graphFailures.toString())
                    // Scoped to "this app run", not "this session": the log dies with the process, so a
                    // freshly-started app legitimately shows 0. Saying "session" invited reading a cold
                    // start as evidence of a delivery failure.
                    SmsStatusRow("SMS delivered (this app run)", log.totalReceived.toString())
                    SmsStatusRow(
                        "Last one reached the app",
                        log.lastReceivedAt?.let { DateUtils.formatDayTime(it) } ?: "never this app run",
                    )
                    SmsStatusRow("…from a bank we recognise", log.fromKnownBanks.toString())
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    clipboard.setText(
                        AnnotatedString(
                            viewModel.buildReport(receiveGranted, readSmsGranted, promptsVisible),
                        ),
                    )
                    scope.launch { snackbarHost.showSnackbar("Report copied") }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy report") }
            // Two earlier drafts of this paragraph promised more than the code delivered, and both were
            // caught in review. The first said personal messages contribute "no sender" — false, since
            // only senders without letters are masked. The second said passcodes "never leave the phone"
            // while only NON-transactions were masked, so the three commonest Indian OTP formats (which
            // contain "debited"/"spent" and therefore parse as real transactions) were exported intact.
            // Every stored body is now masked unconditionally, so this paragraph is finally true — and
            // the fix was to widen the code, not to narrow the promise.
            Text(
                "The report lists the sender name of every text Spends received — that is how a bank " +
                    "whose name has changed gets spotted — but the WORDS only from senders it " +
                    "recognised as banks, with every number replaced by \"#\", addresses removed and " +
                    "links cut back to their site. The amount Spends read is shown separately. Phone " +
                    "numbers and email addresses never appear; a person's name written inside an alert " +
                    "can. Have a quick look before you send it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = viewModel::clear, modifier = Modifier.fillMaxWidth()) {
                Text("Clear what's recorded")
            }

            // ---- Per-message detail ----
            Spacer(Modifier.height(20.dp))
            // "kept", not a total: the ring holds the newest 60, so on a busy phone this is fewer than
            // the delivered count above it. Naming it avoids reading the gap as messages going missing.
            SectionLabel("Messages kept (${log.entries.size}, newest first)")
            Spacer(Modifier.height(6.dp))
            if (log.entries.isEmpty()) {
                Text(
                    if (log.totalReceived == 0) {
                        "Nothing yet. Leave the app open and send yourself any text — if this stays " +
                            "empty, Android isn't delivering SMS to Spends at all, which is the " +
                            "answer in itself."
                    } else {
                        "Counted ${log.totalReceived}, but none recorded in detail yet."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            log.entries.forEach { e ->
                Spacer(Modifier.height(8.dp))
                SpendsCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                DateUtils.formatDayTime(e.timeMillis),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                e.institution ?: "not a known bank",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(plainSmsOutcome(e.outcome), style = MaterialTheme.typography.bodyMedium)
                        e.detail?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        SmsDebugField("from", e.sender)
                        SmsDebugField("text", e.body)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SmsStatusRow(label: String, value: String) {
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
private fun SmsDebugField(label: String, value: String?) {
    Text(
        "$label: ${value ?: "(not kept)"}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

private fun smsYesNo(v: Boolean) = if (v) "Yes" else "No"

/** Plain-English name for where a message stopped. */
private fun plainSmsOutcome(o: SmsDebugLog.Outcome): String = when (o) {
    SmsDebugLog.Outcome.DEMO_MODE -> "Ignored — demo mode is on, so real alerts are left alone"
    SmsDebugLog.Outcome.NO_MESSAGE_DATA -> "Arrived with no readable message in it"
    SmsDebugLog.Outcome.BLANK_BODY -> "Arrived, but the message text was empty"
    SmsDebugLog.Outcome.CAPTURE_OFF -> "Ignored — the \"Detect from bank SMS\" switch is off"
    SmsDebugLog.Outcome.APP_NOT_READY -> "⚠️ Arrived, but Spends couldn't start up to handle it"
    SmsDebugLog.Outcome.SENDER_NOT_RECOGNISED -> "The sender isn't a bank Spends knows"
    SmsDebugLog.Outcome.NOT_A_TRANSACTION -> "From a known bank, but not a transaction (OTP / promo / statement)"
    // Not "✅ Queued": when the row was already held nothing was inserted this time, and the suppression
    // itself — sticky state from ignoring the alert 3+ times — is the thing worth surfacing here.
    SmsDebugLog.Outcome.PATTERN_SUPPRESSED -> "Suppressed — you've ignored this exact alert before, so it goes to the review queue silently"
    SmsDebugLog.Outcome.ALREADY_KNOWN -> "A transaction Spends already has"
    // Deliberately "claimed", not "prompted": when prompts are blocked at OS level the twin claims the
    // slot and then queues instead of showing anything, so "already prompted" would assert a prompt the
    // owner never saw — in the exact scenario this screen is being used to diagnose.
    SmsDebugLog.Outcome.TWIN_ALREADY_PROMPTED -> "Its notification twin claimed this payment — one prompt per payment"
    SmsDebugLog.Outcome.PROMPT_BLOCKED ->
        "⚠️ Read it fine, but your phone won't show the prompt — queued in your review list instead"
    SmsDebugLog.Outcome.PROMPTED -> "✅ Showed the Review & Add prompt"
}
