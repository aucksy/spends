package com.spends.app.ui.quickadd

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.calc.CalculatorEngine
import com.spends.app.core.money.Money
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.core.theme.Numerals
import com.spends.app.core.time.DateUtils
import com.spends.app.data.repo.AllocationInput
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.components.AmountKeypadSheet
import com.spends.app.ui.components.CalculatorKeypad
import com.spends.app.ui.components.CategoryAvatar
import com.spends.app.ui.components.CategoryEditorSheet
import com.spends.app.ui.components.CategoryPickerField
import com.spends.app.ui.components.CategoryPickerSheet
import com.spends.app.ui.cards.PaidWithChip
import com.spends.app.ui.cards.PaidWithPickerSheet
import kotlinx.coroutines.launch

/** One slice of a split: a category and its share (paise). Each becomes its own transaction on save (#2). */
private data class SplitRow(val categoryId: Long, val amountMinor: Long)

/** Persists split rows across config changes as a flat [cat, amt, cat, amt, …] Long list. */
private val SplitRowsSaver = listSaver<List<SplitRow>, Long>(
    save = { rows -> rows.flatMap { listOf(it.categoryId, it.amountMinor) } },
    restore = { flat -> flat.chunked(2).mapNotNull { if (it.size == 2) SplitRow(it[0], it[1]) else null } },
)

/**
 * The half-screen "quick add" sheet (PRD: fast manual entry). Amount is entered on a built-in
 * calculator keypad (basic math), then category, note and date. Editing an existing transaction
 * still uses the full screen — this is purely the fast add path off the + button.
 *
 * #2: an optional SPLIT mode divides the entered total across several categories; each slice is saved as
 * its own normal transaction (BAU) sharing the same date / note / instrument.
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
    val view = LocalView.current
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val paymentState by viewModel.paymentState.collectAsStateWithLifecycle()

    var expr by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(TxnKind.EXPENSE) }
    var selectedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var occurredAt by rememberSaveable { mutableStateOf(viewModel.nowMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var selectedPaymentMethodId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Pre-select the user's default instrument (#2) once the state loads, unless they've already chosen.
    var seededDefaultInstrument by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(paymentState.enabled, paymentState.defaultId) {
        if (!seededDefaultInstrument && paymentState.enabled && paymentState.defaultId != null) {
            selectedPaymentMethodId = paymentState.defaultId
            seededDefaultInstrument = true
        }
    }
    var showPaidWith by remember { mutableStateOf(false) }
    // #6: the "pick a category" warning + a gentle shake only fire when the user actually tries to save
    // without one — never reactively while typing the amount (which used to shove the keypad).
    var categoryError by remember { mutableStateOf(false) }
    val categoryShake = remember { Animatable(0f) }

    // ---- Split state (#2) ----
    var splitMode by rememberSaveable { mutableStateOf(false) }
    var splits by rememberSaveable(stateSaver = SplitRowsSaver) { mutableStateOf(emptyList<SplitRow>()) }
    var showSplitCategoryPicker by remember { mutableStateOf(false) }
    var showSplitAmountKeypad by remember { mutableStateOf(false) }
    // Which split row an inline picker/keypad targets; null = we're adding a NEW row.
    var editingRowIndex by remember { mutableStateOf<Int?>(null) }
    var pendingSplitCategoryId by remember { mutableStateOf<Long?>(null) }

    val usageFilter = if (kind == TxnKind.INCOME) CategoryUsage.INCOME else CategoryUsage.EXPENSE
    val visibleCategories = categories.filter { it.usage == usageFilter || it.usage == CategoryUsage.BOTH }
    val selectedCategory = visibleCategories.firstOrNull { it.id == selectedCategoryId }

    val result = CalculatorEngine.evaluate(expr)
    val amountMinor = CalculatorEngine.toPositiveMinor(result)
    // Save is tappable as soon as a valid amount exists; a missing category is handled on tap (shake +
    // warning) rather than by disabling the button, so the user gets feedback instead of a dead button.
    val amountReady = amountMinor != null && !saving

    val splitAssigned = splits.sumOf { it.amountMinor }
    val splitRemainder = (amountMinor ?: 0L) - splitAssigned
    val splitReady = splitMode && amountMinor != null && splits.isNotEmpty() && splitRemainder == 0L && !saving
    val accent = if (kind == TxnKind.INCOME) LocalSemanticColors.current.income else LocalSemanticColors.current.expense

    /** Reset everything category-related when the kind flips (an expense slice makes no sense under Income). */
    fun onKindChange(newKind: TxnKind) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        kind = newKind
        selectedCategoryId = null
        categoryError = false
        // Leave split mode too, so we never show an empty split section with the wrong-kind categories.
        splits = emptyList()
        splitMode = false
    }

    fun dismiss() {
        // Animate the sheet away, then fire onDismiss once (guarded so an interrupted hide doesn't).
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }

    fun saveSingle() {
        val a = amountMinor
        val c = selectedCategoryId
        when {
            a != null && c != null -> {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                val pmId = if (kind == TxnKind.EXPENSE) selectedPaymentMethodId else null
                viewModel.save(a, kind, c, note, occurredAt, paymentMethodId = pmId) { onSaved(); dismiss() }
            }
            a != null -> {
                categoryError = true
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                scope.launch { shakeField(categoryShake) }
            }
        }
    }

    fun saveSplit() {
        if (!splitReady) return
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        val pmId = if (kind == TxnKind.EXPENSE) selectedPaymentMethodId else null
        viewModel.saveSplit(
            kind = kind,
            note = note,
            occurredAt = occurredAt,
            paymentMethodId = pmId,
            slices = splits.map { AllocationInput(it.categoryId, it.amountMinor) },
        ) { onSaved(); dismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = kind == TxnKind.EXPENSE,
                    onClick = { onKindChange(TxnKind.EXPENSE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Expense") }
                SegmentedButton(
                    selected = kind == TxnKind.INCOME,
                    onClick = { onKindChange(TxnKind.INCOME) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Income") }
            }

            Spacer(Modifier.height(10.dp))
            AmountDisplay(
                expr = expr,
                currentMinor = result?.movePointRight(2)?.longValueExact() ?: 0L,
                kind = kind,
                caption = if (splitMode) "TOTAL TO SPLIT" else null,
            )
            Spacer(Modifier.height(12.dp))

            if (!splitMode) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).offset(x = categoryShake.value.dp)) {
                        CategoryPickerField(
                            selected = selectedCategory,
                            placeholder = "Category",
                            onClick = { showCategoryPicker = true },
                        )
                    }
                    DateButton(occurredAt) { showDatePicker = true }
                }
                if (categoryError) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Pick a category to save",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Date", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DateButton(occurredAt) { showDatePicker = true }
                }
            }

            // "Paid with" — only when Smart Cycle is on, and only for expenses (income isn't paid with a card).
            if (paymentState.enabled && kind == TxnKind.EXPENSE) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Paid with", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PaidWithChip(
                        selected = paymentState.cards.firstOrNull { it.id == selectedPaymentMethodId },
                        onClick = { showPaidWith = true },
                    )
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

            if (!splitMode) {
                // Enter split mode; seed the first slice from any category already chosen so there's a head start.
                TextButton(
                    enabled = amountMinor != null,
                    onClick = {
                        splits = selectedCategoryId?.let { listOf(SplitRow(it, amountMinor ?: 0L)) } ?: emptyList()
                        splitMode = true
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Split across categories")
                }
            } else {
                SplitSection(
                    splits = splits,
                    categoriesById = { id -> visibleCategories.firstOrNull { it.id == id } },
                    remainder = splitRemainder,
                    accent = accent,
                    onEditCategory = { index -> editingRowIndex = index; showSplitCategoryPicker = true },
                    onEditAmount = { index -> editingRowIndex = index; showSplitAmountKeypad = true },
                    onRemove = { index -> splits = splits.filterIndexed { i, _ -> i != index } },
                    onAdd = { editingRowIndex = null; showSplitCategoryPicker = true },
                    onCancelSplit = { splitMode = false },
                )
            }

            Spacer(Modifier.height(12.dp))
            CalculatorKeypad(
                // Key-tap haptics live inside the shared keypad now; here we just feed the expression.
                onKey = { key -> expr = CalculatorEngine.append(expr, key) },
                canSave = if (splitMode) splitReady else amountReady,
                saving = saving,
                saveLabel = if (splitMode) "Save ${splits.size}" else "Save",
                onSave = { if (splitMode) saveSplit() else saveSingle() },
            )
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
            onSelect = { id ->
                selectedCategoryId = id
                categoryError = false // clears the warning the moment a category is chosen
                showCategoryPicker = false
            },
            onAddNew = { showCategoryPicker = false; showAddCategory = true },
            onDismiss = { showCategoryPicker = false },
        )
    }

    // Split: choose a category for a new or existing slice.
    if (showSplitCategoryPicker) {
        val targetIndex = editingRowIndex
        CategoryPickerSheet(
            categories = visibleCategories,
            selectedId = targetIndex?.let { splits.getOrNull(it)?.categoryId },
            onSelect = { id ->
                showSplitCategoryPicker = false
                if (targetIndex == null) {
                    // New slice: remember the category, then ask for its amount (pre-filled with the remainder).
                    pendingSplitCategoryId = id
                    showSplitAmountKeypad = true
                } else {
                    splits = splits.mapIndexed { i, row -> if (i == targetIndex) row.copy(categoryId = id) else row }
                    editingRowIndex = null
                }
            },
            onDismiss = { showSplitCategoryPicker = false; editingRowIndex = null },
        )
    }

    // Split: enter/adjust a slice amount on the same calculator keypad.
    if (showSplitAmountKeypad) {
        val targetIndex = editingRowIndex
        val initial = if (targetIndex != null) {
            splits.getOrNull(targetIndex)?.amountMinor ?: 0L
        } else {
            splitRemainder.coerceAtLeast(0L) // pre-fill a new slice with what's left to assign
        }
        AmountKeypadSheet(
            initialMinor = initial,
            accent = accent,
            title = "Split amount",
            onConfirm = { minor ->
                if (targetIndex != null) {
                    splits = splits.mapIndexed { i, row -> if (i == targetIndex) row.copy(amountMinor = minor) else row }
                } else {
                    pendingSplitCategoryId?.let { splits = splits + SplitRow(it, minor) }
                }
            },
            onDismiss = { showSplitAmountKeypad = false; editingRowIndex = null; pendingSplitCategoryId = null },
        )
    }

    if (showPaidWith) {
        PaidWithPickerSheet(
            cards = paymentState.cards,
            selectedId = selectedPaymentMethodId,
            onSelect = { selectedPaymentMethodId = it; showPaidWith = false },
            onDismiss = { showPaidWith = false },
        )
    }

    if (showAddCategory) {
        // Create a category WITH an icon directly from the quick-add keypad (#5). The type is fixed to the
        // current Expense/Income kind, so the new category always shows up in this picker.
        CategoryEditorSheet(
            initial = null,
            fixedUsage = usageFilter,
            onSave = { name, usage, iconKey, customized ->
                viewModel.addCategory(name, usage, if (customized) iconKey else null) { id ->
                    selectedCategoryId = id
                    categoryError = false
                }
                showAddCategory = false
            },
            onDismiss = { showAddCategory = false },
        )
    }
}

/** The Split editor block: header + remainder, one row per slice, and an "add category" action. */
@Composable
private fun SplitSection(
    splits: List<SplitRow>,
    categoriesById: (Long) -> com.spends.app.data.db.entity.CategoryEntity?,
    remainder: Long,
    accent: androidx.compose.ui.graphics.Color,
    onEditCategory: (Int) -> Unit,
    onEditAmount: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
    onCancelSplit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Split across categories", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = onCancelSplit) { Text("Undo split") }
    }
    Spacer(Modifier.height(4.dp))

    splits.forEachIndexed { index, row ->
        val cat = categoriesById(row.categoryId)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).clickable { onEditCategory(index) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (cat != null) {
                        CategoryAvatar(cat.iconKey, cat.colorHex, size = 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(cat.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    } else {
                        Text("Category", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    Money.formatRupees(row.amountMinor),
                    style = Numerals.amountRow,
                    modifier = Modifier.clickable { onEditAmount(index) }.padding(horizontal = 6.dp),
                )
                IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    Spacer(Modifier.height(2.dp))
    TextButton(onClick = onAdd) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Add category")
    }

    // Remainder line — the guardrail: Save stays disabled until this reaches zero.
    val remainderColor = when {
        remainder == 0L -> LocalSemanticColors.current.income
        remainder < 0L -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val remainderText = when {
        remainder == 0L -> "All assigned"
        remainder < 0L -> "Over by ${Money.formatRupees(-remainder)}"
        else -> "${Money.formatRupees(remainder)} left to assign"
    }
    Text(remainderText, style = MaterialTheme.typography.bodySmall, color = remainderColor, fontWeight = FontWeight.SemiBold)
}

/** The shared calendar "date" button used by both single and split modes. */
@Composable
private fun DateButton(occurredAt: Long, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Filled.CalendarMonth, contentDescription = "Date", modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(DateUtils.formatDay(occurredAt))
    }
}

/** A short left-right wobble used to draw attention to the category field on a failed save (#6). */
private suspend fun shakeField(anim: Animatable<Float, *>) {
    val steps = listOf(-10f, 10f, -8f, 8f, -5f, 5f, -2f, 0f)
    anim.snapTo(0f)
    for (target in steps) anim.animateTo(target, animationSpec = tween(durationMillis = 38))
}

@Composable
private fun AmountDisplay(expr: String, currentMinor: Long, kind: TxnKind, caption: String? = null) {
    val semantic = LocalSemanticColors.current
    val accent = if (kind == TxnKind.INCOME) semantic.income else semantic.expense
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
