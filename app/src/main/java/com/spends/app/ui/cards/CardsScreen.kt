package com.spends.app.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.theme.Numerals
import com.spends.app.ui.components.SpendsCard
import com.spends.app.ui.components.SectionLabel
import com.spends.app.ui.components.parseHexColor
import com.spends.app.ui.components.rupeeText

/**
 * The Cards tab (shown only when Smart Cycle is on). Lists the user's cards with each card's CURRENT
 * billing-cycle spend, surfaces auto-discovered cards for review, and lets the user add/edit/merge/delete
 * cards or scan their SMS for more. Round A: per-card cycles are shown here; the composite "one number
 * across everything" total + per-instrument analytics arrive in Round B.
 */
@Composable
fun CardsScreen(
    onOpenSettings: () -> Unit,
    viewModel: CardsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CardUi?>(null) }
    var reviewing by remember { mutableStateOf<CandidateUi?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // A plain top row (not a nested Scaffold/TopAppBar) — this screen renders inside HomeScreen's
        // Scaffold, so its own Scaffold would double the bottom-nav inset and clip the last card.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Cards", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, contentDescription = "Add card") }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Bottom padding clears the + / eye FABs that HomeScreen overlays on every tab.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.candidates.isNotEmpty()) {
                item { SectionLabel("Cards to review") }
                items(state.candidates, key = { it.id }) { cand ->
                    CandidateCard(
                        candidate = cand,
                        onAdd = { viewModel.confirmCandidate(cand.id, cand.label, cand.last4, cand.institution, null, null) },
                        onEdit = { reviewing = cand },
                        onDismiss = { viewModel.dismissCandidate(cand.id) },
                    )
                }
                item { Spacer(Modifier.height(2.dp)) }
            }

            item { SectionLabel("Your cards") }
            if (state.cards.isEmpty()) {
                item { EmptyCardsHint() }
            } else {
                items(state.cards, key = { it.id }) { card ->
                    CardRow(card = card, onClick = { editing = card })
                }
            }

            item {
                ScanSection(
                    scanning = state.scanning,
                    message = state.message,
                    onScan = { viewModel.clearMessage(); viewModel.scanForCards() },
                )
            }
        }
    }

    if (showAdd) {
        CardEditorSheet(
            title = "Add card",
            saveLabel = "Add card",
            initial = CardEditorInitial(),
            onSave = { l, l4, inst, bd, dd -> viewModel.addCard(l, l4, inst, bd, dd); showAdd = false },
            onDismiss = { showAdd = false },
        )
    }

    editing?.let { card ->
        CardEditorSheet(
            title = "Edit card",
            saveLabel = "Save",
            initial = CardEditorInitial(card.label, card.last4, card.institution, card.billingDay, card.dueDay),
            onSave = { l, l4, inst, bd, dd -> viewModel.updateCard(card.id, l, l4, inst, bd, dd); editing = null },
            onDismiss = { editing = null },
            onDelete = { viewModel.deleteCard(card.id); editing = null },
            mergeTargets = state.cards.filter { it.id != card.id },
            onMerge = { targetId -> viewModel.mergeCards(card.id, targetId); editing = null },
        )
    }

    reviewing?.let { cand ->
        CardEditorSheet(
            title = "Review card",
            saveLabel = "Add card",
            initial = CardEditorInitial(cand.label, cand.last4, cand.institution, null, null),
            onSave = { l, l4, inst, bd, dd -> viewModel.confirmCandidate(cand.id, l, l4, inst, bd, dd); reviewing = null },
            onDismiss = { reviewing = null },
        )
    }
}

/** A 36×24 coloured card chip with a tiny bottom-right tag (Design System: "My Cycle breakdown" rows). */
@Composable
private fun CardChip(colorHex: String, tag: String = "CR") {
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 24.dp)
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

@Composable
private fun CardRow(card: CardUi, onClick: () -> Unit) {
    SpendsCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardChip(card.colorHex)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(card.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = cardSubline(card),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(rupeeText(card.cycleSpendMinor), style = Numerals.amountRow, maxLines = 1)
                Text(card.cycleLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun cardSubline(card: CardUi): String {
    val parts = buildList {
        card.last4?.let { add("·$it") }
        add(card.billsLabel ?: "Salary cycle")
        add("${card.txnCount} txn${if (card.txnCount == 1) "" else "s"}")
    }
    return parts.joinToString("  ·  ")
}

@Composable
private fun CandidateCard(
    candidate: CandidateUi,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    SpendsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardChip(candidate.colorHex)
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        candidate.label + (candidate.last4?.let { " ·$it" } ?: ""),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Found in your SMS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onAdd) { Text("Add card") }
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Not a card") }
            }
        }
    }
}

@Composable
private fun EmptyCardsHint() {
    SpendsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                "No cards yet",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Add a card, or scan your SMS to find the cards you spend with. Each card gets its own billing cycle.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScanSection(scanning: Boolean, message: String?, onScan: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        OutlinedButton(onClick = onScan, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
            if (scanning) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Scanning…")
            } else {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Scan past SMS for cards")
            }
        }
        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "We look for credit-card alerts in your SMS and suggest cards to review — nothing is added automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
