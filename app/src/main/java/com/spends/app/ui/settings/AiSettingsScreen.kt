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
            "An optional helper powered by your own free Groq key. It suggests categories, and puts your own " +
                "figures into plain English on the Analytics insight cards — it can never add, edit, or " +
                "change a transaction or an amount, and every number on those cards is worked out on your " +
                "phone before the AI sees it. Turn it off and Spends works exactly as before.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsSection("AI helper") {
            SwitchRow(
                title = "Use AI helper",
                subtitle = if (state.aiEnabled) {
                    "On — set your key and pick features below"
                } else {
                    "Off — nothing is sent to any AI service"
                },
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
                    subtitle = "Swipeable cards at the top of Analytics: a summary of the cycle you're " +
                        "viewing, plus anything unusual, your pace, and how things compare over time.",
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
                    "Suggestions: the merchant text from a detected bank SMS or notification, your category " +
                        "names, and your saved merchant→category shortcuts (names only) so it can recognise a " +
                        "merchant you've tagged before. The merchant is sent as your bank wrote it, so if your " +
                        "bank put a number there (a UPI id, for example), that number goes too.",
                )
                BulletLine(
                    "Suggestions also send whether it was money in or out, and the WORDS of that one message " +
                        "with every number replaced by a single # — that's how \"Hello Fuels\" gets read when " +
                        "the merchant came through as a phone number. Masking removes amounts, balances, card " +
                        "numbers and numeric dates entirely (₹5,59,393.44 becomes just \"₹#\", so even its size " +
                        "isn't visible). It does NOT remove letters — so a payee's name (\"to JOHN DOE\"), a " +
                        "month written as text (\"Jun\") and the letters of a mixed reference (\"AB12CD\" → " +
                        "\"AB#CD\") are sent.",
                )
                BulletLine(
                    "Insights: your category totals and income/expense totals for the cycle you're viewing " +
                        "and, in most views, the one before it. For the \"unusual spending\" cards, Spends " +
                        "works out on your " +
                        "phone what you typically spend in a category over the last six cycles, and sends only " +
                        "the comparison — for example \"Fuel: ₹12,500 this cycle, usually ₹4,100\". Your older " +
                        "cycles themselves are never sent.",
                )
                Spacer(Modifier.height(8.dp))
                BulletLine(
                    "Two insight cards are about one payment rather than a category — \"a large charge\" and " +
                        "\"charged twice?\". For those, that single charge's amount and its category are sent. " +
                        "Never its merchant or its date.",
                )
                Spacer(Modifier.height(8.dp))
                BulletLine(
                    "The cards that compare over time send a little more, all of it worked out on your phone " +
                        "first: how many days into the cycle you are, the calendar month it mostly falls in " +
                        "(just the word \"July\" — never a full date), what the same stretch of the cycle came " +
                        "to a year ago, what one category cost per cycle earlier compared with lately, and — " +
                        "for the payday card — the SHARE of your spending that lands in the week after you're " +
                        "paid. That last one describes when in the month you tend to spend.",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Never sent: your full unmasked message, your balances, your account and card numbers, or " +
                        "the date of any transaction. The insight cards never send a merchant either — only " +
                        "category suggestions do, and only for the one message being reviewed, as described " +
                        "above. For the insight cards, the cycle's month name and your day within the cycle " +
                        "are the only date-shaped values sent (a masked message can still contain a month " +
                        "written in letters, as explained above). The AI helper can never add, edit " +
                        "or delete a transaction — it only suggests a category you still confirm yourself.",
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
                        "features you enable — a detected bank SMS or notification's merchant name, that message's " +
                        "words with every run of digits replaced by #, whether it was money in or out, plus " +
                        "your category names and your saved " +
                        "merchant→category shortcuts (for category suggestions); and your category and " +
                        "income/expense totals for the cycle you're viewing and, in most views, the one " +
                        "before it, what you " +
                        "typically spend in a category, for two of the cards a single charge's amount and " +
                        "category, and for the cards that compare over time your day into the cycle, its " +
                        "calendar month, the same stretch a year ago, what one category cost per cycle " +
                        "earlier compared with lately, and the share of your spending that falls in the week " +
                        "after payday (for insights) are sent to Groq. " +
                        "Being straight with you about the limits: the merchant goes as your bank wrote it, so " +
                        "if your bank put a number there (a UPI id) that number goes too, and the masking " +
                        "removes numbers but not letters — a payee's name or a month written as \"Jun\" is " +
                        "still sent. " +
                        "Never your full unmasked message, your balances, your account and card numbers, or " +
                        "the date of any transaction. The insight cards never send a merchant — only category " +
                        "suggestions do. You'll paste your own free key next.",
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
