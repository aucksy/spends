package com.spends.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.ui.backup.PasswordField

/**
 * "AI helper" settings sub-page (docs/AI-RESEARCH.md §2.1). Master switch (OFF by default), the BYOK Groq key
 * field + Test button, the two sub-toggles, and a plain-English "what leaves your phone" explainer. Turning the
 * master switch ON is gated behind a first-enable privacy dialog (the first time any data would leave the phone).
 */
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val testStatus by viewModel.testStatus.collectAsStateWithLifecycle()
    // Not rememberSaveable: a typed-but-unsaved key must not be serialised into the instance-state bundle.
    var keyInput by remember { mutableStateOf("") }
    var showEnableConfirm by remember { mutableStateOf(false) }
    val semantic = LocalSemanticColors.current

    SettingsSubScaffold(title = "AI helper", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        Text(
            "An optional helper powered by your own free Groq key. It only suggests categories and writes a " +
                "short spending summary — it can never add, edit, or change a transaction or an amount. Turn it " +
                "off and Spends works exactly as before.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsSection("AI helper") {
            SwitchRow(
                title = "Use AI helper",
                subtitle = if (state.aiEnabled) "On — set your key and pick features below" else "Off — Spends stays 100% offline",
                checked = state.aiEnabled,
                onChange = { on -> if (on) showEnableConfirm = true else viewModel.setEnabled(false) },
            )
        }

        if (state.aiEnabled) {
            SettingsSection("Your Groq key") {
                Text(
                    "Paste your own free key. It's stored encrypted on this phone and is never included in a " +
                        "backup. Create one at console.groq.com/keys (no credit card needed).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                )
                PasswordField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = "Groq API key",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { viewModel.testKey(keyInput) },
                        enabled = testStatus != KeyTestStatus.Testing && (keyInput.isNotBlank() || hasKey),
                    ) { Text("Test key") }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = { viewModel.saveKey(keyInput); keyInput = "" },
                        enabled = keyInput.isNotBlank(),
                    ) { Text("Save key") }
                }
                TestStatusLine(testStatus, semantic.income)
                if (hasKey) {
                    RowDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "A key is saved on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.removeKey() }) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            SettingsSection("What AI can do") {
                SwitchRow(
                    title = "Smart category suggestions",
                    subtitle = "Suggest a category for detected bank SMS the rules can't place. You still confirm each one.",
                    checked = state.aiCategorize,
                    onChange = viewModel::setCategorize,
                )
                RowDivider()
                SwitchRow(
                    title = "Spending insights",
                    subtitle = "A short, friendly summary at the top of Analytics for the cycle you're viewing.",
                    checked = state.aiInsights,
                    onChange = viewModel::setInsights,
                )
                if (!hasKey) {
                    Text(
                        "Add a key above to activate these.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }

            SettingsSection("What leaves your phone") {
                Text(
                    "Only for the feature you switch on, and only these:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                )
                BulletLine(
                    "Suggestions: the merchant text from a detected SMS, your category names, and your saved " +
                        "merchant→category shortcuts (names only) so it can recognise a merchant you've tagged before.",
                )
                BulletLine("Insights: your category totals for the cycle.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Never sent: your SMS messages, amounts, balances, account or card numbers, dates, or any " +
                        "individual transaction.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showEnableConfirm) {
        AlertDialog(
            onDismissRequest = { showEnableConfirm = false },
            title = { Text("Turn on the AI helper?") },
            text = {
                Text(
                    "This is the first time any of your data would leave your phone. With it on — and only for the " +
                        "features you enable — a detected SMS's merchant name plus your saved merchant→category " +
                        "shortcuts (for category suggestions) and your category totals (for insights) are sent to " +
                        "Groq. Never your messages, amounts, balances, or account numbers. You'll paste your own " +
                        "free key next.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setEnabled(true); showEnableConfirm = false }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { showEnableConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TestStatusLine(status: KeyTestStatus, okColor: androidx.compose.ui.graphics.Color) {
    val (text, color) = when (status) {
        KeyTestStatus.Idle -> return
        KeyTestStatus.Testing -> "Testing…" to MaterialTheme.colorScheme.onSurfaceVariant
        KeyTestStatus.Working -> "Working ✓" to okColor
        is KeyTestStatus.Failed -> status.message to MaterialTheme.colorScheme.error
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun BulletLine(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
