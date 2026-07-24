package com.spends.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import com.spends.app.domain.model.DefaultLanding
import com.spends.app.domain.model.ThemeMode

/**
 * "Appearance" settings sub-page: theme (+ the auto-dark window), the screen the app opens on, and the
 * widget's reveal-button visibility. Lifted verbatim from the old settings page's "Appearance" section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showDarkStartPicker by remember { mutableStateOf(false) }
    var showDarkEndPicker by remember { mutableStateOf(false) }

    SettingsSubScaffold(title = "Appearance", onBack = onBack) {
        SettingsSection("Theme") {
            Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        icon = {},
                        label = {
                            Text(mode.label(), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                        },
                    )
                }
            }
            if (state.themeMode == ThemeMode.AUTO) {
                ClickableRow(
                    title = "Dark from",
                    value = formatMinuteOfDay(state.autoDarkStartMinute),
                    onClick = { showDarkStartPicker = true },
                )
                ClickableRow(
                    title = "Dark until",
                    value = formatMinuteOfDay(state.autoDarkEndMinute),
                    onClick = { showDarkEndPicker = true },
                )
                Text(
                    "Dark mode turns on between these times each day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            RowDivider()
            Text("Open on", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                DefaultLanding.entries.forEachIndexed { index, landing ->
                    SegmentedButton(
                        selected = state.defaultLanding == landing,
                        onClick = { viewModel.setDefaultLanding(landing) },
                        shape = SegmentedButtonDefaults.itemShape(index, DefaultLanding.entries.size),
                        icon = {},
                        label = {
                            Text(landing.label(), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                        },
                    )
                }
            }
        }

        SettingsSection("Widget") {
            SwitchRow(
                title = "Hide the widget's reveal button",
                subtitle = "Makes the widget's eye invisible but still tappable, so no one can tell the figures can be revealed.",
                checked = state.widgetEyeHidden,
                onChange = { hidden ->
                    viewModel.setWidgetEyeHidden(hidden) { com.spends.app.widget.SummaryWidget.refresh(context) }
                },
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showDarkStartPicker) {
        TimeOfDayDialog(
            title = "Dark from",
            initialMinute = state.autoDarkStartMinute,
            onConfirm = { minute ->
                viewModel.setAutoDarkWindow(minute, state.autoDarkEndMinute)
                showDarkStartPicker = false
            },
            onDismiss = { showDarkStartPicker = false },
        )
    }
    if (showDarkEndPicker) {
        TimeOfDayDialog(
            title = "Dark until",
            initialMinute = state.autoDarkEndMinute,
            onConfirm = { minute ->
                viewModel.setAutoDarkWindow(state.autoDarkStartMinute, minute)
                showDarkEndPicker = false
            },
            onDismiss = { showDarkEndPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeOfDayDialog(title: String, initialMinute: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val timeState = rememberTimePickerState(
        initialHour = (initialMinute / 60).coerceIn(0, 23),
        initialMinute = (initialMinute % 60).coerceIn(0, 59),
        is24Hour = false,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                TimePicker(state = timeState)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(timeState.hour * 60 + timeState.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.AUTO -> "Auto"
}

private fun DefaultLanding.label(): String = when (this) {
    DefaultLanding.TRANSACTIONS -> "Transactions"
    DefaultLanding.ANALYTICS -> "Analytics"
}
