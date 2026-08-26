package com.spends.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.money.AppCurrency
import com.spends.app.core.money.FxMath
import com.spends.app.data.ai.AiProvider
import com.spends.app.data.ai.CurrencyAi
import com.spends.app.widget.SummaryWidget

/**
 * "Currency & AI" settings sub-page.
 *
 * Two related things live together because they are one decision for the user: which currency the books
 * are in, and what happens when a bank texts them in a different one.
 *
 * The AI half is opt-in twice over — a switch AND the user's own key — and the copy is deliberate about
 * what turning it on means: this is the only feature in Spends that sends anything off the phone, and the
 * only honest way to offer it is to say so on the screen that enables it.
 */
@Composable
fun CurrencySettingsScreen(
    onBack: () -> Unit,
    viewModel: CurrencyAiViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val keyTest by viewModel.keyTest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCurrencyDialog by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showProviderDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var rateFor by remember { mutableStateOf<AppCurrency?>(null) }

    SettingsSubScaffold(title = "Currency & AI", onBack = onBack) {
        SettingsSection("Currency") {
            ClickableRow(
                title = "Currency",
                value = state.baseCurrency.label,
                onClick = { showCurrencyDialog = true },
            )
            Text(
                "Every amount already saved keeps its value — this changes the symbol and how figures are " +
                    "grouped, not the numbers themselves.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        SettingsSection("Convert foreign alerts with AI") {
            SwitchRow(
                title = "Convert with AI",
                subtitle = "When a bank texts you in another currency, work out what it is in " +
                    "${state.baseCurrency.symbol} and show the rate used. Needs your own API key. " +
                    "This is the only part of Spends that sends anything off your phone.",
                checked = state.aiConversionEnabled,
                onChange = viewModel::setAiConversionEnabled,
            )
            if (state.aiConversionEnabled) {
                RowDivider()
                ClickableRow(
                    title = "Provider",
                    value = state.aiProvider.label,
                    onClick = { showProviderDialog = true },
                )
                ClickableRow(
                    title = "API key",
                    value = if (hasKey) "Saved on this device" else "Not set",
                    onClick = { showKeyDialog = true },
                )
                ClickableRow(
                    title = "Model",
                    value = state.aiModel.ifBlank { "${state.aiProvider.defaultModel} (default)" },
                    onClick = { showModelDialog = true },
                )
                if (hasKey) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.testKey() },
                            enabled = keyTest != KeyTestState.Testing,
                        ) {
                            Text(if (keyTest == KeyTestState.Testing) "Testing…" else "Test key")
                        }
                        Spacer(Modifier.width(12.dp))
                        when (val t = keyTest) {
                            is KeyTestState.Passed -> Text(
                                "Working",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            is KeyTestState.Failed -> Text(
                                t.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            else -> Unit
                        }
                    }
                }
                Text(
                    "The rate comes from the AI's own estimate, not a live market feed, so treat it as " +
                        "approximate. Every converted transaction shows the original amount and the rate " +
                        "used, and you can pin your own rate below to override it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }

        SettingsSection("Your own rates") {
            Text(
                "Pin a rate and Spends uses it instead of asking the AI — no call, no estimate. Leave one " +
                    "blank to go back to the AI's figure.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            AppCurrency.entries.filter { it != state.baseCurrency }.forEachIndexed { index, currency ->
                if (index > 0) RowDivider()
                val pinned = CurrencyAi.manualRateFor(state.manualRates, currency.code, state.baseCurrency.code)
                ClickableRow(
                    title = "1 ${currency.code} =",
                    value = pinned?.let { "${state.baseCurrency.symbol}${FxMath.formatRate(it)}" } ?: "Ask the AI",
                    onClick = { rateFor = currency },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showCurrencyDialog) {
        ChoiceDialog(
            title = "Currency",
            body = "Pick the currency you keep your books in. Amounts you've already saved aren't changed.",
            options = AppCurrency.entries.map { it.label },
            selectedIndex = AppCurrency.entries.indexOf(state.baseCurrency),
            onSelect = { index ->
                // Refresh the widget only after the write commits, so it re-reads the new symbol rather
                // than racing the async DataStore write (the same ordering every other setting uses).
                viewModel.setBaseCurrency(AppCurrency.entries[index]) { SummaryWidget.refresh(context) }
            },
            onDismiss = { showCurrencyDialog = false },
        )
    }

    if (showProviderDialog) {
        ChoiceDialog(
            title = "AI provider",
            body = "Whichever you already have a key for. Your key is only ever sent to the provider you pick here.",
            options = AiProvider.entries.map { it.label },
            selectedIndex = AiProvider.entries.indexOf(state.aiProvider),
            onSelect = { viewModel.setProvider(AiProvider.entries[it]) },
            onDismiss = { showProviderDialog = false },
        )
    }

    if (showKeyDialog) {
        ApiKeyDialog(
            provider = state.aiProvider,
            hasKey = hasKey,
            onSave = { viewModel.saveKey(it) },
            onTest = { viewModel.testKey(it) },
            onClear = { viewModel.clearKey() },
            keyTest = keyTest,
            onDismiss = { showKeyDialog = false },
        )
    }

    if (showModelDialog) {
        var text by remember { mutableStateOf(state.aiModel) }
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("Model") },
            text = {
                Column {
                    Text(
                        "Leave blank to use ${state.aiProvider.defaultModel}. Any model your key can " +
                            "reach will do — this is a one-line question, not a hard one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Model") },
                        placeholder = { Text(state.aiProvider.defaultModel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setModel(text); showModelDialog = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showModelDialog = false }) { Text("Cancel") } },
        )
    }

    rateFor?.let { currency ->
        val base = state.baseCurrency
        val existing = CurrencyAi.manualRateFor(state.manualRates, currency.code, base.code)
        var text by remember(currency) { mutableStateOf(existing?.let { FxMath.formatRate(it) } ?: "") }
        AlertDialog(
            onDismissRequest = { rateFor = null },
            title = { Text("1 ${currency.code} in ${base.code}") },
            text = {
                Column {
                    Text(
                        "How many ${base.displayName.lowercase()} one ${currency.displayName.lowercase()} " +
                            "is worth. Clear the box to let the AI estimate it again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { input -> text = input.filter { it.isDigit() || it == '.' } },
                        label = { Text("Rate") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setManualRate(currency.code, text); rateFor = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { rateFor = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The API-key sheet.
 *
 * The field starts EMPTY even when a key is stored, and the stored value is never fetched into it: this
 * screen can write a key and prove one works, but it cannot read one back out. "Remove" is offered
 * separately so the user always has a way to take the credential off the device.
 */
@Composable
private fun ApiKeyDialog(
    provider: AiProvider,
    hasKey: Boolean,
    keyTest: KeyTestState,
    onSave: (String) -> Unit,
    onTest: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${provider.label} API key") },
        text = {
            Column {
                Text(
                    if (hasKey) {
                        "A key is saved on this device. Paste a new one to replace it — for security, " +
                            "the saved key can't be shown again."
                    } else {
                        "Paste your own key. It's encrypted on this device, never included in a backup, " +
                            "and only ever sent to ${provider.label}."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Get one at ${provider.consoleUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.trim() },
                    label = { Text("API key") },
                    placeholder = { Text(provider.keyHint) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (keyTest is KeyTestState.Failed) {
                    Spacer(Modifier.height(6.dp))
                    Text(keyTest.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else if (keyTest is KeyTestState.Passed) {
                    Spacer(Modifier.height(6.dp))
                    Text("That key works.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (text.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onTest(text) },
                        enabled = keyTest != KeyTestState.Testing,
                    ) {
                        Text(if (keyTest == KeyTestState.Testing) "Testing…" else "Test before saving")
                    }
                }
                if (hasKey) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { onClear(); onDismiss() }) {
                        Text("Remove the saved key", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(text); onDismiss() },
                enabled = text.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A plain single-choice list dialog — used for the currency and the provider. */
@Composable
private fun ChoiceDialog(
    title: String,
    body: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(selectedIndex) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                options.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == selected, onClick = { selected = index })
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(selected); onDismiss() }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
