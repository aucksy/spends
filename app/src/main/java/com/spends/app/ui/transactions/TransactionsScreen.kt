package com.spends.app.ui.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.view.HapticFeedbackConstants
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun TransactionsScreen(
    snackbarHostState: SnackbarHostState,
    onEditTransaction: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    searchActive: Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
    searchInTopBar: Boolean = false,
    onOpenBreakdown: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selection by viewModel.periodSelection.collectAsStateWithLifecycle()
    val smartCycleEnabled by viewModel.smartCycleEnabled.collectAsStateWithLifecycle()
    val cardChoices by viewModel.cardChoices.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    // Search text is owned locally (synchronous snapshot state) so the cursor never jumps; the
    // ViewModel is fed for filtering only.
    var searchText by rememberSaveable { mutableStateOf("") }
    // Search visibility is owned by HomeScreen's bottom "Search" tab now (#5); the query text stays local
    // here so the cursor never jumps. ALWAYS reconcile the VM when search is inactive — even after a
    // cross-tab remount where the local searchText has reset to "" — otherwise a stale VM query would keep
    // filtering the timeline with no visible field to clear it (e.g. search → Analytics → Transactions).
    LaunchedEffect(searchActive) {
        if (!searchActive) { searchText = ""; viewModel.setSearch("") }
    }

    val listState = rememberLazyListState()

    // The summary header lives INSIDE the LazyColumn (as item 0) so it scrolls away naturally. The old
    // approach toggled a header/compact-bar with AnimatedVisibility above the list, which changed the
    // viewport height mid-scroll and shoved a row back down on every drag (the #15 glitch). It's only
    // hidden while searching so results start at the very top.
    val searching = state.search.isNotBlank()
    // Snap to top ONLY when the search query actually CHANGES — not on every composition entry. A plain
    // LaunchedEffect(state.search) also re-runs when the screen re-enters composition (e.g. returning from
    // the editor), which was resetting the scroll position to the top (#3). Guarding on the previous value
    // keeps the scroll where it was on return, while still jumping to the top on a real new search.
    var lastSearch by remember { mutableStateOf(state.search) }
    LaunchedEffect(state.search) {
        if (state.search != lastSearch) {
            lastSearch = state.search
            listState.scrollToItem(0)
        }
    }

    var pendingDelete by remember { mutableStateOf<TransactionRowUi?>(null) }
    var changeCategoryFor by remember { mutableStateOf<TransactionRowUi?>(null) }

    // Multi-select (#9): long-press a row to start; tap toggles; bulk delete / change category.
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val selectionMode = selectedIds.isNotEmpty()
    var showBulkDelete by remember { mutableStateOf(false) }
    var showBulkCategory by remember { mutableStateOf(false) }
    // Haptic on EVERY multi-select toggle (select AND deselect). KEYBOARD_TAP (the keypad's tick) is
    // reliably felt across devices, unlike CONTEXT_CLICK which is often suppressed — so it no longer
    // feels like only the first selection buzzes (#17).
    fun toggle(id: Long) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    fun startSelection(id: Long) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        selectedIds = setOf(id)
    }

    fun closeSearch() {
        searchText = ""
        viewModel.setSearch("")
        onSearchActiveChange(false)
    }

    // Back exits a transient mode first (industry standard) instead of closing the app (#6): clear a
    // multi-selection, else close an open search. At most one is active at a time.
    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }
    BackHandler(enabled = searchActive && !selectionMode) { closeSearch() }

    val showScrollTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 25 } }

    Box(modifier = modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        if (selectionMode) {
            SelectionBar(
                count = selectedIds.size,
                onClear = { selectedIds = emptySet() },
                onChangeCategory = { showBulkCategory = true },
                onDelete = { showBulkDelete = true },
            )
        } else {
            // Period selector — always visible so the cycle/range can be changed any time. Hosts the
            // Settings gear AND the search toggle now that the title/search bars are gone (#7/#16). Inset
            // 16.dp so its edges sit flush with the stat tiles + hero below it (#16).
            PeriodSelectorBar(
                selection = selection,
                label = state.periodLabel,
                onSelect = viewModel::applySelection,
                onOpenSettings = onOpenSettings,
                // With Smart Cycle ON, the bottom "Search" tab is replaced by a Cards tab, so search returns
                // to a compact icon here (the cycle pill keeps full width when it's off, as before — #5).
                onToggleSearch = if (searchInTopBar) ({ onSearchActiveChange(!searchActive) }) else null,
                searchActive = searchActive,
                smartCycleEnabled = smartCycleEnabled,
                cards = cardChoices,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        // Search field appears only when toggled on (#16), and autofocuses so the keyboard is ready.
        if (searchActive && !selectionMode) {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                // The field is rememberSaveable but the VM's query isn't — re-apply a restored search so
                // the list matches what the box shows after process death, then focus.
                if (searchText.isNotEmpty()) viewModel.setSearch(searchText)
                runCatching { focusRequester.requestFocus() }
            }
            TextField(
                value = searchText,
                onValueChange = { searchText = it; viewModel.setSearch(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text("Search transactions") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }

        when {
            // A themed spinner (not a blank frame) while the first data loads — e.g. right after a
            // restore lands on Home, so it doesn't flash empty then pop in (#5).
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.isEmpty -> EmptyTimeline(searching = state.search.isNotBlank())
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                if (!searching) {
                    item(key = "summary") {
                        SummaryHeader(state = state, onOpenBreakdown = onOpenBreakdown, modifier = Modifier.padding(top = 2.dp))
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
                            onLongClick = { if (selectionMode) toggle(row.id) else startSelection(row.id) },
                            onRequestDelete = { pendingDelete = row },
                            onRequestChangeCategory = { changeCategoryFor = row },
                        )
                    }
                }
            }
        }
      }

      // Scroll-to-top — shows after ~25 rows; floats just above the + add FAB, hidden in selection mode (#15).
      AnimatedVisibility(
          visible = showScrollTop && !selectionMode,
          enter = fadeIn() + scaleIn(),
          exit = fadeOut() + scaleOut(),
          modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 88.dp),
      ) {
          SmallFloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }) {
              Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Scroll to top")
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
        // Per-day Expense + Income shown separately (#1) instead of a single net figure. Only non-zero
        // sides appear; a transfers-only / empty day shows a muted ₹0.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (group.expenseSubtotal > 0) {
                Text(
                    text = "−" + Money.formatRupees(group.expenseSubtotal),
                    style = Numerals.amountSmall,
                    color = semantic.expense,
                )
            }
            if (group.incomeSubtotal > 0) {
                Text(
                    text = "+" + Money.formatRupees(group.incomeSubtotal),
                    style = Numerals.amountSmall,
                    color = semantic.income,
                )
            }
            if (group.expenseSubtotal == 0L && group.incomeSubtotal == 0L) {
                Text(
                    text = Money.formatRupees(0),
                    style = Numerals.amountSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
    val view = LocalView.current
    val semantic = LocalSemanticColors.current
    val density = LocalDensity.current
    val maxPx = with(density) { 120.dp.toPx() }
    val triggerPx = with(density) { 96.dp.toPx() }
    // Live offset is a plain float mutated synchronously during the drag; ONLY the snap-back animates,
    // so a single owner ever touches the Animatable — no cross-scope race / stuck partially-swiped row.
    var offsetX by remember { mutableStateOf(0f) }
    val anim = remember { Animatable(0f) }
    // Recomposes only when the swipe crosses 0 (not every drag frame).
    val direction by remember { derivedStateOf { offsetX.compareTo(0f) } }

    Box {
        Box(Modifier.matchParentSize()) {
            when {
                direction < 0 -> SwipeBg(
                    color = semantic.expense.copy(alpha = 0.18f),
                    icon = Icons.Filled.Delete,
                    tint = semantic.expense,
                    alignEnd = true,
                    label = "Delete",
                )
                direction > 0 -> SwipeBg(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    icon = Icons.Filled.SwapHoriz,
                    tint = MaterialTheme.colorScheme.primary,
                    alignEnd = false,
                    label = "Category",
                )
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                // Modifier.draggable(Horizontal) AXIS-LOCKS: a vertical scroll is consumed by the
                // LazyColumn and never engages the swipe, so accidental swipes while scrolling stop (#14).
                // Each direction only REQUESTS an action on release past a deliberate distance (delete is
                // still behind a confirm dialog), then the row snaps back — never an auto-dismiss.
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta -> offsetX = (offsetX + delta).coerceIn(-maxPx, maxPx) },
                    onDragStopped = {
                        when {
                            offsetX <= -triggerPx -> {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onRequestDelete()
                            }
                            offsetX >= triggerPx -> {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onRequestChangeCategory()
                            }
                        }
                        anim.snapTo(offsetX)
                        anim.animateTo(0f) { offsetX = value }
                    },
                ),
        ) {
            TransactionRow(row = row, selected = false, onClick = onClick, onLongClick = onLongClick)
        }
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
            // Line 1 (#2): the CATEGORY name (title) + an optional note (muted). Merchant is no longer shown
            // here — it lives in the editor; tap the row to see/edit it.
            val note = row.note?.takeIf { it.isNotBlank() && it != row.title }
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                text = buildAnnotatedString {
                    append(row.title)
                    if (note != null) withStyle(SpanStyle(color = muted)) { append("  $note") }
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Line 2: "Total: <category's total for the cycle>  <time>" — the category name is already the
            // title on line 1, so it's dropped here (just "Total").
            val subtitle = buildString {
                append("Total: ")
                append(Money.formatRupees(row.categoryTotalMinor))
                row.timeLabel?.let { append("  "); append(it) }
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
