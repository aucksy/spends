package com.spends.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spends.app.core.period.PeriodRange
import com.spends.app.core.period.PeriodSelection
import com.spends.app.core.period.PeriodType
import com.spends.app.core.time.DateUtils

/**
 * The period selector row: a full-width pill that names the selected cycle in words (e.g. "Current
 * Salary Cycle") with the concrete date range beneath it, plus an optional Settings gear ([onOpenSettings])
 * so the home screen can drop its title bar to save space (#5/#7). [label] is the resolved date range.
 */
@Composable
fun PeriodSelectorBar(
    selection: PeriodSelection,
    label: String,
    onSelect: (PeriodSelection) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.weight(1f).clickable { open = true },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        selection.describe(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change period", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onOpenSettings != null) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (open) {
        PeriodSelectorSheet(
            selection = selection,
            onSelect = onSelect,
            onDismiss = { open = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelectorSheet(
    selection: PeriodSelection,
    onSelect: (PeriodSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var current by remember { mutableStateOf(selection) }
    var showCustom by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            SectionLabel("Cycle")
            Spacer(Modifier.height(10.dp))
            PillSegmentedControl(
                options = listOf("Month", "Salary", "Smart"),
                selectedIndex = current.type.ordinal,
                onSelect = { idx ->
                    val newType = PeriodType.entries[idx]
                    current = current.copy(type = newType)
                    onSelect(current) // apply immediately, keeping the range
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(18.dp))
            SectionLabel("Range")
            Spacer(Modifier.height(4.dp))
            RangeRow("All", current.range == PeriodRange.ALL) {
                onSelect(current.copy(range = PeriodRange.ALL, customStartMillis = null, customEndExclusiveMillis = null)); onDismiss()
            }
            RangeRow("Current", current.range == PeriodRange.CURRENT) {
                onSelect(current.copy(range = PeriodRange.CURRENT, customStartMillis = null, customEndExclusiveMillis = null)); onDismiss()
            }
            RangeRow("Last 3", current.range == PeriodRange.LAST_3) {
                onSelect(current.copy(range = PeriodRange.LAST_3, customStartMillis = null, customEndExclusiveMillis = null)); onDismiss()
            }
            RangeRow("Last 6", current.range == PeriodRange.LAST_6) {
                onSelect(current.copy(range = PeriodRange.LAST_6, customStartMillis = null, customEndExclusiveMillis = null)); onDismiss()
            }
            RangeRow("Custom range…", current.range == PeriodRange.CUSTOM) { showCustom = true }
        }
    }

    if (showCustom) {
        CustomRangeDialog(
            onDismiss = { showCustom = false },
            onPick = { start, endExclusive ->
                showCustom = false
                onSelect(current.copy(range = PeriodRange.CUSTOM, customStartMillis = start, customEndExclusiveMillis = endExclusive))
                onDismiss()
            },
        )
    }
}

@Composable
private fun RangeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(onDismiss: () -> Unit, onPick: (Long, Long) -> Unit) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val startUtc = state.selectedStartDateMillis
            val endUtc = state.selectedEndDateMillis
            TextButton(
                enabled = startUtc != null && endUtc != null,
                onClick = {
                    if (startUtc != null && endUtc != null) {
                        val startDay = DateUtils.toLocalDate(DateUtils.fromPickerUtcMillis(startUtc))
                        val endDay = DateUtils.toLocalDate(DateUtils.fromPickerUtcMillis(endUtc))
                        onPick(DateUtils.startOfDayMillis(startDay), DateUtils.startOfDayMillis(endDay.plusDays(1)))
                    }
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(state = state, modifier = Modifier.height(480.dp))
    }
}
