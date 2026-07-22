package com.spends.app.ui.breakdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.theme.Numerals
import com.spends.app.ui.components.SectionLabel
import com.spends.app.ui.components.parseHexColor
import com.spends.app.ui.components.rupeeText

/**
 * Smart Cycle "per-instrument breakdown" (design Screen 2 · "My Cycle breakdown"): one teal TOTAL card,
 * then the credit cards and the Bank/UPI bucket, each showing what it paid inside the ONE Smart Cycle
 * window (anchored on the reset day) — so every row and the TOTAL reconcile with the timeline and
 * Analytics. The TOTAL is spend (expense only) — it matches the Analytics donut centre / total expense,
 * not the timeline balance hero (income − expense).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleBreakdownScreen(
    onBack: () -> Unit,
    viewModel: CycleBreakdownViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Cycle breakdown") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        ) {
            item {
                TotalCard(state)
                Spacer(Modifier.height(14.dp))
            }
            item {
                SectionLabel("Credit cards")
                Spacer(Modifier.height(8.dp))
            }
            item {
                if (state.cards.isEmpty()) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "No cards yet — add a card to track its own billing cycle here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    InstrumentGroup(state.cards)
                }
                Spacer(Modifier.height(14.dp))
            }
            item {
                SectionLabel("Bank / UPI")
                Spacer(Modifier.height(8.dp))
                InstrumentGroup(state.banks)
            }
        }
    }
}

@Composable
private fun TotalCard(state: CycleBreakdownUiState) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.primary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "TOTAL THIS CYCLE",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onPrimary.copy(alpha = 0.85f),
                )
                Text(
                    rupeeText(state.totalSpendMinor),
                    style = Numerals.balanceHero,
                    color = scheme.onPrimary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${state.instrumentCount} instrument${if (state.instrumentCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onPrimary.copy(alpha = 0.85f),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${state.cardCount} card${if (state.cardCount == 1) "" else "s"} · ${state.bankCount} bank${if (state.bankCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onPrimary.copy(alpha = 0.85f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun InstrumentGroup(rows: List<InstrumentRowUi>) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            rows.forEachIndexed { index, row ->
                InstrumentRow(row)
                if (index < rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun InstrumentRow(row: InstrumentRowUi) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        InstrumentChip(row.colorHex, if (row.isCard) "CR" else "A/C")
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            rupeeText(row.amountMinor),
            style = Numerals.amountRow,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

/** A 34×22 coloured instrument chip with a tiny bottom-right tag (matches the Cards screen chip). */
@Composable
private fun InstrumentChip(colorHex: String, tag: String) {
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 22.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(parseHexColor(colorHex)),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Text(
            tag,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.padding(end = 3.dp, bottom = 2.dp),
        )
    }
}
