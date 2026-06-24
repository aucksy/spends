package com.spends.app.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.ui.components.AnimatedRupee

@Composable
fun SummaryHeader(
    state: TransactionsUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        // Period stepper
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous period")
            }
            Text(
                text = state.periodLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onNext, enabled = state.canStepForward) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next period")
            }
        }

        // Horizontally scrollable stat cards
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatCard("Expense", state.totals.expense, semantic.expense)
            }
            item {
                StatCard("Income", state.totals.income, semantic.income)
            }
            item {
                val balColor = if (state.totals.balance < 0) semantic.expense else semantic.income
                StatCard("Balance", state.totals.balance, balColor, withSign = true)
            }
            if (state.carryForward != null) {
                item {
                    StatCard("Carry Forward", state.carryForward, MaterialTheme.colorScheme.onSurfaceVariant, withSign = true)
                }
                item {
                    val total = state.balanceWithCarry ?: 0
                    val color = if (total < 0) semantic.expense else semantic.income
                    StatCard("With Carry Fwd", total, color, withSign = true)
                }
            }
            if (state.totals.transfer > 0) {
                item {
                    StatCard("Transfers", state.totals.transfer, semantic.transfer)
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    minor: Long,
    accent: Color,
    withSign: Boolean = false,
) {
    Card(
        modifier = Modifier.width(150.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedRupee(
                minor = minor,
                style = MaterialTheme.typography.titleLarge,
                color = accent,
                withSign = withSign,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
