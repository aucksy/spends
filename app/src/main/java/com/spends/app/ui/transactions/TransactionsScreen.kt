package com.spends.app.ui.transactions

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.components.CategoryAvatar
import kotlinx.coroutines.launch

@Composable
fun TransactionsScreen(
    snackbarHostState: SnackbarHostState,
    onEditTransaction: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        SummaryHeader(
            state = state,
            onPrevious = viewModel::stepPrevious,
            onNext = viewModel::stepNext,
            modifier = Modifier.padding(top = 4.dp),
        )

        TextField(
            value = state.search,
            onValueChange = viewModel::setSearch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
                            onDelete = {
                                viewModel.moveToTrash(row.id)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Moved to Trash",
                                        actionLabel = "Undo",
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.restore(row.id)
                                    }
                                }
                            },
                        )
                    }
                }
            }
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
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
private fun SwipeableRow(
    row: TransactionRowUi,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete(); true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LocalSemanticColors.current.expense.copy(alpha = 0.18f))
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = LocalSemanticColors.current.expense,
                )
            }
        },
    ) {
        TransactionRow(row = row, onClick = onClick)
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
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        Text(
            text = prefix + Money.formatRupees(row.amountMinor),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = amountColor,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
