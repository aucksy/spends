package com.spends.app.ui.analytics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.money.Money
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.core.theme.Numerals
import com.spends.app.domain.model.RecurrenceFreq

@Composable
fun AnalyticsScreen(
    onOpenRecurring: () -> Unit,
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val recurring by viewModel.recurringByFreq.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        RecurringSummaryCard(recurring, onOpenRecurring)

        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Insights,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(12.dp))
            Text("More analytics coming", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            Text(
                "Cycle-aware charts — spending by category, trends over time, and per-card cycles — " +
                    "arrive in the next update.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RecurringSummaryCard(rows: List<RecurringFreqSummary>, onOpenRecurring: () -> Unit) {
    val semantic = LocalSemanticColors.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenRecurring),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Autorenew, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Text("Recurring", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open recurring",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.frequency.label(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            if (row.outMinor > 0) {
                                Text(
                                    "-" + Money.formatRupees(row.outMinor),
                                    style = Numerals.amountRow,
                                    color = semantic.expense,
                                )
                            }
                            if (row.inMinor > 0) {
                                Text(
                                    "+" + Money.formatRupees(row.inMinor),
                                    style = Numerals.amountRow,
                                    color = semantic.income,
                                )
                            }
                        }
                    }
                }
                val total = rows.sumOf { it.count }
                Spacer(Modifier.height(4.dp))
                Text(
                    "$total active ${if (total == 1) "rule" else "rules"} · amounts per occurrence",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}

private fun RecurrenceFreq.label(): String = when (this) {
    RecurrenceFreq.DAILY -> "Daily"
    RecurrenceFreq.WEEKLY -> "Weekly"
    RecurrenceFreq.MONTHLY -> "Monthly"
    RecurrenceFreq.YEARLY -> "Yearly"
}
