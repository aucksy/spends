package com.spends.app.ui.backup

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spends.app.core.period.PeriodRange
import com.spends.app.core.period.PeriodResolver
import com.spends.app.core.period.PeriodSelection
import com.spends.app.core.period.PeriodType
import com.spends.app.core.time.DateUtils
import com.spends.app.ui.components.CustomRangeDialog
import com.spends.app.ui.components.PillSegmentedControl
import java.time.LocalDate

/**
 * A one-shot "which cycle do you want in the spreadsheet?" picker for the Excel export (#4). Offers the same
 * Month / Salary cycle × All-time / This-cycle / Last-3 / Last-6 / Custom choices as the main cycle selector,
 * then hands the resolved window + a filename-friendly label to [onExport]. Smart Cycle isn't offered — a
 * spreadsheet spans a plain contiguous window, not a per-instrument composite.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportCycleSheet(
    salaryDay: Int,
    earliestDayMillis: Long?,
    onExport: (startMillis: Long, endExclusiveMillis: Long, label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val today = remember { LocalDate.now(DateUtils.ZONE) }
    // Default to all time — the export's previous behaviour — so "export everything" stays one tap.
    var current by remember { mutableStateOf(PeriodSelection(type = PeriodType.SALARY_CYCLE, range = PeriodRange.ALL)) }
    var showCustom by remember { mutableStateOf(false) }

    // Resolve the selection to a concrete window + a filename-friendly label. All-time uses an unbounded
    // window so it captures every transaction (matching the old export), including any future-dated rows.
    fun resolve(sel: PeriodSelection): Triple<Long, Long, String> {
        if (sel.range == PeriodRange.ALL) return Triple(Long.MIN_VALUE, Long.MAX_VALUE, "All time")
        val r = PeriodResolver.resolve(
            type = sel.type,
            range = sel.range,
            salaryDay = salaryDay,
            smartDay = salaryDay,
            today = today,
            earliestDataDay = earliestDayMillis?.let { DateUtils.toLocalDate(it) },
            customStartMillis = sel.customStartMillis,
            customEndExclusiveMillis = sel.customEndExclusiveMillis,
            cycleOffset = sel.cycleOffset,
        )
        return Triple(r.startMillis, r.endExclusiveMillis, r.label)
    }

    val types = listOf(PeriodType.MONTH, PeriodType.SALARY_CYCLE)
    val typeLabels = listOf("Month", "Salary")
    val selectedTypeIndex = types.indexOf(current.type).coerceAtLeast(0)
    val resolvedLabel = resolve(current).third

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Text("Export to Excel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Choose which cycle to include in the spreadsheet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            SectionLabelText("Cycle")
            Spacer(Modifier.height(8.dp))
            PillSegmentedControl(
                options = typeLabels,
                selectedIndex = selectedTypeIndex,
                onSelect = { idx -> current = current.copy(type = types[idx]) },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            SectionLabelText("Range")
            Spacer(Modifier.height(2.dp))
            ExportRangeRow("All time", current.range == PeriodRange.ALL) {
                current = current.copy(range = PeriodRange.ALL, customStartMillis = null, customEndExclusiveMillis = null, cycleOffset = 0)
            }
            ExportRangeRow("This cycle", current.range == PeriodRange.CURRENT) {
                current = current.copy(range = PeriodRange.CURRENT, customStartMillis = null, customEndExclusiveMillis = null, cycleOffset = 0)
            }
            ExportRangeRow("Last 3", current.range == PeriodRange.LAST_3) {
                current = current.copy(range = PeriodRange.LAST_3, customStartMillis = null, customEndExclusiveMillis = null, cycleOffset = 0)
            }
            ExportRangeRow("Last 6", current.range == PeriodRange.LAST_6) {
                current = current.copy(range = PeriodRange.LAST_6, customStartMillis = null, customEndExclusiveMillis = null, cycleOffset = 0)
            }
            ExportRangeRow("Custom range…", current.range == PeriodRange.CUSTOM) { showCustom = true }

            Spacer(Modifier.height(16.dp))
            Text(
                "Exporting: $resolvedLabel",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val (start, end, label) = resolve(current)
                    onExport(start, end, label)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Export")
            }
        }
    }

    if (showCustom) {
        CustomRangeDialog(
            initialStart = current.customStartMillis?.let { DateUtils.toLocalDate(it) },
            initialEnd = current.customEndExclusiveMillis?.let { DateUtils.toLocalDate(it).minusDays(1) },
            onDismiss = { showCustom = false },
            onPick = { start, endExclusive ->
                showCustom = false
                current = current.copy(range = PeriodRange.CUSTOM, customStartMillis = start, customEndExclusiveMillis = endExclusive, cycleOffset = 0)
            },
        )
    }
}

@Composable
private fun SectionLabelText(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExportRangeRow(label: String, selected: Boolean, onClick: () -> Unit) {
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
