package com.spends.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.money.Money
import com.spends.app.core.time.DateUtils
import com.spends.app.ui.components.NumberWheelPicker
import java.time.LocalDate

/**
 * "Money & Cycles" settings sub-page: salary day, carry-forward, the Smart Cycle + its reset day, and the
 * Banks & Cards link. Lifted verbatim from the old single settings page's "Cycle & cards" section so the
 * behaviour (and all its dialogs) is unchanged — only the location moved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneySettingsScreen(
    onBack: () -> Unit,
    onOpenBanksCards: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showSalaryDialog by remember { mutableStateOf(false) }
    var showResetDayDialog by remember { mutableStateOf(false) }
    var showAnchorPicker by remember { mutableStateOf(false) }
    var showOpeningDialog by remember { mutableStateOf(false) }

    SettingsSubScaffold(title = "Money & Cycles", onBack = onBack) {
        SettingsSection("Salary & carry forward") {
            ClickableRow(
                title = "Salary day",
                value = ordinal(state.salaryCycleStartDay),
                onClick = { showSalaryDialog = true },
            )
            RowDivider()
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
                    "Only counts transactions on/after this date, so incomplete older months can't drag the balance negative.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }

        SettingsSection("Smart Cycle") {
            SwitchRow(
                title = "Smart Cycle",
                subtitle = "One balance across bank, UPI and cards — everything you spend counts in the cycle you spend it. You can still view each card on its own billing cycle.",
                checked = state.smartCycleEnabled,
                onChange = { on ->
                    viewModel.setSmartCycle(on)
                    if (on) showResetDayDialog = true
                },
            )
            if (state.smartCycleEnabled) {
                ClickableRow(
                    title = "Cycle reset day",
                    value = if (state.smartCycleResetDay in 1..31) {
                        "${ordinal(state.smartCycleResetDay)} of every month"
                    } else {
                        "Salary day (${ordinal(state.salaryCycleStartDay)})"
                    },
                    onClick = { showResetDayDialog = true },
                )
                RowDivider()
                ClickableRow(
                    title = "Banks & Cards",
                    value = "Cards, banks & default instrument",
                    onClick = onOpenBanksCards,
                    leading = { Icon(Icons.Filled.CreditCard, contentDescription = null) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showSalaryDialog) {
        SalaryDayDialog(
            current = state.salaryCycleStartDay,
            onSelect = {
                viewModel.setSalaryDay(it) { com.spends.app.widget.SummaryWidget.refresh(context) }
            },
            onDismiss = { showSalaryDialog = false },
        )
    }

    if (showResetDayDialog) {
        SmartResetDayDialog(
            salaryDay = state.salaryCycleStartDay,
            currentResetDay = state.smartCycleResetDay,
            onSelect = { day ->
                val stored = if (day == state.salaryCycleStartDay) 0 else day
                viewModel.setSmartCycleResetDay(stored) { com.spends.app.widget.SummaryWidget.refresh(context) }
            },
            onDismiss = { showResetDayDialog = false },
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
}

/**
 * Asked when Smart Cycle is turned ON (and editable later via the "Cycle reset day" row): the one day of
 * the month the Smart Cycle balance starts over. Explained in plain words.
 */
@Composable
private fun SmartResetDayDialog(
    salaryDay: Int,
    currentResetDay: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(if (currentResetDay in 1..31) currentResetDay else salaryDay) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("When should your cycle restart?") },
        text = {
            Column {
                Text(
                    "Smart Cycle shows one balance: everything you earn and spend between two reset days — " +
                        "bank, UPI and cards together.\n\n" +
                        "Most people pick their salary day (${ordinal(salaryDay)}), so the balance answers " +
                        "“how much of this salary is left?”\n\n" +
                        "Pick a different day if that fits you better — for example the day you sit down to " +
                        "pay your card bills each month.",
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

@Composable
private fun SalaryDayDialog(current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
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
