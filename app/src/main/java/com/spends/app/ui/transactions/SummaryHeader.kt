package com.spends.app.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.core.theme.Numerals
import com.spends.app.ui.components.AutoSizeRupee
import com.spends.app.ui.components.rememberSharedAmountStyle
import com.spends.app.ui.components.rupeeText

@Composable
fun SummaryHeader(
    state: TransactionsUiState,
    modifier: Modifier = Modifier,
    onOpenBreakdown: () -> Unit = {},
) {
    val semantic = LocalSemanticColors.current
    val scheme = MaterialTheme.colorScheme

    // Tiles shown in the horizontally-scrollable strip. Expense + Income always; Carry forward and
    // Transfers join only when relevant, so two fill the width and extras scroll into view.
    val tiles = buildList {
        add(Tile("Expense", state.totals.expense, semantic.expense, Icons.Filled.ArrowUpward, withSign = false))
        add(Tile("Income", state.totals.income, semantic.income, Icons.Filled.ArrowDownward, withSign = false))
        if (state.carryForward != null) {
            add(Tile("Carry forward", state.carryForward, semantic.transfer, Icons.AutoMirrored.Filled.ArrowForward, withSign = true))
        }
        if (state.totals.transfer > 0) {
            add(Tile("Transfers", state.totals.transfer, semantic.transfer, Icons.Filled.SwapHoriz, withSign = false))
        }
    }

    // The headline balance: with carry-forward on, the meaningful figure is the rolled-in balance.
    val heroBalance = if (state.carryForward != null) (state.balanceWithCarry ?: 0) else state.totals.balance
    val negative = heroBalance < 0

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // Stat tiles — exactly two fill the row; any extras (carry forward / transfers) scroll.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            val gap = 10.dp
            val tileW = (maxWidth - gap) / 2
            // All tiles share ONE font scale (#12): every figure is measured at the tile's inner width and
            // the smallest fit is applied to all — so Expense/Income (and any extra tiles) are always the
            // SAME size, never one shrunk and one full-size.
            val density = LocalDensity.current
            val innerWpx = with(density) { (tileW - 28.dp).toPx().toInt() } // 14dp horizontal padding each side
            val sharedStyle = rememberSharedAmountStyle(
                texts = tiles.map { rupeeText(it.minor, it.withSign) },
                baseStyle = Numerals.amountLg,
                maxWidthPx = innerWpx,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                tiles.forEach { tile ->
                    StatTile(tile = tile, amountStyle = sharedStyle, modifier = Modifier.width(tileW))
                }
            }
        }

        // Balance hero — teal when in the black, soft-rose with red ink when in deficit.
        val heroBg = when {
            negative -> semantic.negativeContainer
            semantic.dark -> scheme.primaryContainer
            else -> scheme.primary
        }
        val heroOn = when {
            negative -> semantic.negative
            semantic.dark -> scheme.onPrimaryContainer
            else -> scheme.onPrimary
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = heroBg),
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text(
                    text = "BALANCE · ${state.periodLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = heroOn.copy(alpha = 0.85f),
                )
                AutoSizeRupee(
                    minor = heroBalance,
                    style = Numerals.balanceHero,
                    color = heroOn,
                    withSign = true,
                    // Shrink the figure to fit the tile width instead of letting it overflow / scroll (#4).
                    minScale = 0.35f,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }

        // Smart Cycle composite (Round B): this one number spans instruments each on their own cycle, so
        // offer a tap to see the per-instrument breakdown (design Screen 2, "My Cycle breakdown").
        if (state.isComposite) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.surfaceVariant)
                    .clickable(onClick = onOpenBreakdown)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.AccountBalanceWallet,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Per-instrument breakdown",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open breakdown",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private data class Tile(
    val label: String,
    val minor: Long,
    val accent: Color,
    val icon: ImageVector,
    val withSign: Boolean,
)

@Composable
private fun StatTile(tile: Tile, amountStyle: TextStyle, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(tile.icon, contentDescription = null, tint = tile.accent, modifier = Modifier.size(16.dp))
                Text(
                    tile.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // Plain Text with the SHARED style (#12) — not an independent AutoSizeRupee — so all tiles match.
            Text(
                text = rupeeText(tile.minor, tile.withSign),
                style = amountStyle,
                color = tile.accent,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}
