package com.spends.app.ui.categorytxns

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
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
                        onSelectPeriod = viewModel::setPeriod,
                        onAvgWindow = viewModel::setAvgWindow,
                    )
                }
                if (state.rows.isEmpty()) {
                    item(key = "empty") { EmptyCategory() }
                } else {
                    items(state.rows, key = { it.id }) { row -> CategoryTxnRowItem(row) }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(
    state: CategoryTxnsUiState,
    selection: PeriodSelection,
    onSelectPeriod: (PeriodSelection) -> Unit,
    onAvgWindow: (AvgWindow) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "TOTAL SPENT",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            Money.formatRupees(state.totalMinor),
            style = Numerals.amountLg,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        // Count + the concrete dates of the selected cycle. As you step cycles with the ‹ › arrows below,
        // this line updates so the exact window is always clear (the stepper itself shows only the name).
        val datePart = state.cycleLabel.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""
        Text(
            "${state.count} ${if (state.count == 1) "transaction" else "transactions"}$datePart",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )

        // Cycle selector (#5) — a COMPACT single-line control, seeded from the cycle you were viewing so it
        // matches the number you tapped. For a single current cycle it's a ‹ › prev/next stepper; for
        // All-time / Last-N / Custom it's a tappable name. LOCAL: re-scopes the total, count and list without
        // touching the main Transactions/Analytics cycle. label="" keeps it to one line (dates on the count
        // line above). Smart Cycle isn't offered here (a composite doesn't map to one category).
        Spacer(Modifier.height(14.dp))
        PeriodSelectorBar(
            selection = selection,
            label = "",
            onSelect = onSelectPeriod,
            smartCycleEnabled = false,
        )

        Spacer(Modifier.height(18.dp))
        // Monthly average with its own trailing window (#8): "Last" is static; 3M / 6M / All are tappable.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Monthly average",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                Money.formatRupees(state.monthlyAverageMinor),
                style = Numerals.amountRow,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Last",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            val windows = AvgWindow.entries
            PillSegmentedControl(
                options = windows.map { it.label },
                selectedIndex = windows.indexOf(state.avgWindow).coerceAtLeast(0),
                onSelect = { onAvgWindow(windows[it]) },
                modifier = Modifier.weight(1f),
            )
        }
        // Breathing room before the transaction list (#5).
        Spacer(Modifier.height(14.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
            Text(
                text = "${row.dateLabel} · ${row.timeLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
private fun EmptyCategory() {
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
                "Nothing in this category for the selected cycle.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
