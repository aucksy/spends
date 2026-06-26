package com.spends.app.ui.addedit

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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.money.Money
import com.spends.app.core.theme.Numerals
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.components.CategoryAvatar
import com.spends.app.ui.components.CategoryPickerField
import com.spends.app.ui.components.CategoryPickerSheet

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
                onAddCategory = { name, usage, onCreated -> viewModel.addCategory(name, usage, onCreated) },
                onSave = { amount, kind, categoryId, merchant, note, occurredAt ->
                    viewModel.save(amount, kind, categoryId, merchant, note, occurredAt)
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
    onAddCategory: (String, CategoryUsage, (Long) -> Unit) -> Unit,
    onSave: (Long, TxnKind, Long, String, String, Long) -> Unit,
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
        TextField(
            value = amountText,
            onValueChange = { input -> amountText = input.filter { it.isDigit() || it == '.' } },
            modifier = Modifier.fillMaxWidth(),
            textStyle = Numerals.balanceHero.copy(textAlign = TextAlign.Center),
            placeholder = {
                Text(
                    "0",
                    style = Numerals.balanceHero,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            },
            prefix = { Text("₹", style = Numerals.balanceHero, color = MaterialTheme.colorScheme.onSurface) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

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
                onSave(a, kind, c, merchant, note, occurredAt)
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
        AddCategoryDialog(
            isIncome = kind == TxnKind.INCOME,
            onConfirm = { name ->
                onAddCategory(name, usage) { newId -> selectedCategoryId = newId }
                showAddCategory = false
            },
            onDismiss = { showAddCategory = false },
        )
    }
}

@Composable
private fun AddCategoryDialog(isIncome: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isIncome) "New income category" else "New category") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
