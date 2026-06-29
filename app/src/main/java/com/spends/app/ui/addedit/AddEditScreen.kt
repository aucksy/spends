package com.spends.app.ui.addedit

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.money.Money
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.core.theme.Numerals
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.components.AmountKeypadSheet
import com.spends.app.ui.components.CategoryAvatar
import com.spends.app.ui.components.CategoryEditorSheet
import com.spends.app.ui.components.CategoryPickerField
import com.spends.app.ui.components.CategoryPickerSheet
import com.spends.app.ui.cards.PaidWithField
import com.spends.app.ui.cards.PaidWithPickerSheet
import com.spends.app.ui.cards.PaymentState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditScreen(
    onDone: () -> Unit,
    viewModel: AddEditViewModel = hiltViewModel(),
) {
    val initial by viewModel.initial.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val finished by viewModel.finished.collectAsStateWithLifecycle()
    val paymentState by viewModel.paymentState.collectAsStateWithLifecycle()
    var showDelete by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(finished) { if (finished) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.screenTitle) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    if (viewModel.isEdit) {
                        IconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete transaction")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val seed = initial
        if (seed == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            AddEditForm(
                modifier = Modifier.padding(padding),
                initial = seed,
                categories = categories,
                saving = saving,
                saveLabel = viewModel.saveLabel,
                paymentState = paymentState,
                showPaidWith = viewModel.showPaidWith,
                onAddCategory = { name, usage, iconKey, onCreated -> viewModel.addCategory(name, usage, iconKey, onCreated) },
                onSave = { amount, kind, categoryId, merchant, note, occurredAt, paymentMethodId ->
                    viewModel.save(amount, kind, categoryId, merchant, note, occurredAt, paymentMethodId)
                },
            )
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete this transaction?") },
            text = { Text("It moves to Trash — you can restore it from Settings → Trash.") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; viewModel.delete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditForm(
    modifier: Modifier,
    initial: AddEditInitial,
    categories: List<CategoryEntity>,
    saving: Boolean,
    saveLabel: String,
    paymentState: PaymentState,
    showPaidWith: Boolean,
    onAddCategory: (String, CategoryUsage, String?, (Long) -> Unit) -> Unit,
    onSave: (Long, TxnKind, Long, String, String, Long, Long?) -> Unit,
) {
    var amountText by rememberSaveable { mutableStateOf(initial.amountText) }
    var kind by rememberSaveable { mutableStateOf(initial.kind) }
    var selectedCategoryId by rememberSaveable { mutableStateOf(initial.categoryId) }
    var merchant by rememberSaveable { mutableStateOf(initial.merchant) }
    var note by rememberSaveable { mutableStateOf(initial.note) }
    var occurredAt by rememberSaveable { mutableStateOf(initial.occurredAt) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showAmountKeypad by remember { mutableStateOf(false) }
    var selectedPaymentMethodId by rememberSaveable { mutableStateOf(initial.paymentMethodId) }
    var showPaidWithPicker by remember { mutableStateOf(false) }

    val usageFilter = if (kind == TxnKind.INCOME) CategoryUsage.INCOME else CategoryUsage.EXPENSE
    val visibleCategories = categories.filter { it.usage == usageFilter || it.usage == CategoryUsage.BOTH }
    val selectedCategory = visibleCategories.firstOrNull { it.id == selectedCategoryId }

    val amountMinor = Money.parseRupeesToMinor(amountText)?.takeIf { it > 0 }
    val canSave = amountMinor != null && selectedCategoryId != null && !saving

    Column(
        modifier = modifier
            .fillMaxSize()
            // imePadding BEFORE verticalScroll so the IME shrinks the scroll viewport (under
            // edge-to-edge), letting the focused Merchant/Note field auto-scroll above the keyboard.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = kind == TxnKind.EXPENSE,
                onClick = { kind = TxnKind.EXPENSE; selectedCategoryId = null },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Expense") }
            SegmentedButton(
                selected = kind == TxnKind.INCOME,
                onClick = { kind = TxnKind.INCOME; selectedCategoryId = null },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Income") }
        }

        Spacer(Modifier.height(8.dp))

        // Amount hero — big tabular-mono entry, centred (Design System).
        Text(
            "AMOUNT",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        // Tap the amount to enter it on the calculator KEYPAD (#4) — the same keypad as quick-add, instead
        // of the system keyboard. This now applies to new, edit, and the notification "Review & Add" flows.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAmountKeypad = true }
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "₹" + amountText.ifBlank { "0" },
                style = Numerals.balanceHero,
                color = if (amountText.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(8.dp))

        Text("Category", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        CategoryPickerField(
            selected = selectedCategory,
            placeholder = "Pick a category",
            onClick = { showCategoryPicker = true },
        )

        Spacer(Modifier.height(16.dp))

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

        // "Paid with" — only when Smart Cycle is on and this isn't a capture review (those auto-tag from the
        // SMS last4 on save), and only for expenses.
        if (paymentState.enabled && showPaidWith && kind == TxnKind.EXPENSE) {
            Spacer(Modifier.height(4.dp))
            PaidWithField(
                selected = paymentState.cards.firstOrNull { it.id == selectedPaymentMethodId },
                onClick = { showPaidWithPicker = true },
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Date", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { showDatePicker = true }) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(DateUtils.formatDay(occurredAt))
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                val a = amountMinor ?: return@Button
                val c = selectedCategoryId ?: return@Button
                val pmId = if (kind == TxnKind.EXPENSE) selectedPaymentMethodId else null
                onSave(a, kind, c, merchant, note, occurredAt, pmId)
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(saveLabel)
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = DateUtils.toPickerUtcMillis(occurredAt),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { occurredAt = DateUtils.fromPickerUtcMillis(it) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showCategoryPicker) {
        CategoryPickerSheet(
            categories = visibleCategories,
            selectedId = selectedCategoryId,
            onSelect = { id -> selectedCategoryId = id; showCategoryPicker = false },
            onAddNew = { showCategoryPicker = false; showAddCategory = true },
            onDismiss = { showCategoryPicker = false },
        )
    }

    if (showAddCategory) {
        val usage = if (kind == TxnKind.INCOME) CategoryUsage.INCOME else CategoryUsage.EXPENSE
        // Create a category WITH an icon, inline from the transaction flow (#5) — the same editor as
        // Settings. The type is fixed to the current Expense/Income kind (no Spending/Income selector).
        CategoryEditorSheet(
            initial = null,
            fixedUsage = usage,
            onSave = { name, u, iconKey, customized ->
                onAddCategory(name, u, if (customized) iconKey else null) { newId -> selectedCategoryId = newId }
                showAddCategory = false
            },
            onDismiss = { showAddCategory = false },
        )
    }

    if (showAmountKeypad) {
        // The SAME calculator keypad used by quick-add — now also for the full editor, so editing an
        // existing amount and the notification "Review & Add" flow both get the keypad, not the system
        // keyboard (#4). Seeded with the current amount; commits back as a formatted edit string.
        AmountKeypadSheet(
            initialMinor = Money.parseRupeesToMinor(amountText) ?: 0L,
            accent = if (kind == TxnKind.INCOME) LocalSemanticColors.current.income else LocalSemanticColors.current.expense,
            title = "Amount",
            onConfirm = { minor -> amountText = Money.toEditString(minor) },
            onDismiss = { showAmountKeypad = false },
        )
    }

    if (showPaidWithPicker) {
        PaidWithPickerSheet(
            cards = paymentState.cards,
            selectedId = selectedPaymentMethodId,
            onSelect = { selectedPaymentMethodId = it; showPaidWithPicker = false },
            onDismiss = { showPaidWithPicker = false },
        )
    }
}

// Category creation (with icon) now goes through CategoryEditorSheet (com.spends.app.ui.components), #5.
