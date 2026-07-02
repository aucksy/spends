package com.spends.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.money.Money
import com.spends.app.core.time.DateUtils
import com.spends.app.domain.model.DefaultLanding
import com.spends.app.domain.model.ThemeMode
import com.spends.app.ui.backup.BackupSection
import com.spends.app.ui.backup.SpreadsheetSection
import com.spends.app.ui.components.NumberWheelPicker
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenCapture: () -> Unit,
    onOpenBanksCards: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showSalaryDialog by remember { mutableStateOf(false) }
    var showAnchorPicker by remember { mutableStateOf(false) }
    var showOpeningDialog by remember { mutableStateOf(false) }
    var showDarkStartPicker by remember { mutableStateOf(false) }
    var showDarkEndPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        // Paper background so the white section cards read as distinct surfaces (like the Digest app) (#1).
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ── The money model first (#3): period, carry-forward, and the Smart Cycle + instruments. ──
            SettingsSection("Cycle & cards") {
                ClickableRow(
                    title = "Salary day",
                    value = ordinal(state.salaryCycleStartDay),
                    onClick = { showSalaryDialog = true },
                )
                SwitchRow(
                    title = "Carry forward",
                    subtitle = "Roll each period's leftover into the next.",
                    checked = state.carryForwardEnabled,
                    onChange = viewModel::setCarryForward,
                )
                if (state.carryForwardEnabled) {
                    ClickableRow(
                        title = "Carry forward from",
                        value = if (state.carryForwardAnchorEpochDay > 0) {
                            DateUtils.formatDay(DateUtils.startOfDayMillis(LocalDate.ofEpochDay(state.carryForwardAnchorEpochDay)))
                        } else {
                            "Pick a date"
                        },
                        onClick = { showAnchorPicker = true },
                    )
                    ClickableRow(
                        title = "Opening balance",
                        value = Money.formatRupees(state.carryForwardOpeningMinor),
                        onClick = { showOpeningDialog = true },
                    )
                    Text(
                        "Your balance was the opening amount on this date; only transactions on/after it count, so " +
                            "incomplete older months can't drag the balance negative.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                SwitchRow(
                    title = "Smart Cycle",
                    subtitle = "Group each credit card's spending by its own billing date, and your bank/UPI by your salary date — so \"this period\" is what you're actually about to pay, not a calendar month.",
                    checked = state.smartCycleEnabled,
                    onChange = viewModel::setSmartCycle,
                )
                if (state.smartCycleEnabled) {
                    Text(
                        "Manage your cards and banks below (or scan your SMS to find cards) and set each card's billing day. Tag spending with \"Paid with\" when you add it. Turn this off any time to return to the simple salary / month view.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    ClickableRow(
                        title = "Banks & Cards",
                        value = "Manage cards, banks & default instrument",
                        onClick = onOpenBanksCards,
                        leading = { Icon(Icons.Filled.CreditCard, contentDescription = null) },
                    )
                }
            }

            // ── How transactions get added without typing (#3: was two separate sections). ──
            SettingsSection("Automatic entries") {
                ClickableRow(
                    title = "Detect from SMS",
                    value = "Review & add bank transactions from your texts",
                    onClick = onOpenCapture,
                    leading = { Icon(Icons.Filled.Sms, contentDescription = null) },
                )
                ClickableRow(
                    title = "Recurring transactions",
                    value = "Rent, salary, EMIs & subscriptions on a schedule",
                    onClick = onOpenRecurring,
                    leading = { Icon(Icons.Filled.Autorenew, contentDescription = null) },
                )
            }

            SettingsSection("Categories") {
                ClickableRow(
                    title = "Manage categories",
                    value = "Add, rename, archive or delete",
                    onClick = onOpenCategories,
                    leading = { Icon(Icons.Filled.Category, contentDescription = null) },
                )
            }

            // ── Look & feel — theme, the screen the app opens on, and the widget (#3: merged three). ──
            SettingsSection("Appearance") {
                Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.themeMode == mode,
                            onClick = { viewModel.setTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                            // No check-icon slot — the filled segment already shows selection, and the icon
                            // stole the width that made "System" wrap to two lines.
                            icon = {},
                            label = {
                                Text(mode.label(), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                            },
                        )
                    }
                }
                // Auto = dark inside a daily window the user sets (default 8 PM–6 AM).
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
                Spacer(Modifier.height(6.dp))
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
                Spacer(Modifier.height(2.dp))
                SwitchRow(
                    title = "Hide the widget's reveal button",
                    subtitle = "The eye on the home-screen summary widget becomes invisible (but still tappable), so someone holding your phone can't tell there's a way to reveal the figures.",
                    checked = state.widgetEyeHidden,
                    onChange = { hidden ->
                        // Refresh the widget only AFTER the value is persisted, so it re-renders with the
                        // new state immediately instead of needing a tap to force a fresh read.
                        viewModel.setWidgetEyeHidden(hidden) { com.spends.app.widget.SummaryWidget.refresh(context) }
                    },
                )
            }

            // ── Export / restore / cleanup last — rarely touched (#3). ──
            SettingsSection("Backup") {
                BackupSection()
            }

            SettingsSection("Spreadsheet (Excel / CSV)") {
                SpreadsheetSection(onImport = onOpenImport)
            }

            SettingsSection("Data") {
                ClickableRow(
                    title = "Trash",
                    value = "Auto-purge after ${state.trashRetentionDays} days",
                    onClick = onOpenTrash,
                    leading = { Icon(Icons.Filled.Delete, contentDescription = null) },
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Notification detection and app lock arrive in upcoming updates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSalaryDialog) {
        SalaryDayDialog(
            current = state.salaryCycleStartDay,
            // Persist only on "Done" (the dialog closes itself). #14: refresh the widget AFTER the write
            // commits so its cycle name + dates re-read the new salary day immediately (a synchronous refresh
            // would race the async DataStore write and re-render the old cycle).
            onSelect = {
                viewModel.setSalaryDay(it) { com.spends.app.widget.SummaryWidget.refresh(context) }
            },
            onDismiss = { showSalaryDialog = false },
        )
    }

    if (showAnchorPicker) {
        val initial = if (state.carryForwardAnchorEpochDay > 0) {
            DateUtils.toPickerUtcMillis(DateUtils.startOfDayMillis(LocalDate.ofEpochDay(state.carryForwardAnchorEpochDay)))
        } else {
            null
        }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showAnchorPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { utc ->
                        viewModel.setCarryForwardAnchor(DateUtils.toLocalDate(DateUtils.fromPickerUtcMillis(utc)).toEpochDay())
                    }
                    showAnchorPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showAnchorPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }

    if (showOpeningDialog) {
        var amountText by remember { mutableStateOf(Money.toEditString(state.carryForwardOpeningMinor)) }
        AlertDialog(
            onDismissRequest = { showOpeningDialog = false },
            title = { Text("Opening balance") },
            text = {
                Column {
                    Text(
                        "Your balance at the start of the carry-forward date. Can be negative.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { input -> amountText = input.filter { it.isDigit() || it == '.' || it == '-' } },
                        label = { Text("Amount (₹)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setCarryForwardOpening(Money.parseRupeesToMinor(amountText) ?: 0L)
                    showOpeningDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showOpeningDialog = false }) { Text("Cancel") } },
        )
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

@Composable
private fun SectionHeader(text: String) {
    // A slim primary accent bar beside a bold uppercase label — more prominent, still elegant (#7).
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 13.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** A titled settings group: the coloured label sits above a rounded surface card holding its rows (#1). */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(16.dp))
    SectionHeader(title)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 12dp inner padding (not 16) so a 4-way control like the Theme picker has room and doesn't wrap (#3).
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), content = content)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ClickableRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.padding(horizontal = 6.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SalaryDayDialog(current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    // Spinning the wheel only updates this LOCAL day — it does NOT commit or close the dialog. The value is
    // persisted (and the dialog closed) only on "Done", so scrolling past a day no longer auto-selects it
    // and closes the dialog the instant a new number snaps to centre (#13).
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Salary day") },
        text = {
            Column {
                Text(
                    "Spin to the day you usually get paid, then tap Done.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                NumberWheelPicker(
                    value = selected,
                    range = 1..31,
                    onValueChange = { selected = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(selected); onDismiss() }) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.AUTO -> "Auto"
}

/** "20:00" → "8:00 PM" for the auto-theme window rows. */
private fun formatMinuteOfDay(minute: Int): String {
    val h24 = (minute / 60).coerceIn(0, 23)
    val m = (minute % 60).coerceIn(0, 59)
    val period = if (h24 < 12) "AM" else "PM"
    val h12 = when {
        h24 == 0 -> 12
        h24 > 12 -> h24 - 12
        else -> h24
    }
    return "%d:%02d %s".format(h12, m, period)
}

private fun DefaultLanding.label(): String = when (this) {
    DefaultLanding.TRANSACTIONS -> "Transactions"
    DefaultLanding.ANALYTICS -> "Analytics"
}

private fun ordinal(day: Int): String {
    val suffix = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}
