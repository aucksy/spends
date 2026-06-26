package com.spends.app.ui.review

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.money.Money
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.core.theme.Numerals
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.components.CategoryAvatar
import com.spends.app.ui.components.CategoryPickerSheet
import com.spends.app.ui.components.SpendsCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    onEditPending: (Long) -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var pickFor by remember { mutableStateOf<ReviewRowUi?>(null) }
    var showConfirmAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review captured") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = { showConfirmAll = true }) { Text("Add all (${items.size})") }
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(48.dp), tint = LocalSemanticColors.current.income)
                    Spacer(Modifier.height(12.dp))
                    Text("All caught up", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Captured transactions that need a category or a second look appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { row ->
                    ReviewCard(
                        row = row,
                        onChangeCategory = { pickFor = row },
                        onEdit = { onEditPending(row.id) },
                        onReject = { viewModel.reject(row.id) },
                    )
                }
            }
        }
    }

    pickFor?.let { row ->
        val usage = if (row.kind == TxnKind.INCOME) CategoryUsage.INCOME else CategoryUsage.EXPENSE
        val visible = categories.filter { it.usage == usage || it.usage == CategoryUsage.BOTH }
        CategoryPickerSheet(
            categories = visible,
            selectedId = row.categoryId,
            // Just re-tag the queued row — does NOT add it to the ledger (#9).
            onSelect = { id -> viewModel.changeCategory(row.id, id); pickFor = null },
            onDismiss = { pickFor = null },
        )
    }

    if (showConfirmAll) {
        AlertDialog(
            onDismissRequest = { showConfirmAll = false },
            title = { Text("Add all ${items.size}?") },
            text = { Text("Adds every captured transaction with its guessed category. You can edit any of them afterwards in the timeline.") },
            confirmButton = {
                TextButton(onClick = { showConfirmAll = false; viewModel.confirmAll() }) { Text("Add all") }
            },
            dismissButton = { TextButton(onClick = { showConfirmAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ReviewCard(row: ReviewRowUi, onChangeCategory: () -> Unit, onEdit: () -> Unit, onReject: () -> Unit) {
    val semantic = LocalSemanticColors.current
    // Tapping the card opens the full editor prefilled — same as "Review and Add" (#9).
    SpendsCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CAPTURED FROM SMS", style = MaterialTheme.typography.labelMedium, color = semantic.review, modifier = Modifier.weight(1f))
                IconButton(onClick = onReject, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Reject", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            val color = if (row.kind == TxnKind.INCOME) semantic.income else semantic.expense
            val prefix = when (row.kind) { TxnKind.INCOME -> "+"; TxnKind.EXPENSE -> "-"; TxnKind.TRANSFER -> "" }
            Text(prefix + Money.formatRupees(row.amountMinor), style = Numerals.amountLg, color = color)
            Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (row.subtitle.isNotBlank()) {
                Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryAvatar(row.iconKey ?: "tag", row.colorHex ?: "#78716C", size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Text(row.categoryName ?: "Uncategorized", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            // Equal-height buttons (48dp) + single line so "Change category" can't wrap taller than the
            // primary action (#9). "Review and Add" opens the editor; it does NOT add on its own.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onChangeCategory, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("Change category", maxLines = 1)
                }
                Button(onClick = onEdit, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("Review and Add", maxLines = 1)
                }
            }
        }
    }
}
