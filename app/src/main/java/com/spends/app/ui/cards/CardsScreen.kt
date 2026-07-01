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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.spends.app.ui.components.NumberWheelPicker
import com.spends.app.ui.components.SpendsCard
import com.spends.app.ui.components.SectionLabel
import com.spends.app.ui.components.parseHexColor
import com.spends.app.ui.components.rupeeText

/**
 * The Banks & Cards management screen (#3 — moved out of the bottom nav into Settings). Lists the user's
 * cards (each with its own billing-cycle spend) and banks (salary cycle), surfaces auto-discovered cards
 * for review, lets the user add/edit/delete instruments, pick a default "Paid with" for new expenses (#2),
 * set a common billing day across cards (#10), or scan SMS for more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardsScreen(
    onBack: () -> Unit,
    viewModel: CardsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dismissed by viewModel.dismissed.collectAsStateWithLifecycle()
    var showAddCard by remember { mutableStateOf(false) }
    var showAddBank by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CardUi?>(null) }
    var editingBank by remember { mutableStateOf<CardUi?>(null) }
    var reviewing by remember { mutableStateOf<CandidateUi?>(null) }
    var dismissConfirm by remember { mutableStateOf<CandidateUi?>(null) }
    var showDefaultPicker by remember { mutableStateOf(false) }
    var showCommonBilling by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Banks & Cards") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add")
                        }
                        DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Add card") },
                                leadingIcon = { Icon(Icons.Filled.CreditCard, contentDescription = null) },
                                onClick = { showAddMenu = false; showAddCard = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Add bank") },
                                leadingIcon = { Icon(Icons.Filled.AccountBalance, contentDescription = null) },
                                onClick = { showAddMenu = false; showAddBank = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                DefaultInstrumentRow(label = state.defaultLabel, onClick = { showDefaultPicker = true })
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("Your cards", modifier = Modifier.weight(1f))
                    if (state.cards.size > 1) {
                        TextButton(onClick = { showCommonBilling = true }) { Text("Set common billing day") }
                    }
                }
            }
            if (state.cards.isEmpty()) {
                item { EmptyHint("No cards yet", "Add a card, or scan your SMS. Each card gets its own billing cycle.") }
            } else {
                items(state.cards, key = { "card-${it.id}" }) { card ->
                    CardRow(card = card, tag = "CR", onClick = { editing = card })
                }
            }

            item { SectionLabel("Your banks") }
            if (state.banks.isEmpty()) {
                item { EmptyHint("No banks yet", "Add a bank or UPI account. Banks ride your salary cycle.") }
            } else {
                items(state.banks, key = { "bank-${it.id}" }) { bank ->
                    CardRow(card = bank, tag = "A/C", onClick = { editingBank = bank })
                }
            }

            // "Cards to review" sits BELOW the added cards/banks (#14) — suggestions, not the main list.
            if (state.candidates.isNotEmpty()) {
                item { Column { Spacer(Modifier.height(2.dp)); SectionLabel("Cards to review") } }
                items(state.candidates, key = { "cand-${it.id}" }) { cand ->
                    CandidateCard(
                        candidate = cand,
                        onAdd = { viewModel.confirmCandidate(cand.id, cand.label, cand.last4, cand.institution, null, null) },
                        onEdit = { reviewing = cand },
                        onNotACard = { dismissConfirm = cand },
                        onRemove = { viewModel.removeCandidate(cand.id) },
                    )
                }
            }

            // Dismissed ("Not a card") — restorable in case something was rejected by mistake (#14).
            if (dismissed.isNotEmpty()) {
                item { Column { Spacer(Modifier.height(2.dp)); SectionLabel("Dismissed") } }
                items(dismissed, key = { "dis-${it.id}" }) { cand ->
                    DismissedRow(candidate = cand, onRestore = { viewModel.restoreDismissed(cand.id) })
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

    if (showAddCard) {
        CardEditorSheet(
            title = "Add card",
            saveLabel = "Add card",
            isBank = false,
            initial = CardEditorInitial(),
            onSave = { l, l4, inst, bd, dd -> viewModel.addCard(l, l4, inst, bd, dd); showAddCard = false },
            onDismiss = { showAddCard = false },
        )
    }

    if (showAddBank) {
        CardEditorSheet(
            title = "Add bank",
            saveLabel = "Add bank",
            isBank = true,
            initial = CardEditorInitial(),
            onSave = { l, l4, inst, _, _ -> viewModel.addBank(l, l4, inst); showAddBank = false },
            onDismiss = { showAddBank = false },
        )
    }

    editing?.let { card ->
        CardEditorSheet(
            title = "Edit card",
            saveLabel = "Save",
            isBank = false,
            initial = CardEditorInitial(card.label, card.last4, card.institution, card.billingDay, card.dueDay),
            onSave = { l, l4, inst, bd, dd -> viewModel.updateCard(card.id, l, l4, inst, bd, dd); editing = null },
            onDismiss = { editing = null },
            onDelete = { viewModel.deleteCard(card.id); editing = null },
        )
    }

    editingBank?.let { bank ->
        CardEditorSheet(
            title = "Edit bank",
            saveLabel = "Save",
            isBank = true,
            initial = CardEditorInitial(bank.label, bank.last4, bank.institution, null, null),
            onSave = { l, l4, inst, _, _ -> viewModel.updateCard(bank.id, l, l4, inst, null, null); editingBank = null },
            onDismiss = { editingBank = null },
            onDelete = { viewModel.deleteCard(bank.id); editingBank = null },
        )
    }

    reviewing?.let { cand ->
        CardEditorSheet(
            title = "Review card",
            saveLabel = "Add card",
            isBank = false,
            initial = CardEditorInitial(cand.label, cand.last4, cand.institution, null, null),
            onSave = { l, l4, inst, bd, dd -> viewModel.confirmCandidate(cand.id, l, l4, inst, bd, dd); reviewing = null },
            onDismiss = { reviewing = null },
        )
    }

    if (showDefaultPicker) {
        val options = state.cards.map { CardOption(it.id, it.label, it.last4, it.colorHex, isCard = true) } +
            state.banks.map { CardOption(it.id, it.label, it.last4, it.colorHex, isCard = false) }
        PaidWithPickerSheet(
            cards = options,
            selectedId = state.defaultId,
            onSelect = { viewModel.setDefaultInstrument(it); showDefaultPicker = false },
            onDismiss = { showDefaultPicker = false },
        )
    }

    if (showCommonBilling) {
        CommonBillingDialog(
            cards = state.cards,
            onApply = { ids, day -> viewModel.setCommonBillingDay(ids, day); showCommonBilling = false },
            onDismiss = { showCommonBilling = false },
        )
    }

    // "Not a card" is permanent-ish (hidden from future scans), so confirm it — with a note that it's
    // restorable from the Dismissed section (#14).
    dismissConfirm?.let { cand ->
        AlertDialog(
            onDismissRequest = { dismissConfirm = null },
            title = { Text("Not a card?") },
            text = { Text("We'll stop suggesting \"${cand.label}\". You can bring it back later from the Dismissed section below.") },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissCandidate(cand.id); dismissConfirm = null }) { Text("Not a card") }
            },
            dismissButton = { TextButton(onClick = { dismissConfirm = null }) { Text("Cancel") } },
        )
    }
}

/** The "Default for new expenses" row (#2) — tap to pick which instrument new expenses pre-select. */
@Composable
private fun DefaultInstrumentRow(label: String, onClick: () -> Unit) {
    SpendsCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Default for new expenses", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(
                    "New expenses start as: $label",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A coloured chip with a tiny bottom-right tag ("CR" for cards, "A/C" for banks). */
@Composable
private fun CardChip(colorHex: String, tag: String) {
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 24.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(parseHexColor(colorHex)),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Text(tag, fontSize = 7.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(end = 3.dp, bottom = 2.dp))
    }
}

@Composable
private fun CardRow(card: CardUi, tag: String, onClick: () -> Unit) {
    SpendsCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardChip(card.colorHex, tag)
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
private fun CandidateCard(candidate: CandidateUi, onAdd: () -> Unit, onEdit: () -> Unit, onNotACard: () -> Unit, onRemove: () -> Unit) {
    SpendsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                CardChip(candidate.colorHex, "CR")
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f).padding(top = 6.dp)) {
                    Text(
                        candidate.label + (candidate.last4?.let { " ·$it" } ?: ""),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("Found in your SMS", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // X = remove for now; a later scan can surface it again (distinct from "Not a card").
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove for now", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onAdd) { Text("Add card") }
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onNotACard) { Text("Not a card") }
            }
        }
    }
}

/** A dismissed instrument with a Restore action (#14 — undo an accidental "Not a card"). */
@Composable
private fun DismissedRow(candidate: CandidateUi, onRestore: () -> Unit) {
    SpendsCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardChip(candidate.colorHex, "CR")
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    candidate.label + (candidate.last4?.let { " ·$it" } ?: ""),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Marked \"Not a card\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRestore) { Text("Restore") }
        }
    }
}

@Composable
private fun EmptyHint(title: String, body: String) {
    SpendsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Multi-select cards + one billing day → apply to all (#10, replaces per-card Merge). */
@Composable
private fun CommonBillingDialog(cards: List<CardUi>, onApply: (Set<Long>, Int) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var day by remember { mutableStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Common billing day") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Pick the cards that share a statement day, then choose the day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                cards.forEach { card ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selected = if (card.id in selected) selected - card.id else selected + card.id
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = card.id in selected, onCheckedChange = {
                            selected = if (card.id in selected) selected - card.id else selected + card.id
                        })
                        Spacer(Modifier.size(4.dp))
                        Text(card.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(8.dp))
                NumberWheelPicker(value = day, range = 1..31, onValueChange = { day = it }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty(), onClick = { onApply(selected, day) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
