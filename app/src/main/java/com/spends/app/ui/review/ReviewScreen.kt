package com.spends.app.ui.review

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.spends.app.core.time.DateUtils
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.components.CategoryAvatar
import com.spends.app.ui.components.SpendsCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    onEditPending: (Long) -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    var smsFor by remember { mutableStateOf<ReviewRowUi?>(null) }
    var rejectFor by remember { mutableStateOf<ReviewRowUi?>(null) }
    var showConfirmAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review detected") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // "Add all" adds the whole queue, so hide it while searching (a filtered subset).
                    if (pendingCount > 0 && query.isBlank()) {
                        TextButton(onClick = { showConfirmAll = true }) { Text("Add all ($pendingCount)") }
                    }
                },
            )
        },
    ) { padding ->
        if (pendingCount == 0) {
            EmptyState(
                modifier = Modifier.fillMaxSize().padding(padding),
                icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(48.dp), tint = LocalSemanticColors.current.income) },
                title = "All caught up",
                body = "Detected transactions that need a category or a second look appear here.",
            )
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                )
                // #7: filter the queue to only Income or only Expense (independent of the search box).
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    val opts = listOf(ReviewFilter.ALL to "All", ReviewFilter.EXPENSE to "Expense", ReviewFilter.INCOME to "Income")
                    opts.forEachIndexed { i, (f, label) ->
                        SegmentedButton(
                            selected = filter == f,
                            onClick = { viewModel.setFilter(f) },
                            shape = SegmentedButtonDefaults.itemShape(i, opts.size),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (items.isEmpty() && (query.isNotBlank() || filter != ReviewFilter.ALL)) {
                    EmptyState(
                        modifier = Modifier.fillMaxSize(),
                        icon = { Icon(Icons.Filled.SearchOff, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        title = "No matches",
                        body = when {
                            query.isNotBlank() -> "Nothing in the review queue matches “${query.trim()}”."
                            filter == ReviewFilter.EXPENSE -> "No expenses in the review queue right now."
                            filter == ReviewFilter.INCOME -> "No income in the review queue right now."
                            else -> "Nothing here."
                        },
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(items, key = { it.id }) { row ->
                            ReviewCard(
                                row = row,
                                onEdit = { onEditPending(row.id) },
                                onViewSms = { smsFor = row },
                                onReject = { rejectFor = row },
                            )
                        }
                    }
                }
            }
        }
    }

    smsFor?.let { row -> SmsDetailSheet(row = row, onDismiss = { smsFor = null }) }

    // Confirm before the X discards a scanned SMS from the review queue (#9).
    rejectFor?.let { row ->
        AlertDialog(
            onDismissRequest = { rejectFor = null },
            title = { Text("Remove this from review?") },
            text = { Text("It won't be added — this just discards the detected SMS from the review queue.") },
            confirmButton = {
                TextButton(onClick = { viewModel.reject(row.id); rejectFor = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { rejectFor = null }) { Text("Cancel") } },
        )
    }

    if (showConfirmAll) {
        AlertDialog(
            onDismissRequest = { showConfirmAll = false },
            title = { Text("Add all $pendingCount?") },
            text = { Text("Adds every detected transaction with its guessed category. You can edit any of them afterwards in the timeline.") },
            confirmButton = {
                TextButton(onClick = { showConfirmAll = false; viewModel.confirmAll() }) { Text("Add all") }
            },
            dismissButton = { TextButton(onClick = { showConfirmAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier, icon: @Composable () -> Unit, title: String, body: String) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            icon()
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReviewCard(
    row: ReviewRowUi,
    onEdit: () -> Unit,
    onViewSms: () -> Unit,
    onReject: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    // Tapping the card opens the full editor prefilled — same as "Review and Add" (#9).
    SpendsCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (row.sourceAppName != null) "DETECTED FROM NOTIFICATION" else "DETECTED FROM SMS",
                    style = MaterialTheme.typography.labelMedium,
                    color = semantic.review,
                    modifier = Modifier.weight(1f),
                )
                // #10: only when we actually kept the source text (rows scanned before DB v8 have none).
                if (!row.rawBody.isNullOrBlank()) {
                    Text(
                        if (row.sourceAppName != null) "View text" else "View SMS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onViewSms)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                IconButton(onClick = onReject, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Reject", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            val color = if (row.kind == TxnKind.INCOME) semantic.income else semantic.expense
            val prefix = when (row.kind) { TxnKind.INCOME -> "+"; TxnKind.EXPENSE -> "-" }
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
            // Single primary action (#6) — the category is changed inside the editor it opens, so there's
            // no separate "Change category" button. It opens the editor; it does NOT add on its own.
            Button(onClick = onEdit, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Review and Add")
            }
        }
    }
}

/** #10: shows the original captured SMS/notification (sender + time + body) in a theme-matching sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmsDetailSheet(row: ReviewRowUi, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(
                if (row.sourceAppName != null) "Original notification" else "Original SMS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            val subtitle = listOfNotNull(
                row.sourceAppName?.let { "via $it" },
                row.sender?.takeIf { it.isNotBlank() },
                "${DateUtils.formatDay(row.receivedAt)} · ${DateUtils.formatTime(row.receivedAt)}",
            ).joinToString(" · ")
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalSemanticColors.current.review)
            Spacer(Modifier.height(14.dp))
            SpendsCard(modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        row.rawBody ?: "The original text isn't available for this one.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
