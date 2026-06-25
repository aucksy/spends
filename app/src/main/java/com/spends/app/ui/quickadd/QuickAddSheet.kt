package com.spends.app.ui.quickadd

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.calc.CalculatorEngine
import com.spends.app.core.money.Money
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.core.theme.Numerals
import com.spends.app.core.time.DateUtils
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.components.CategoryPickerField
import com.spends.app.ui.components.CategoryPickerSheet
import kotlinx.coroutines.launch

/**
 * The half-screen "quick add" sheet (PRD: fast manual entry). Amount is entered on a built-in
 * calculator keypad (basic math), then category, note and date. Editing an existing transaction
 * still uses the full screen — this is purely the fast add path off the + button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    viewModel: QuickAddViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()

    var expr by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(TxnKind.EXPENSE) }
    var selectedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var occurredAt by rememberSaveable { mutableStateOf(viewModel.nowMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }

    val usageFilter = if (kind == TxnKind.INCOME) CategoryUsage.INCOME else CategoryUsage.EXPENSE
    val visibleCategories = categories.filter { it.usage == usageFilter || it.usage == CategoryUsage.BOTH }
    val selectedCategory = visibleCategories.firstOrNull { it.id == selectedCategoryId }

    val result = CalculatorEngine.evaluate(expr)
    val amountMinor = CalculatorEngine.toPositiveMinor(result)
    val canSave = amountMinor != null && selectedCategoryId != null && !saving

    fun dismiss() {
        // Animate the sheet away, then fire onDismiss once (guarded so an interrupted hide doesn't).
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
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

            Spacer(Modifier.height(10.dp))
            AmountDisplay(expr = expr, currentMinor = result?.movePointRight(2)?.longValueExact() ?: 0L, kind = kind)
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    CategoryPickerField(
                        selected = selectedCategory,
                        placeholder = "Category",
                        onClick = { showCategoryPicker = true },
                    )
                }
                TextButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = "Date", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(DateUtils.formatDay(occurredAt))
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Note (optional)") },
                singleLine = true,
            )

            Spacer(Modifier.height(12.dp))
            CalculatorKeypad(
                onKey = { key -> expr = CalculatorEngine.append(expr, key) },
                canSave = canSave,
                saving = saving,
                onSave = {
                    val a = amountMinor
                    val c = selectedCategoryId
                    if (a != null && c != null) {
                        viewModel.save(a, kind, c, note, occurredAt) {
                            onSaved()
                            dismiss()
                        }
                    }
                },
            )
            if (amountMinor != null && selectedCategoryId == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Pick a category to save",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = DateUtils.toPickerUtcMillis(occurredAt))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { occurredAt = DateUtils.fromPickerUtcMillis(it) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }

    if (showCategoryPicker) {
        CategoryPickerSheet(
            categories = visibleCategories,
            selectedId = selectedCategoryId,
            onSelect = { id -> selectedCategoryId = id; showCategoryPicker = false },
            onDismiss = { showCategoryPicker = false },
        )
    }
}

@Composable
private fun AmountDisplay(expr: String, currentMinor: Long, kind: TxnKind) {
    val semantic = LocalSemanticColors.current
    val accent = if (kind == TxnKind.INCOME) semantic.income else semantic.expense
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Show the live expression (with × ÷ − glyphs) only while a calculation is in progress.
        if (CalculatorEngine.hasPendingOperation(expr)) {
            Text(
                text = displayExpression(expr),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = Money.formatRupees(currentMinor),
            style = Numerals.balanceHero,
            color = accent,
            maxLines = 1,
        )
    }
}

/** Map the stored ASCII operators to their display glyphs for the expression line. */
private fun displayExpression(expr: String): String =
    expr.replace("*", " × ").replace("/", " ÷ ").replace("+", " + ").replace("-", " − ")

@Composable
private fun CalculatorKeypad(
    onKey: (String) -> Unit,
    canSave: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
) {
    val rows = listOf(
        listOf("C", "/", "*", "<"),
        listOf("7", "8", "9", "-"),
        listOf("4", "5", "6", "+"),
        listOf("1", "2", "3", "="),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    KeypadKey(key = key, modifier = Modifier.weight(1f), onClick = { onKey(key) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            KeypadKey(key = "0", modifier = Modifier.weight(1f), onClick = { onKey("0") })
            KeypadKey(key = ".", modifier = Modifier.weight(1f), onClick = { onKey(".") })
            SaveKey(modifier = Modifier.weight(2f), enabled = canSave, saving = saving, onClick = onSave)
        }
    }
}

@Composable
private fun KeypadKey(key: String, modifier: Modifier, onClick: () -> Unit) {
    val isOperator = key in listOf("/", "*", "-", "+", "=")
    val isUtil = key in listOf("C", "<")
    val container = when {
        isOperator -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        isOperator -> MaterialTheme.colorScheme.onSecondaryContainer
        isUtil -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        modifier = modifier.height(54.dp).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = keyLabel(key),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = content,
            )
        }
    }
}

@Composable
private fun SaveKey(modifier: Modifier, enabled: Boolean, saving: Boolean, onClick: () -> Unit) {
    val container = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        modifier = modifier.height(54.dp).clickable(enabled = enabled && !saving) { onClick() },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (saving) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = content, strokeWidth = 2.dp)
            } else {
                Text("Save", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = content)
            }
        }
    }
}

private fun keyLabel(key: String): String = when (key) {
    "/" -> "÷"
    "*" -> "×"
    "-" -> "−"
    "<" -> "⌫"
    else -> key
}
