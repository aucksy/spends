package com.spends.app.ui.categorytxns

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.money.Money
import com.spends.app.core.period.PeriodSelection
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.core.theme.Numerals
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.components.CategoryAvatar
import com.spends.app.ui.components.PeriodSelectorBar
import com.spends.app.ui.components.PillSegmentedControl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryTransactionsScreen(
    onBack: () -> Unit,
    viewModel: CategoryTransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selection by viewModel.periodSelection.collectAsStateWithLifecycle()
    val smartCycleEnabled by viewModel.smartCycleEnabled.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.categoryName.ifBlank { "Category" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Unit
            // The header (with its cycle selector) always shows — even when the chosen cycle has no rows —
            // so the user can switch back to a cycle that does have data instead of being stranded.
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "header") {
                    CategoryHeader(
                        state = state,
                        selection = selection,
                        smartCycleEnabled = smartCycleEnabled,
                        onSelectPeriod = viewModel::setPeriod,
                        onAvgMode = viewModel::setAvgMode,
                        onAvgWindow = viewModel::setAvgWindow,
                        onSelectYear = viewModel::setYear,
                    )
                }
                if (state.rows.isEmpty()) {
                    item(key = "empty") { EmptyCategory(state.avgMode, state.selectedYear) }
                } else {
                    items(state.rows, key = { it.id }) { row -> CategoryTxnRowItem(row) }
                }
            }
        }
    }
}

/**
 * ⭐**One headline number, and the comparison written out in words underneath it.**
 *
 * Two earlier layouts both failed the same way: the cycle total and the monthly average were rendered at
 * identical weight, covering different stretches of time, with nothing on screen stating how they related.
 * Swapping their order (the previous attempt) fixed which one the list belonged to, but left the reader
 * doing the subtraction — and the giveaway was the heading "SPENT IN THIS CYCLE **ONLY**", a warning label
 * compensating for a layout that was still ambiguous.
 *
 * So: **this cycle** is the only headline; the average is demoted to a reference bar beside it, and the
 * answer ("about ₹1,500 more than your usual month") is a sentence rather than an arithmetic exercise. The
 * trailing-window control now lives INSIDE the comparison block, because it only ever changed that figure —
 * sitting a few dp from the cycle stepper, it read as though it changed both.
 *
 * **Yearly gets the identical treatment** (the owner asked for it, for consistency), with the baseline
 * swapped: a year is compared against the newest EARLIER year that has data, per month on both sides so a
 * part-finished year still compares fairly. It keeps its own headline meaning — average per month, with the
 * year's total on the line beneath — because that is the figure that was confirmed correct on the device.
 *
 * The heading above the figure comes from the view model ([CategoryTxnsUiState.periodHeading]) rather than
 * being written here: only that layer knows whether the window is the current cycle, an earlier one, a whole
 * year, or not a cycle at all. Hardcoding "THIS CYCLE" is what made a cycle stepped three months back
 * announce itself as the current one.
 */
@Composable
private fun CategoryHeader(
    state: CategoryTxnsUiState,
    selection: PeriodSelection,
    smartCycleEnabled: Boolean,
    onSelectPeriod: (PeriodSelection) -> Unit,
    onAvgMode: (AvgMode) -> Unit,
    onAvgWindow: (AvgWindow) -> Unit,
    onSelectYear: (Int) -> Unit,
) {
    val yearly = state.avgMode == AvgMode.YEARLY && state.selectedYear != null
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Monthly / Yearly. Hidden entirely when there is nothing to look at year-by-year, rather than
        // offering a button that lands on an empty screen.
        if (state.availableYears.isNotEmpty()) {
            val modes = AvgMode.entries
            PillSegmentedControl(
                options = modes.map { it.label },
                selectedIndex = modes.indexOf(state.avgMode).coerceAtLeast(0),
                onSelect = { onAvgMode(modes[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
        }

        // ---- THE headline. Both modes share it, so the screen reads the same way either way. ----
        // Monthly: what the selected cycle came to. Yearly: the per-month figure for the selected year,
        // with the year's total on the line beneath — the owner asked for both, not one behind a tap.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                // Supplied by the view model: only it knows whether this is the current cycle, an earlier
                // one, or not a cycle at all. Hardcoding "THIS CYCLE" here is what made a cycle from three
                // months ago announce itself as the current one.
                state.periodHeading,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                Money.formatRupees(if (yearly) state.monthlyAverageMinor else state.totalMinor),
                style = Numerals.amountLg,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.height(2.dp))
            val countPart = "${state.count} ${if (state.count == 1) "transaction" else "transactions"}"
            Text(
                if (yearly) {
                    "${Money.formatRupees(state.totalMinor)} in total  ·  $countPart"
                } else {
                    // The concrete dates of the selected cycle. As you step with the ‹ › arrows below, this
                    // updates — the stepper itself shows only the cycle's name.
                    val datePart = state.cycleLabel.takeIf { it.isNotBlank() }?.let { "$it  ·  " } ?: ""
                    "$datePart$countPart"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                maxLines = 2,
            )
        }

        Spacer(Modifier.height(10.dp))

        // ---- The answer, in words, with the baseline as a reference bar rather than a rival headline ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
            Text(
                // Null means there is no baseline: no history at all in Monthly, or no earlier year with
                // data in Yearly. Say which, rather than rendering an empty box or comparing against zero.
                state.comparison?.sentence ?: if (yearly) {
                    "No earlier year to compare ${state.selectedYear} with yet."
                } else {
                    "Not enough history yet to say what a usual month looks like."
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = onContainer,
            )
            state.comparison?.let { c ->
                Spacer(Modifier.height(10.dp))
                ComparisonBar(
                    label = state.comparisonSelfLabel,
                    value = Money.formatRupees(state.comparisonSelfMinor, alwaysTwoDecimals = false),
                    fraction = c.cycleFraction,
                    fill = MaterialTheme.colorScheme.primary,
                    onContainer = onContainer,
                )
                ComparisonBar(
                    label = state.comparisonRefLabel,
                    value = Money.formatRupees(state.comparisonRefMinor, alwaysTwoDecimals = false),
                    fraction = c.usualFraction,
                    fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    onContainer = onContainer,
                )
                if (yearly) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // Both sides are per-month, so a part-finished year compares fairly against a whole
                        // one. Said out loud, because "2026 vs 2025" otherwise invites the opposite reading.
                        "Both figures are per month, so a part-finished year still compares fairly.",
                        style = MaterialTheme.typography.labelSmall,
                        color = onContainer.copy(alpha = 0.7f),
                    )
                }
            }
            if (!yearly) {
                Spacer(Modifier.height(10.dp))
                // The trailing-window control lives HERE, inside the only figure it changes. Sitting outside,
                // a few dp from the cycle stepper, it read as though it re-scoped the cycle as well. Yearly has
                // no equivalent: its baseline is the previous year, which the year chips below already pick.
                val windows = AvgWindow.entries
                PillSegmentedControl(
                    options = windows.map { it.label },
                    selectedIndex = windows.indexOf(state.avgWindow).coerceAtLeast(0),
                    onSelect = { onAvgWindow(windows[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when (state.avgWindow) {
                        AvgWindow.ALL -> "\"Usual\" is averaged across all your history"
                        else -> "\"Usual\" is averaged over the last ${state.avgWindow.label.removeSuffix("M")} months"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer.copy(alpha = 0.7f),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        if (yearly) {
            // Every year with data, newest first, no cap (the owner's choice) — so a LazyRow, which scrolls
            // rather than shrinking each chip until the years are unreadable.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.availableYears, key = { it }) { year ->
                    FilterChip(
                        selected = year == state.selectedYear,
                        onClick = { onSelectYear(year) },
                        label = { Text(year.toString()) },
                    )
                }
            }
        } else {
            // Cycle selector (#5) — a COMPACT single-line control, seeded from the cycle you were viewing so it
            // matches the number you tapped. For a single current cycle it's a ‹ › prev/next stepper; for
            // All-time / Last-N / Custom it's a tappable name. LOCAL: re-scopes the total, count and list without
            // touching the main Transactions/Analytics cycle. label="" keeps it to one line (dates on the hero
            // above). Smart Cycle IS offered (it's one contiguous window now, so it slices a category exactly),
            // but without the card narrowing — a per-category list isn't per-card.
            PeriodSelectorBar(
                selection = selection,
                label = "",
                onSelect = onSelectPeriod,
                smartCycleEnabled = smartCycleEnabled,
                showCardSection = false,
            )
        }
        Spacer(Modifier.height(16.dp))

        // Names what the list underneath actually holds. Without it the rows are simply adjacent to the
        // figures above, which is exactly how a one-cycle list came to be read as six months of history.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (yearly) "What's in ${state.selectedYear}" else "What's in this cycle",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${state.count} ${if (state.count == 1) "item" else "items"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** One labelled bar in the cycle-vs-usual comparison. */
@Composable
private fun ComparisonBar(
    label: String,
    value: String,
    fraction: Float,
    fill: Color,
    onContainer: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = onContainer.copy(alpha = 0.8f),
            modifier = Modifier.width(74.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(onContainer.copy(alpha = 0.12f)),
        ) {
            // Guarded: a zero-spend cycle draws no bar at all rather than a stray rounded stub.
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(50))
                        .background(fill),
                )
            }
        }
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = onContainer.copy(alpha = 0.8f),
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(86.dp).padding(start = 8.dp),
        )
    }
}

@Composable
private fun CategoryTxnRowItem(row: CategoryTxnRow) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(iconKey = row.iconKey, colorHex = row.colorHex)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${row.dateLabel} · ${row.timeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // This row's own date is outside the cycle's printed dates, yet it belongs to the cycle:
                // Smart Cycle buckets by the paying card's BILLING day. Saying so turns what looks like a
                // miscount into the statement date it actually is.
                if (row.billedIntoCycle) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "billed this cycle",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            val note = row.note?.takeIf { it.isNotBlank() && it != row.title }
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        val amountColor = when (row.kind) {
            TxnKind.INCOME -> semantic.income
            TxnKind.EXPENSE -> semantic.expense
        }
        val prefix = when (row.kind) {
            TxnKind.INCOME -> "+"
            TxnKind.EXPENSE -> "-"
        }
        Text(
            text = prefix + Money.formatRupees(row.amountMinor),
            style = Numerals.amountRow,
            color = amountColor,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun EmptyCategory(mode: AvgMode, year: Int?) {
    // Inline (not full-screen) so the header + cycle selector above it stay visible and the user can pick a
    // different cycle.
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No transactions", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                // Naming the period that came up empty, so the user knows which control to change.
                if (mode == AvgMode.YEARLY && year != null) {
                    "Nothing in this category in $year."
                } else {
                    "Nothing in this category for the selected cycle."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
