package com.spends.app.ui.analytics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.category.ColorAssigner
import com.spends.app.core.money.Money
import com.spends.app.core.period.PeriodType
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.core.theme.Numerals
import com.spends.app.ui.components.AutoSizeRupee
import com.spends.app.ui.components.DonutChart
import com.spends.app.ui.components.DonutSlice
import com.spends.app.ui.components.PeriodSelectorBar
import com.spends.app.ui.components.PillSegmentedControl
import com.spends.app.ui.components.SectionLabel
import com.spends.app.ui.components.SpendsCard
import com.spends.app.ui.components.WeeklyBars
import com.spends.app.ui.components.parseHexColor
import com.spends.app.ui.components.rememberSharedAmountStyle
import com.spends.app.ui.components.rupeeText

@Composable
fun AnalyticsScreen(
    onOpenRecurring: () -> Unit,
    onOpenCategory: (categoryId: Long, name: String, cycleLabel: String, startMillis: Long, endExclusiveMillis: Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBreakdown: () -> Unit = {},
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selection by viewModel.periodSelection.collectAsStateWithLifecycle()
    val smartCycleEnabled by viewModel.smartCycleEnabled.collectAsStateWithLifecycle()
    val cardChoices by viewModel.cardChoices.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current
    // The cycle these numbers belong to (#5): the selection name, plus the concrete date range when it adds
    // information (a composite's label already IS its name). Passed to the drill-down so it updates per cycle.
    val cycleLabel = selection.describe().let { name ->
        if (state.periodLabel.isNotBlank() && !state.periodLabel.equals(name, ignoreCase = true)) "$name · ${state.periodLabel}" else name
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        PeriodSelectorBar(
            selection = selection,
            label = state.periodLabel,
            onSelect = viewModel::applySelection,
            onOpenSettings = onOpenSettings,
            smartCycleEnabled = smartCycleEnabled,
            cards = cardChoices,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        // Smart Cycle (#4): the per-instrument breakdown lives here now (moved off the timeline). Gated on
        // the SELECTION (not state.isComposite) — the all-instruments Smart view is a contiguous window now,
        // so isComposite only marks Single-Card, but the breakdown link belongs to both Smart modes.
        if (smartCycleEnabled && selection.type == PeriodType.SMART_CYCLE) {
            BreakdownLinkCard(onClick = onOpenBreakdown)
            Spacer(Modifier.height(12.dp))
        }

        if (state.isEmpty) {
            EmptyAnalytics()
        } else {
            Spacer(Modifier.height(4.dp))
            SummaryCard(state)
            Spacer(Modifier.height(14.dp))
            // ONE toggle for both charts below (see [Lens]). rememberSaveable so the choice survives a
            // rotation and a trip into a category and back — a user analysing their income should not be
            // dropped back into the spending view every time they drill in.
            var lens by rememberSaveable { mutableStateOf(Lens.SPENDING) }
            PillSegmentedControl(
                options = Lens.entries.map { it.label },
                selectedIndex = lens.ordinal,
                onSelect = { lens = Lens.entries[it] },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            // Inject the current cycle label (#5) so the drill-down shows which period these numbers are for.
            CategoryBreakdownCard(state, lens, semantic.dark) { catId, name, start, end ->
                onOpenCategory(catId, name, cycleLabel, start, end)
            }
            Spacer(Modifier.height(14.dp))
            OverTimeCard(state, lens)
            Spacer(Modifier.height(14.dp))
        }

        RecurringCard(state.recurring, onOpenRecurring)
        Spacer(Modifier.height(24.dp))
    }
}

/** Tappable link to the Smart Cycle per-instrument breakdown (#4 — moved here from the timeline header). */
@Composable
private fun BreakdownLinkCard(onClick: () -> Unit) {
    SpendsCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                "Per-instrument breakdown",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open breakdown", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SummaryCard(state: AnalyticsUiState) {
    val semantic = LocalSemanticColors.current
    val netColor = if (state.netMinor < 0) semantic.negative else semantic.income
    SpendsCard(modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            val gap = 8.dp
            val cellW = (maxWidth - gap * 2) / 3
            val density = LocalDensity.current
            val cellWpx = with(density) { cellW.toPx().toInt() }
            // The three figures share ONE font scale (#12) so Expense / Income / Net never mismatch.
            val sharedStyle = rememberSharedAmountStyle(
                texts = listOf(
                    rupeeText(state.expenseMinor, false),
                    rupeeText(state.incomeMinor, false),
                    rupeeText(state.netMinor, true),
                ),
                baseStyle = Numerals.amountLg,
                maxWidthPx = cellWpx,
                minScale = 0.3f,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                SummaryCell(Modifier.width(cellW), "Expense", Icons.Filled.ArrowUpward, semantic.expense, state.expenseMinor, false, sharedStyle)
                SummaryCell(Modifier.width(cellW), "Income", Icons.Filled.ArrowDownward, semantic.income, state.incomeMinor, false, sharedStyle)
                SummaryCell(Modifier.width(cellW), "Net", Icons.AutoMirrored.Filled.ArrowForward, netColor, state.netMinor, true, sharedStyle)
            }
        }
    }
}

@Composable
private fun SummaryCell(
    modifier: Modifier,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    minor: Long,
    withSign: Boolean,
    amountStyle: TextStyle,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        // Plain Text with the SHARED style (#12) — all three figures render at the same size.
        Text(
            text = rupeeText(minor, withSign),
            style = amountStyle,
            color = accent,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/**
 * The category donut + tappable legend, for whichever side of the ledger [lens] selects. One composable
 * rather than a spending copy and an income copy: the two are the same chart over a different total, and
 * a duplicate would be where the income view quietly stopped matching the spending view's behaviour.
 */
@Composable
private fun CategoryBreakdownCard(
    state: AnalyticsUiState,
    lens: Lens,
    dark: Boolean,
    onOpenCategory: (categoryId: Long, name: String, startMillis: Long, endExclusiveMillis: Long) -> Unit,
) {
    fun catColor(hex: String) = parseHexColor(if (dark) ColorAssigner.darkVariant(hex) else hex)
    val slices = state.slicesFor(lens)
    val income = lens == Lens.INCOME
    SpendsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            SectionLabel(if (income) "Income by category" else "Spending by category")
            Spacer(Modifier.height(14.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                DonutChart(
                    slices = slices.map { DonutSlice(catColor(it.colorHex), it.amountMinor.toFloat()) },
                    modifier = Modifier.size(200.dp),
                    center = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.widthIn(max = 104.dp),
                        ) {
                            Text(
                                if (income) "EARNED" else "SPENT",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // The categorised total so the centre figure reconciles with the wedges + legend.
                            AutoSizeRupee(
                                minor = state.categorisedFor(lens),
                                style = Numerals.amountLg,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            if (slices.isEmpty()) {
                Text(
                    if (income) "No categorised income this period." else "No categorised spending this period.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                slices.forEachIndexed { index, c ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOpenCategory(c.categoryId, c.name, state.windowStartMillis, state.windowEndExclusiveMillis)
                            },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                        ) {
                            Box(modifier = Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(catColor(c.colorHex)))
                            Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("${c.percent}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                Money.format(c.amountMinor),
                                style = Numerals.amountRow,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.widthIn(min = 70.dp),
                                textAlign = TextAlign.End,
                            )

                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Open ${c.name} transactions",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverTimeCard(state: AnalyticsUiState, lens: Lens) {
    val income = lens == Lens.INCOME
    SpendsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            SectionLabel(if (income) "Income over time" else "Spend over time")
            Spacer(Modifier.height(14.dp))
            WeeklyBars(values = state.barsFor(lens), labels = state.weekLabels)
            Spacer(Modifier.height(10.dp))
            Text(
                // Both series use the SAME bucket boundaries, so the note says which one is on screen
                // rather than implying the other is mixed in.
                if (income) "Shows income only." else "Shows spending only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecurringCard(rows: List<RecurringFreqSummary>, onOpenRecurring: () -> Unit) {
    val semantic = LocalSemanticColors.current
    SpendsCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenRecurring)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Autorenew, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Text("Recurring", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open recurring", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            if (rows.isEmpty()) {
                Text(
                    "No recurring transactions yet. Tap to add rent, salary, EMIs or subscriptions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                rows.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.frequency.label(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            if (row.outMinor > 0) Text("-" + Money.format(row.outMinor), style = Numerals.amountRow, color = semantic.expense)
                            if (row.inMinor > 0) Text("+" + Money.format(row.inMinor), style = Numerals.amountRow, color = semantic.income)
                        }
                    }
                }
                val total = rows.sumOf { it.count }
                Spacer(Modifier.height(4.dp))
                Text(
                    "$total active ${if (total == 1) "rule" else "rules"} · amounts per occurrence",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyAnalytics() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Insights, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text("Nothing to chart yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Add transactions or pick another period to see your spending breakdown.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun com.spends.app.domain.model.RecurrenceFreq.label(): String = when (this) {
    com.spends.app.domain.model.RecurrenceFreq.DAILY -> "Daily"
    com.spends.app.domain.model.RecurrenceFreq.WEEKLY -> "Weekly"
    com.spends.app.domain.model.RecurrenceFreq.MONTHLY -> "Monthly"
    com.spends.app.domain.model.RecurrenceFreq.YEARLY -> "Yearly"
}
