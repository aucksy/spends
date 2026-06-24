package com.spends.app.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.core.theme.Numerals
import com.spends.app.ui.components.AnimatedRupee

@Composable
fun SummaryHeader(
    state: TransactionsUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalSemanticColors.current
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // Period stepper
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous period")
            }
            Text(
                text = state.periodLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = onNext, enabled = state.canStepForward) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next period")
            }
        }

        // Expense + Income tiles
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "Expense",
                minor = state.totals.expense,
                accent = semantic.expense,
                icon = Icons.Filled.ArrowDownward,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Income",
                minor = state.totals.income,
                accent = semantic.income,
                icon = Icons.Filled.ArrowUpward,
                modifier = Modifier.weight(1f),
            )
        }

        // Balance hero
        val heroBg = if (semantic.dark) scheme.primaryContainer else scheme.primary
        val heroOn = if (semantic.dark) scheme.onPrimaryContainer else scheme.onPrimary
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = heroBg),
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Text(
                    text = "BALANCE · ${state.periodLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = heroOn.copy(alpha = 0.85f),
                )
                AnimatedRupee(
                    minor = state.totals.balance,
                    style = Numerals.balanceHero,
                    color = heroOn,
                    withSign = true,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // Carry forward (when enabled)
        if (state.carryForward != null) {
            val cf = state.carryForward
            val withCf = state.balanceWithCarry ?: 0
            CarryRow("Carry forward", cf, semantic.transfer)
            CarryRow("With carry forward", withCf, if (withCf < 0) semantic.negative else semantic.income)
        }
        if (state.totals.transfer > 0) {
            CarryRow("Transfers", state.totals.transfer, semantic.transfer)
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    minor: Long,
    accent: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedRupee(
                minor = minor,
                style = Numerals.amountLg,
                color = accent,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun CarryRow(label: String, minor: Long, accent: Color) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedRupee(minor = minor, style = Numerals.amountRow, color = accent, withSign = true)
        }
    }
}
