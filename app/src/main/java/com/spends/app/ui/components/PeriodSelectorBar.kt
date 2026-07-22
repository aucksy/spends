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

/** A card the Smart Cycle selector can narrow to (Single-Card mode). Only the bits the picker shows. */
data class CardChoice(
    val id: Long,
    val label: String,
    val colorHex: String,
)

/**
 * The period selector row: a full-width pill that names the selected cycle in words (e.g. "Current
 * Salary Cycle") with the concrete date range beneath it, plus an optional Settings gear ([onOpenSettings])
 * so the home screen can drop its title bar to save space (#5/#7). [label] is the resolved date range.
 *
 * When [smartCycleEnabled] is true the sheet offers the "Smart" cycle pill; selecting it swaps the Range
 * list for a card picker (All cards = composite, or one card = Single-Card mode) built from [cards].
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
    smartCycleEnabled: Boolean = false,
    cards: List<CardChoice> = emptyList(),
    // The category drill-down offers the Smart pill (so its window matches the slice you tapped) but hides
    // the card narrowing — a per-category list isn't per-card. Everything else keeps the section.
    showCardSection: Boolean = true,
    // #1: in All-time mode the caller passes this so the leading calendar icon "pops" and opens the
    // "Jump to month" picker. null in every other mode → the calendar stays a plain decorative glyph.
    onJumpToMonth: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val view = LocalView.current
    // A persisted SMART_CYCLE selection can outlive the toggle (e.g. a backup restore writes the flag
    // directly, bypassing setSmartCycle). The data paths already coerce it to the salary cycle, so coerce
    // it HERE too — otherwise the pill would read "Smart Cycle" with phantom prev/next arrows over salary
    // data. Acting on the coerced value also self-heals the persisted selection on the next interaction.
    val effective = if (!smartCycleEnabled && selection.type == PeriodType.SMART_CYCLE) {
        selection.copy(type = PeriodType.SALARY_CYCLE, selectedCardId = null)
    } else {
        selection
    }
    // Prev/next cycle stepping shows only for a single current cycle; future is capped at the present (#6).
    val navigable = effective.isNavigable
    // The secondary line is the concrete date range. Hide it when there's nothing meaningful to show (the
    // Smart composite has no single range, so its label == the name) — otherwise the name duplicates on two
    // lines and the larger title truncates (#5). Compared case-insensitively so near-duplicates like
    // "All Time" over "All time" / "Last 3 Months" over "Last 3 months" collapse to a single clean line too.
    val dateLine = label.takeIf { it.isNotBlank() && !it.equals(effective.describe(), ignoreCase = true) }
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
                        onSelect(effective.copy(cycleOffset = effective.cycleOffset - 1))
                    }
                } else if (onJumpToMonth != null) {
                    // All-time: the calendar "pops" (a primary-container chip) so it reads as tappable, and
                    // opens the month jumper (#1) — this replaces the separate "Jump to month" pill.
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onJumpToMonth()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.CalendarMonth,
                            contentDescription = "Jump to month",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
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
                        effective.describe(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (dateLine != null) {
                        Text(
                            dateLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (navigable) {
                    // Can't step into the future — › is disabled once back at the current cycle.
                    CycleArrow(Icons.Filled.ChevronRight, "Next cycle", enabled = effective.cycleOffset < 0) {
                        onSelect(effective.copy(cycleOffset = effective.cycleOffset + 1))
                    }
                    // No trailing dropdown caret when the stepper arrows are present — the pill is tappable and
                    // the arrows already read as interactive, so dropping the caret frees width for the full
                    // cycle name (#5, the truncated "Current Smart C…").
                    Spacer(Modifier.width(4.dp))
                } else {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = "Change period",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { open = true }.padding(end = 4.dp),
                    )
                }
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
            selection = effective,
            onSelect = onSelect,
            onDismiss = { open = false },
            smartCycleEnabled = smartCycleEnabled,
            cards = cards,
            showCardSection = showCardSection,
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
    smartCycleEnabled: Boolean,
    cards: List<CardChoice>,
    showCardSection: Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var current by remember { mutableStateOf(selection) }
    var showCustom by remember { mutableStateOf(false) }

    // The Smart pill only exists while the feature is on (PRD §4.7). With it off, the control is exactly the
    // old two/three-pill set, so a stray SMART_CYCLE selection (e.g. just-disabled) maps back to Salary.
    val types = if (smartCycleEnabled) {
        listOf(PeriodType.MONTH, PeriodType.SALARY_CYCLE, PeriodType.SMART_CYCLE)
    } else {
        listOf(PeriodType.MONTH, PeriodType.SALARY_CYCLE)
    }
    val typeLabels = types.map {
        when (it) {
            PeriodType.MONTH -> "Month"
            PeriodType.SALARY_CYCLE -> "Salary"
            PeriodType.SMART_CYCLE -> "Smart"
        }
    }
    val selectedTypeIndex = types.indexOf(current.type).let { if (it < 0) types.indexOf(PeriodType.SALARY_CYCLE) else it }
    val showCardPicker = smartCycleEnabled && current.type == PeriodType.SMART_CYCLE && showCardSection

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            SectionLabel("Cycle")
            Spacer(Modifier.height(10.dp))
            PillSegmentedControl(
                options = typeLabels,
                selectedIndex = selectedTypeIndex,
                onSelect = { idx ->
                    val newType = types[idx]
                    // Reset any prev/next stepping AND any single-card narrowing when the cycle type changes
                    // (#6) — leaving Smart shouldn't silently keep a stale selectedCardId. Smart is ALWAYS the
                    // current cycle (stepped by the arrows), so force CURRENT — a stale Last-N would otherwise
                    // hide the prev/next arrows (they're gated on range == CURRENT).
                    val newRange = if (newType == PeriodType.SMART_CYCLE) PeriodRange.CURRENT else current.range
                    current = current.copy(type = newType, range = newRange, cycleOffset = 0, selectedCardId = null)
                    onSelect(current) // apply immediately
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (current.type == PeriodType.SMART_CYCLE && smartCycleEnabled) {
                // Smart Cycle always resolves the CURRENT cycle (stepped by the prev/next arrows), so Range
                // (All / Last-N / Custom) doesn't apply. Instead, let the user narrow to one card's billing
                // cycle, or keep "All cards" for the whole cycle — unless the caller hides the card section
                // (the category drill-down: a per-category list isn't per-card).
                if (showCardPicker) {
                    Spacer(Modifier.height(18.dp))
                    SectionLabel("Cards")
                    Spacer(Modifier.height(4.dp))
                    RangeRow("All cards", current.selectedCardId == null) {
                        onSelect(current.copy(selectedCardId = null, cycleOffset = 0)); onDismiss()
                    }
                    cards.forEach { card ->
                        CardPickerRow(card, selected = current.selectedCardId == card.id) {
                            onSelect(current.copy(selectedCardId = card.id, cycleOffset = 0)); onDismiss()
                        }
                    }
                    if (cards.isEmpty()) {
                        Text(
                            "Add a card to see its own billing cycle here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    // Card section hidden (category drill-down): explain why nothing else is listed.
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "Smart Cycle shows one cycle at a time — use the ‹ › arrows to move between cycles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
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

/** A card row in the Smart Cycle picker: a coloured chip + the card name, ticked when it's the active one. */
@Composable
private fun CardPickerRow(card: CardChoice, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 26.dp, height = 17.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(parseHexColor(card.colorHex)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            card.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
fun CustomRangeDialog(
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
    val view = LocalView.current
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
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) // a felt tick on each day pick (#12)
                                onSelect(d)
                            },
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
