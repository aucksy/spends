package com.spends.app.ui.analytics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.category.ColorAssigner
import com.spends.app.core.money.Money
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.core.theme.Numerals
import com.spends.app.ui.components.AutoSizeRupee
import com.spends.app.ui.components.DonutChart
import com.spends.app.ui.components.DonutSlice
import com.spends.app.ui.components.PeriodSelectorBar
import com.spends.app.ui.components.SectionLabel
import com.spends.app.ui.components.SpendsCard
import com.spends.app.ui.components.WeeklyBars
import com.spends.app.ui.components.parseHexColor

@Composable
fun AnalyticsScreen(
    onOpenRecurring: () -> Unit,
    onOpenCategory: (categoryId: Long, name: String, startMillis: Long, endExclusiveMillis: Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selection by viewModel.periodSelection.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text("Analytics", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        PeriodSelectorBar(
            selection = selection,
            label = state.periodLabel,
            onSelect = viewModel::applySelection,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        if (state.isEmpty) {
            EmptyAnalytics()
        } else {
            Spacer(Modifier.height(4.dp))
            SummaryCard(state)
            Spacer(Modifier.height(14.dp))
            CategoryDonutCard(state, semantic.dark, semantic.transfer, onOpenCategory)
            Spacer(Modifier.height(14.dp))
            SpendOverTimeCard(state)
            Spacer(Modifier.height(14.dp))
        }

        RecurringCard(state.recurring, onOpenRecurring)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SummaryCard(state: AnalyticsUiState) {
    val semantic = LocalSemanticColors.current
    SpendsCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCell(Modifier.weight(1f), "Expense", Icons.Filled.ArrowUpward, semantic.expense, state.expenseMinor, false)
            SummaryCell(Modifier.weight(1f), "Income", Icons.Filled.ArrowDownward, semantic.income, state.incomeMinor, false)
            SummaryCell(
                Modifier.weight(1f),
                "Net",
                Icons.AutoMirrored.Filled.ArrowForward,
                if (state.netMinor < 0) semantic.negative else semantic.income,
                state.netMinor,
                true,
            )
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
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        AutoSizeRupee(
            minor = minor,
            style = Numerals.amountLg,
            color = accent,
            withSign = withSign,
            // Three big figures share one row, so allow a deeper shrink — the "Net" lakh amount with
            // decimals was overflowing its cell and clipping the paise off-screen (#7).
            minScale = 0.3f,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun CategoryDonutCard(
    state: AnalyticsUiState,
    dark: Boolean,
    transferColor: Color,
    onOpenCategory: (categoryId: Long, name: String, startMillis: Long, endExclusiveMillis: Long) -> Unit,
) {
    fun catColor(hex: String) = parseHexColor(if (dark) ColorAssigner.darkVariant(hex) else hex)
    SpendsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            SectionLabel("Spending by category")
            Spacer(Modifier.height(14.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                DonutChart(
                    slices = state.categories.map { DonutSlice(catColor(it.colorHex), it.amountMinor.toFloat()) },
                    modifier = Modifier.size(200.dp),
                    center = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.widthIn(max = 104.dp),
                        ) {
                            Text("SPENT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // The categorised total so the centre figure reconciles with the wedges + legend.
                            AutoSizeRupee(
                                minor = state.categorisedSpendMinor,
                                style = Numerals.amountLg,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            if (state.categories.isEmpty()) {
                Text(
                    "No categorised spending this period.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.categories.take(8).forEachIndexed { index, c ->
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
                                Money.formatRupees(c.amountMinor),
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
            if (state.transferMinor > 0) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = transferColor, modifier = Modifier.size(17.dp))
                    Text("Excludes transfers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(Money.formatRupees(state.transferMinor), style = Numerals.amountSmall, color = transferColor)
                }
            }
        }
    }
}

@Composable
private fun SpendOverTimeCard(state: AnalyticsUiState) {
    SpendsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            SectionLabel("Spend over time")
            Spacer(Modifier.height(14.dp))
            WeeklyBars(values = state.weekly, labels = state.weekLabels)
            Spacer(Modifier.height(10.dp))
            Text(
                "Excludes transfers.",
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
                            if (row.outMinor > 0) Text("-" + Money.formatRupees(row.outMinor), style = Numerals.amountRow, color = semantic.expense)
                            if (row.inMinor > 0) Text("+" + Money.formatRupees(row.inMinor), style = Numerals.amountRow, color = semantic.income)
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
