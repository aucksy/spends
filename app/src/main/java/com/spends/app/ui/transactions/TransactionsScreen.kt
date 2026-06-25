package com.spends.app.ui.transactions

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.money.Money
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.core.theme.Numerals
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
import com.spends.app.ui.components.AutoSizeRupee
import com.spends.app.ui.components.CategoryAvatar
import com.spends.app.ui.components.CategoryPickerSheet
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
    val scope = rememberCoroutineScope()
    // Search text is owned locally (synchronous snapshot state) so the cursor never jumps; the
    // ViewModel is fed for filtering only.
    var searchText by rememberSaveable { mutableStateOf("") }

    val listState = rememberLazyListState()
    // Collapse once the first list item has scrolled off the top. Keyed on firstVisibleItemIndex
    // (not canScrollBackward) so that removing the header — which grows the viewport — can't flip the
    // trigger back and cause the header to oscillate/pop at the boundary.
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    // While searching, suppress BOTH header states so the summary/compact bars never toggle height
    // mid-scroll — that toggle was fighting the user's scroll and hiding the last result off-screen.
    val searching = state.search.isNotBlank()
    // Whenever the (debounced) result set changes, snap back to the top so the freshly filtered list
    // starts fully visible instead of stranded at a stale scroll offset.
    LaunchedEffect(state.search) { listState.scrollToItem(0) }

    var pendingDelete by remember { mutableStateOf<TransactionRowUi?>(null) }
    var changeCategoryFor by remember { mutableStateOf<TransactionRowUi?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(visible = !collapsed && !searching) {
            SummaryHeader(
                state = state,
                onPrevious = viewModel::stepPrevious,
                onNext = viewModel::stepNext,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        AnimatedVisibility(visible = collapsed && !searching) {
            CompactBalanceBar(state)
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
                state.groups.forEach { group ->
                    item(key = "header-${group.date}") {
                        DayHeader(group)
                    }
                    items(group.rows, key = { it.id }) { row ->
                        SwipeableRow(
                            row = row,
                            onClick = { onEditTransaction(row.id) },
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
                        val result = snackbarHostState.showSnackbar(message = "Moved to Trash", actionLabel = "Undo")
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
}

@Composable
private fun CompactBalanceBar(state: TransactionsUiState) {
    val semantic = LocalSemanticColors.current
    val balance = if (state.carryForward != null) (state.balanceWithCarry ?: 0) else state.totals.balance
    val negative = balance < 0
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (negative) semantic.negativeContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "BALANCE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AutoSizeRupee(
                minor = balance,
                style = Numerals.amountLg,
                color = if (negative) semantic.negative else MaterialTheme.colorScheme.onSurface,
                withSign = true,
                modifier = Modifier.weight(1f).padding(start = 16.dp),
            )
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
    onClick: () -> Unit,
    onRequestDelete: () -> Unit,
    onRequestChangeCategory: () -> Unit,
) {
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
        TransactionRow(row = row, onClick = onClick)
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

@Composable
private fun TransactionRow(row: TransactionRowUi, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(
            iconKey = row.primary?.iconKey ?: "tag",
            colorHex = row.primary?.colorHex ?: "#78716C",
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                append(row.primary?.name ?: "Uncategorized")
                if (row.isSplit) append(" +${row.categories.size - 1}")
                append(" · ")
                append(row.timeLabel)
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
            // Show the note when present and not already used as the row title.
            val note = row.note?.takeIf { it.isNotBlank() && it != row.title }
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
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
