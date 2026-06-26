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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.view.HapticFeedbackConstants
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spends.app.core.period.PeriodRange
import com.spends.app.core.period.PeriodSelection
import com.spends.app.core.period.PeriodType
import com.spends.app.core.time.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

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
    onToggleSearch: (() -> Unit)? = null,
    searchActive: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    // Prev/next cycle stepping shows only for a single current cycle; future is capped at the present (#6).
    val navigable = selection.isNavigable
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (navigable) {
                    CycleArrow(Icons.Filled.ChevronLeft, "Previous cycle", enabled = true) {
                        onSelect(selection.copy(cycleOffset = selection.cycleOffset - 1))
                    }
                } else {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                }
                Column(
                    modifier = Modifier.weight(1f).clickable { open = true }.padding(vertical = 2.dp),
                    horizontalAlignment = if (navigable) Alignment.CenterHorizontally else Alignment.Start,
                ) {
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
                if (navigable) {
                    // Can't step into the future — › is disabled once back at the current cycle.
                    CycleArrow(Icons.Filled.ChevronRight, "Next cycle", enabled = selection.cycleOffset < 0) {
                        onSelect(selection.copy(cycleOffset = selection.cycleOffset + 1))
                    }
                } else {
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "Change period",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { open = true }.padding(end = 4.dp),
                )
            }
        }
        // Search lives here beside Settings (#16) so the timeline no longer needs a permanent search bar.
        if (onToggleSearch != null) {
            IconButton(onClick = onToggleSearch) {
                Icon(
                    if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchActive) "Close search" else "Search transactions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onOpenSettings != null) {
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

/** A square tappable chevron used for prev/next cycle stepping inside the period pill (#6). */
@Composable
private fun CycleArrow(icon: ImageVector, contentDescription: String, enabled: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) // a felt tick on each step (#5)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
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
                    // Reset any prev/next cycle stepping when the cycle type changes (#6).
                    current = current.copy(type = newType, cycleOffset = 0)
                    onSelect(current) // apply immediately, keeping the range
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(18.dp))
            SectionLabel("Range")
            Spacer(Modifier.height(4.dp))
            RangeRow("All", current.range == PeriodRange.ALL) {
                onSelect(current.copy(range = PeriodRange.ALL, customStartMillis = null, customEndExclusiveMillis = null, cycleOffset = 0)); onDismiss()
            }
            RangeRow("Current", current.range == PeriodRange.CURRENT) {
                onSelect(current.copy(range = PeriodRange.CURRENT, customStartMillis = null, customEndExclusiveMillis = null, cycleOffset = 0)); onDismiss()
            }
            RangeRow("Last 3", current.range == PeriodRange.LAST_3) {
                onSelect(current.copy(range = PeriodRange.LAST_3, customStartMillis = null, customEndExclusiveMillis = null, cycleOffset = 0)); onDismiss()
            }
            RangeRow("Last 6", current.range == PeriodRange.LAST_6) {
                onSelect(current.copy(range = PeriodRange.LAST_6, customStartMillis = null, customEndExclusiveMillis = null, cycleOffset = 0)); onDismiss()
            }
            RangeRow("Custom range…", current.range == PeriodRange.CUSTOM) { showCustom = true }
        }
    }

    if (showCustom) {
        CustomRangeDialog(
            // Pre-fill the currently-applied custom range so the user can tweak it (the stored end is
            // exclusive — convert it back to the inclusive UI day).
            initialStart = current.customStartMillis?.let { DateUtils.toLocalDate(it) },
            initialEnd = current.customEndExclusiveMillis?.let { DateUtils.toLocalDate(it).minusDays(1) },
            onDismiss = { showCustom = false },
            onPick = { start, endExclusive ->
                showCustom = false
                onSelect(current.copy(range = PeriodRange.CUSTOM, customStartMillis = start, customEndExclusiveMillis = endExclusive, cycleOffset = 0))
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

/**
 * Custom date-range picker (#12): pick a Start and End date, each by tapping a Month dropdown, a Year
 * dropdown and a day in the grid — clearer than a scrolling range calendar and exactly matching the
 * "tap month/year individually for both" ask. [onPick] gets the start-of-day millis and the exclusive
 * end (end day + 1).
 */
@Composable
private fun CustomRangeDialog(
    initialStart: LocalDate? = null,
    initialEnd: LocalDate? = null,
    onDismiss: () -> Unit,
    onPick: (Long, Long) -> Unit,
) {
    val today = remember { DateUtils.toLocalDate(DateUtils.nowMillis()) }
    var editingStart by remember { mutableStateOf(initialStart == null) }
    var start by remember { mutableStateOf(initialStart) }
    var end by remember { mutableStateOf(initialEnd) }

    fun setActive(d: LocalDate) { if (editingStart) start = d else end = d }
    val active = if (editingStart) start else end
    val shown = active ?: today

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("Custom range", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    RangeEndChip("Start", start, editingStart, Modifier.weight(1f)) { editingStart = true }
                    RangeEndChip("End", end, !editingStart, Modifier.weight(1f)) { editingStart = false }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    MonthDropdown(shown.month, Modifier.weight(1f)) { m ->
                        val len = YearMonth.of(shown.year, m).lengthOfMonth()
                        setActive(LocalDate.of(shown.year, m, shown.dayOfMonth.coerceAtMost(len)))
                    }
                    YearDropdown(shown.year, today.year, Modifier.weight(1f)) { y ->
                        val len = YearMonth.of(y, shown.month).lengthOfMonth()
                        setActive(LocalDate.of(y, shown.monthValue, shown.dayOfMonth.coerceAtMost(len)))
                    }
                }
                Spacer(Modifier.height(14.dp))
                MiniDayGrid(YearMonth.of(shown.year, shown.month), active?.dayOfMonth) { day ->
                    setActive(LocalDate.of(shown.year, shown.monthValue, day))
                }
                if (start != null && end != null && start!!.isAfter(end!!)) {
                    Spacer(Modifier.height(8.dp))
                    Text("Start must be on or before end.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    val valid = start != null && end != null && !start!!.isAfter(end!!)
                    TextButton(
                        enabled = valid,
                        onClick = { onPick(DateUtils.startOfDayMillis(start!!), DateUtils.startOfDayMillis(end!!.plusDays(1))) },
                    ) { Text("Apply") }
                }
            }
        }
    }
}

@Composable
private fun RangeEndChip(label: String, date: LocalDate?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fmt = remember { DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH) }
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                date?.let { fmt.format(it) } ?: "Pick a date",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MonthDropdown(month: Month, modifier: Modifier, onSelect: (Month) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        DropdownAnchor(month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Month.entries.forEach { m ->
                DropdownMenuItem(text = { Text(m.getDisplayName(TextStyle.FULL, Locale.ENGLISH)) }, onClick = { onSelect(m); open = false })
            }
        }
    }
}

@Composable
private fun YearDropdown(year: Int, currentYear: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val years = (currentYear downTo currentYear - 12).toList()
    Box(modifier) {
        DropdownAnchor(year.toString()) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            years.forEach { y -> DropdownMenuItem(text = { Text(y.toString()) }, onClick = { onSelect(y); open = false }) }
        }
    }
}

@Composable
private fun DropdownAnchor(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MiniDayGrid(yearMonth: YearMonth, selectedDay: Int?, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..yearMonth.lengthOfMonth()).chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                week.forEach { d ->
                    val sel = d == selectedDay
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onSelect(d) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$d",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
