package com.spends.app.ui.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.money.Money
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.core.theme.Numerals
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
import com.spends.app.ui.components.CategoryAvatar
import com.spends.app.ui.components.CategoryPickerSheet
import com.spends.app.ui.components.PeriodSelectorBar
import kotlinx.coroutines.launch

@Composable
fun TransactionsScreen(
    snackbarHostState: SnackbarHostState,
    onEditTransaction: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selection by viewModel.periodSelection.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Search text is owned locally (synchronous snapshot state) so the cursor never jumps; the
    // ViewModel is fed for filtering only.
    var searchText by rememberSaveable { mutableStateOf("") }

    val listState = rememberLazyListState()

    // The summary header lives INSIDE the LazyColumn (as item 0) so it scrolls away naturally. The old
    // approach toggled a header/compact-bar with AnimatedVisibility above the list, which changed the
    // viewport height mid-scroll and shoved a row back down on every drag (the #15 glitch). It's only
    // hidden while searching so results start at the very top.
    val searching = state.search.isNotBlank()
    // Whenever the (debounced) result set changes, snap back to the top so the freshly filtered list
    // starts fully visible instead of stranded at a stale scroll offset.
    LaunchedEffect(state.search) { listState.scrollToItem(0) }

    var pendingDelete by remember { mutableStateOf<TransactionRowUi?>(null) }
    var changeCategoryFor by remember { mutableStateOf<TransactionRowUi?>(null) }

    // Multi-select (#9): long-press a row to start; tap toggles; bulk delete / change category.
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val selectionMode = selectedIds.isNotEmpty()
    var showBulkDelete by remember { mutableStateOf(false) }
    var showBulkCategory by remember { mutableStateOf(false) }
    fun toggle(id: Long) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }

    Column(modifier = modifier.fillMaxSize()) {
        if (selectionMode) {
            SelectionBar(
                count = selectedIds.size,
                onClear = { selectedIds = emptySet() },
                onChangeCategory = { showBulkCategory = true },
                onDelete = { showBulkDelete = true },
            )
        } else {
            // Period selector — always visible so the cycle/range can be changed any time.
            PeriodSelectorBar(
                selection = selection,
                label = state.periodLabel,
                onSelect = viewModel::applySelection,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        TextField(
            value = searchText,
            onValueChange = { searchText = it; viewModel.setSearch(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            placeholder = { Text("Search transactions") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        when {
            state.loading -> Unit
            state.isEmpty -> EmptyTimeline(searching = state.search.isNotBlank())
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                if (!searching) {
                    item(key = "summary") {
                        SummaryHeader(state = state, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                state.groups.forEach { group ->
                    item(key = "header-${group.date}") {
                        DayHeader(group)
                    }
                    items(group.rows, key = { it.id }) { row ->
                        SwipeableRow(
                            row = row,
                            selectionMode = selectionMode,
                            selected = row.id in selectedIds,
                            onClick = { if (selectionMode) toggle(row.id) else onEditTransaction(row.id) },
                            onLongClick = { if (selectionMode) toggle(row.id) else selectedIds = setOf(row.id) },
                            onRequestDelete = { pendingDelete = row },
                            onRequestChangeCategory = { changeCategoryFor = row },
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation (swipe is easy to trigger, so always confirm).
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this transaction?") },
            text = { Text("\"${row.title}\" moves to Trash — you can restore it from Settings → Trash.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.moveToTrash(row.id)
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "Moved to Trash",
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.restore(row.id)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    // Right-swipe → change category.
    changeCategoryFor?.let { row ->
        val usageFilter = if (row.kind == TxnKind.INCOME) CategoryUsage.INCOME else CategoryUsage.EXPENSE
        val visible = categories.filter { it.usage == usageFilter || it.usage == CategoryUsage.BOTH }
        CategoryPickerSheet(
            categories = visible,
            selectedId = row.primary?.categoryId,
            onSelect = { id -> viewModel.changeCategory(row.id, id); changeCategoryFor = null },
            onDismiss = { changeCategoryFor = null },
        )
    }

    // Bulk delete (multi-select).
    if (showBulkDelete) {
        val ids = selectedIds
        AlertDialog(
            onDismissRequest = { showBulkDelete = false },
            title = { Text("Delete ${ids.size} transactions?") },
            text = { Text("They move to Trash — you can restore them from Settings → Trash.") },
            confirmButton = {
                TextButton(onClick = {
                    showBulkDelete = false
                    selectedIds = emptySet()
                    viewModel.bulkMoveToTrash(ids)
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "Moved ${ids.size} to Trash",
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.bulkRestore(ids)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showBulkDelete = false }) { Text("Cancel") } },
        )
    }

    // Bulk change-category (multi-select). Shows all active categories since the selection may be mixed.
    if (showBulkCategory) {
        val ids = selectedIds
        CategoryPickerSheet(
            categories = categories,
            selectedId = null,
            onSelect = { id ->
                showBulkCategory = false
                selectedIds = emptySet()
                viewModel.bulkChangeCategory(ids, id)
            },
            onDismiss = { showBulkCategory = false },
        )
    }
}

@Composable
private fun SelectionBar(count: Int, onClear: () -> Unit, onChangeCategory: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Close, contentDescription = "Clear selection", tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(
            "$count selected",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onChangeCategory) {
            Icon(Icons.Filled.Category, contentDescription = "Change category", tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun DayHeader(group: DayGroupUi) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.headerLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val color = if (group.netSubtotal < 0) semantic.expense else if (group.netSubtotal > 0) semantic.income else MaterialTheme.colorScheme.onSurfaceVariant
        val sign = if (group.netSubtotal > 0) "+" else if (group.netSubtotal < 0) "-" else ""
        Text(
            text = sign + Money.formatRupees(kotlin.math.abs(group.netSubtotal)),
            style = Numerals.amountSmall,
            color = color,
        )
    }
}

@Composable
private fun SwipeableRow(
    row: TransactionRowUi,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRequestDelete: () -> Unit,
    onRequestChangeCategory: () -> Unit,
) {
    // In selection mode the swipe gestures are disabled — tap toggles, long-press already active.
    if (selectionMode) {
        TransactionRow(row = row, selected = selected, onClick = onClick, onLongClick = onLongClick)
        return
    }
    val dismissState = rememberSwipeToDismissBoxState(
        // Both directions only *request* an action and snap back (return false) — the actual delete
        // is gated behind a confirmation dialog, and right-swipe opens the category picker.
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { onRequestDelete(); false }
                SwipeToDismissBoxValue.StartToEnd -> { onRequestChangeCategory(); false }
                else -> false
            }
        },
        // Require a deliberate, near-full swipe (70% of the row) before the action triggers. A stray
        // horizontal nudge while the user is trying to scroll vertically falls short and snaps back.
        positionalThreshold = { totalDistance -> totalDistance * 0.7f },
    )
    val semantic = LocalSemanticColors.current
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.EndToStart -> SwipeBg(
                    color = semantic.expense.copy(alpha = 0.18f),
                    icon = Icons.Filled.Delete,
                    tint = semantic.expense,
                    alignEnd = true,
                    label = "Delete",
                )
                SwipeToDismissBoxValue.StartToEnd -> SwipeBg(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    icon = Icons.Filled.SwapHoriz,
                    tint = MaterialTheme.colorScheme.primary,
                    alignEnd = false,
                    label = "Category",
                )
                else -> Box(Modifier.fillMaxSize())
            }
        },
    ) {
        TransactionRow(row = row, selected = false, onClick = onClick, onLongClick = onLongClick)
    }
}

@Composable
private fun SwipeBg(color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, alignEnd: Boolean, label: String) {
    Row(
        modifier = Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        if (!alignEnd) {
            Icon(icon, contentDescription = label, tint = tint)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
        } else {
            Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
            Spacer(Modifier.width(8.dp))
            Icon(icon, contentDescription = label, tint = tint)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionRow(row: TransactionRowUi, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    val rowBg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(
            iconKey = row.primary?.iconKey ?: "tag",
            colorHex = row.primary?.colorHex ?: "#78716C",
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Merchant + note share one line (note muted); only wraps to a 2nd line when too long —
            // no dedicated note row, so the card stays compact.
            val note = row.note?.takeIf { it.isNotBlank() && it != row.title }
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                text = buildAnnotatedString {
                    append(row.title)
                    if (note != null) withStyle(SpanStyle(color = muted)) { append("  ·  $note") }
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                append(row.primary?.name ?: "Uncategorized")
                if (row.isSplit) append(" +${row.categories.size - 1}")
                // Time is omitted for rows with a synthetic timestamp (e.g. recurring).
                row.timeLabel?.let { append(" · "); append(it) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                sourceIcon(row.source)?.let { glyph ->
                    Icon(
                        glyph,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp).padding(end = 3.dp),
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        val amountColor = when (row.kind) {
            TxnKind.INCOME -> semantic.income
            TxnKind.EXPENSE -> semantic.expense
            TxnKind.TRANSFER -> semantic.transfer
        }
        val prefix = when (row.kind) {
            TxnKind.INCOME -> "+"
            TxnKind.EXPENSE -> "-"
            TxnKind.TRANSFER -> ""
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = prefix + Money.formatRupees(row.amountMinor),
                style = Numerals.amountRow,
                color = amountColor,
            )
            Text(
                text = row.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                color = amountColor.copy(alpha = 0.75f),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** Small glyph showing where a transaction came from (manual entries get none). */
private fun sourceIcon(source: TxnSource): androidx.compose.ui.graphics.vector.ImageVector? = when (source) {
    TxnSource.SMS -> Icons.Filled.Sms
    TxnSource.NOTIFICATION -> Icons.Filled.Notifications
    TxnSource.RECURRING -> Icons.Filled.Autorenew
    TxnSource.IMPORT -> Icons.Filled.UploadFile
    TxnSource.MANUAL -> Icons.Filled.Edit
}

@Composable
private fun EmptyTimeline(searching: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(
                text = if (searching) "No matches" else "No transactions yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = if (searching) "Try a different search." else "Tap + to add your first one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
