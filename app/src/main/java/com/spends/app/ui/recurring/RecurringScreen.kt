package com.spends.app.ui.recurring

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.money.Money
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.core.time.DateUtils
import com.spends.app.core.time.RecurrenceMath
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.RecurringRuleEntity
import com.spends.app.data.repo.RecurringInput
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.RecurrenceFreq
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.components.AmountKeypadSheet
import com.spends.app.ui.components.CategoryAvatar
import com.spends.app.ui.components.CategoryPickerField
import com.spends.app.ui.components.CategoryPickerSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringScreen(
    onBack: () -> Unit,
    viewModel: RecurringViewModel = hiltViewModel(),
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()

    // null = list view; non-null = editor. Editing(null rule) is "add new".
    var editing by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<RecurringRuleEntity?>(null) }
    var sortByDate by rememberSaveable { mutableStateOf(true) }

    if (editing) {
        RecurringEditor(
            initial = editTarget,
            categories = categories,
            onCancel = { editing = false },
            onDelete = editTarget?.let { target -> { viewModel.delete(target.id); editing = false } },
            onSave = { input, applyToPast -> viewModel.save(input, editTarget?.id, applyToPast); editing = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recurring") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editTarget = null; editing = true },
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add recurring")
            }
        },
    ) { padding ->
        if (rules.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            val byId = categories.associateBy { it.id }
            val sorted = remember(rules, categories, sortByDate) {
                if (sortByDate) {
                    rules.sortedWith(compareByDescending<RecurringRuleEntity> { it.active }.thenBy { it.nextRunAt })
                } else {
                    rules.sortedBy {
                        (it.merchant?.takeIf { m -> m.isNotBlank() } ?: byId[it.categoryId]?.name ?: "Recurring").lowercase()
                    }
                }
            }
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                SortToggle(sortByDate = sortByDate, onChange = { sortByDate = it })
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sorted, key = { it.id }) { rule ->
                        RecurringRow(
                            rule = rule,
                            category = byId[rule.categoryId],
                            onClick = { editTarget = rule; editing = true },
                            onToggle = { viewModel.setActive(rule.id, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SortToggle(sortByDate: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sort", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = sortByDate,
                onClick = { onChange(true) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("By date") }
            SegmentedButton(
                selected = !sortByDate,
                onClick = { onChange(false) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("A–Z") }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("No recurring transactions yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add rent, salary, EMIs or subscriptions and Spends will log them automatically on schedule.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RecurringRow(
    rule: RecurringRuleEntity,
    category: CategoryEntity?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(
            iconKey = category?.iconKey ?: "tag",
            colorHex = category?.colorHex ?: "#78716C",
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rule.merchant?.takeIf { it.isNotBlank() } ?: category?.name ?: "Recurring",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = RecurrenceMath.describe(rule.frequency, rule.intervalCount, rule.anchorDay) +
                    " · next " + DateUtils.formatDay(rule.nextRunAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            val color = if (rule.kind == TxnKind.INCOME) semantic.income else semantic.expense
            val prefix = if (rule.kind == TxnKind.INCOME) "+" else "-"
            Text(
                text = prefix + Money.formatRupees(rule.amountMinor),
                style = com.spends.app.core.theme.Numerals.amountRow,
                color = if (rule.active) color else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(checked = rule.active, onCheckedChange = onToggle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurringEditor(
    initial: RecurringRuleEntity?,
    categories: List<CategoryEntity>,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (RecurringInput, Boolean) -> Unit,
) {
    var amountMinor by remember { mutableStateOf(initial?.amountMinor?.takeIf { it > 0 }) }
    var kind by remember { mutableStateOf(initial?.kind ?: TxnKind.EXPENSE) }
    var categoryId by remember { mutableStateOf(initial?.categoryId) }
    var frequency by remember { mutableStateOf(initial?.frequency ?: RecurrenceFreq.MONTHLY) }
    var intervalCount by remember { mutableStateOf(initial?.intervalCount ?: 1) }
    var startDate by remember { mutableStateOf(initial?.startDate ?: DateUtils.nowMillis()) }
    var merchant by remember { mutableStateOf(initial?.merchant ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    // #8 "repeat N times": occurrenceLimit 0 = forever; >0 = stop after N.
    var limited by remember { mutableStateOf((initial?.occurrenceLimit ?: 0) > 0) }
    var occurrenceCount by remember { mutableStateOf((initial?.occurrenceLimit ?: 0).takeIf { it > 0 } ?: 12) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showAddBlocked by remember { mutableStateOf(false) }
    var showAmountKeypad by remember { mutableStateOf(false) }
    // #5 edit scope: when editing an existing rule, ask whether the change also rewrites past occurrences.
    var pendingInput by remember { mutableStateOf<RecurringInput?>(null) }

    val usageFilter = if (kind == TxnKind.INCOME) CategoryUsage.INCOME else CategoryUsage.EXPENSE
    val visibleCategories = categories.filter { it.usage == usageFilter || it.usage == CategoryUsage.BOTH }
    val selectedCategory = visibleCategories.firstOrNull { it.id == categoryId }

    val semantic = LocalSemanticColors.current
    val amountAccent = if (kind == TxnKind.INCOME) semantic.income else semantic.expense
    val canSave = amountMinor != null && categoryId != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "New recurring" else "Edit recurring") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                // imePadding before verticalScroll so focused fields scroll above the keyboard.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = kind == TxnKind.EXPENSE,
                    onClick = { kind = TxnKind.EXPENSE; categoryId = null },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Expense") }
                SegmentedButton(
                    selected = kind == TxnKind.INCOME,
                    onClick = { kind = TxnKind.INCOME; categoryId = null },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Income") }
            }

            Spacer(Modifier.height(16.dp))
            Text("Amount", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            // Same calculator keypad as quick-add (#12): tap to open it.
            Surface(
                onClick = { showAmountKeypad = true },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("₹", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = amountMinor?.let { Money.formatRupees(it, withSymbol = false) } ?: "Tap to enter amount",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (amountMinor != null) amountAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Category", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            CategoryPickerField(
                selected = selectedCategory,
                placeholder = "Pick a category",
                onClick = { showCategoryPicker = true },
            )

            Spacer(Modifier.height(16.dp))
            Text("Repeats", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val freqs = RecurrenceFreq.entries
                freqs.forEachIndexed { index, f ->
                    SegmentedButton(
                        selected = frequency == f,
                        onClick = { frequency = f },
                        shape = SegmentedButtonDefaults.itemShape(index, freqs.size),
                    ) { Text(f.shortLabel()) }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Every", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = { if (intervalCount > 1) intervalCount-- }, enabled = intervalCount > 1) {
                    Icon(Icons.Filled.Remove, contentDescription = "Less")
                }
                Text("$intervalCount", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { if (intervalCount < 99) intervalCount++ }) {
                    Icon(Icons.Filled.Add, contentDescription = "More")
                }
                Spacer(Modifier.width(4.dp))
                Text(frequency.unitLabel(intervalCount), style = MaterialTheme.typography.bodyLarge)
            }

            // #8 — optionally cap the number of occurrences (e.g. an EMI for 12 months).
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("End after a set number", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "For a fixed run like an EMI — e.g. 12 months, then it stops on its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = limited, onCheckedChange = { on -> limited = on; if (on && occurrenceCount < 1) occurrenceCount = 12 })
            }
            if (limited) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("After", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(12.dp))
                    IconButton(onClick = { if (occurrenceCount > 1) occurrenceCount-- }, enabled = occurrenceCount > 1) {
                        Icon(Icons.Filled.Remove, contentDescription = "Fewer")
                    }
                    Text("$occurrenceCount", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { if (occurrenceCount < 999) occurrenceCount++ }) {
                        Icon(Icons.Filled.Add, contentDescription = "More")
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(if (occurrenceCount == 1) "time" else "times", style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Starts", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(DateUtils.formatDay(startDate))
                }
            }

            // Live summary so the user can see exactly what they're scheduling.
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    RecurrenceMath.describe(frequency, intervalCount, RecurrenceMath.anchorFor(frequency, DateUtils.toLocalDate(startDate))),
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (kind == TxnKind.INCOME) "Source (optional)" else "Merchant / payee (optional)") },
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Note (optional)") },
                singleLine = true,
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    val a = amountMinor ?: return@Button
                    val c = categoryId ?: return@Button
                    val input = RecurringInput(
                        amountMinor = a,
                        kind = kind,
                        categoryId = c,
                        merchant = merchant,
                        note = note,
                        frequency = frequency,
                        intervalCount = intervalCount,
                        startDate = startDate,
                        occurrenceLimit = if (limited) occurrenceCount else 0,
                    )
                    // New rule → just save. Editing → ask whether to also rewrite past occurrences (#5).
                    if (initial == null) onSave(input, false) else pendingInput = input
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (initial == null) "Save recurring" else "Save changes")
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Cancel")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = DateUtils.toPickerUtcMillis(startDate))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { startDate = DateUtils.fromPickerUtcMillis(it) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }

    if (showCategoryPicker) {
        CategoryPickerSheet(
            categories = visibleCategories,
            selectedId = categoryId,
            onSelect = { id -> categoryId = id; showCategoryPicker = false },
            onAddNew = { showCategoryPicker = false; showAddBlocked = true },
            onDismiss = { showCategoryPicker = false },
        )
    }

    if (showAddBlocked) {
        AlertDialog(
            onDismissRequest = { showAddBlocked = false },
            title = { Text("Add categories first") },
            text = { Text("Create the category you need under Settings → Manage categories, then come back to schedule it.") },
            confirmButton = { TextButton(onClick = { showAddBlocked = false }) { Text("OK") } },
        )
    }

    if (showAmountKeypad) {
        AmountKeypadSheet(
            initialMinor = amountMinor ?: 0,
            accent = amountAccent,
            title = "Amount",
            onConfirm = { amountMinor = it },
            onDismiss = { showAmountKeypad = false },
        )
    }

    // #5 — on an edit, choose whether the change also rewrites the transactions already added by this rule.
    pendingInput?.let { input ->
        AlertDialog(
            onDismissRequest = { pendingInput = null },
            title = { Text("Apply changes to") },
            text = { Text("Update only upcoming transactions, or also the ones this rule has already added? Amount, category, merchant and note are updated on past entries — their dates stay put.") },
            confirmButton = {
                TextButton(onClick = { pendingInput = null; onSave(input, true) }) { Text("All, incl. past") }
            },
            dismissButton = {
                TextButton(onClick = { pendingInput = null; onSave(input, false) }) { Text("Only upcoming") }
            },
        )
    }
}

private fun RecurrenceFreq.shortLabel(): String = when (this) {
    RecurrenceFreq.DAILY -> "Day"
    RecurrenceFreq.WEEKLY -> "Week"
    RecurrenceFreq.MONTHLY -> "Month"
    RecurrenceFreq.YEARLY -> "Year"
}

private fun RecurrenceFreq.unitLabel(count: Int): String {
    val plural = count != 1
    return when (this) {
        RecurrenceFreq.DAILY -> if (plural) "days" else "day"
        RecurrenceFreq.WEEKLY -> if (plural) "weeks" else "week"
        RecurrenceFreq.MONTHLY -> if (plural) "months" else "month"
        RecurrenceFreq.YEARLY -> if (plural) "years" else "year"
    }
}
