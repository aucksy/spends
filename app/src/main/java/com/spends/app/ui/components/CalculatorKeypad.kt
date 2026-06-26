package com.spends.app.ui.components

import android.view.HapticFeedbackConstants
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spends.app.core.calc.CalculatorEngine
import com.spends.app.core.money.Money
import com.spends.app.core.theme.Numerals
import kotlinx.coroutines.launch

/**
 * The shared on-screen calculator keypad used for amount entry — quick-add and the recurring editor use
 * the exact same one (#12). Keys carry a tap haptic; the Save key's haptic is left to [onSave] so each
 * caller can signal success vs a blocked save itself.
 */
@Composable
fun CalculatorKeypad(
    onKey: (String) -> Unit,
    canSave: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    saveLabel: String = "Save",
) {
    val view = LocalView.current
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
                    KeypadKey(key = key, modifier = Modifier.weight(1f), onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onKey(key)
                    })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            KeypadKey(key = "0", modifier = Modifier.weight(1f), onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onKey("0")
            })
            KeypadKey(key = ".", modifier = Modifier.weight(1f), onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onKey(".")
            })
            SaveKey(modifier = Modifier.weight(2f), enabled = canSave, saving = saving, label = saveLabel, onClick = onSave)
        }
    }
}

/**
 * The live amount line above the keypad: shows the in-progress expression (with × ÷ − glyphs) and the
 * current value in [accent].
 */
@Composable
fun CalculatorAmountDisplay(expr: String, currentMinor: Long, accent: Color) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (CalculatorEngine.hasPendingOperation(expr)) {
            Text(
                text = displayExpression(expr),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(text = Money.formatRupees(currentMinor), style = Numerals.balanceHero, color = accent, maxLines = 1)
    }
}

/**
 * A bottom sheet for entering an amount on the calculator keypad, returning paise via [onConfirm].
 * Used by forms (e.g. recurring) that want the same keypad as quick-add without embedding it inline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmountKeypadSheet(
    initialMinor: Long,
    accent: Color,
    title: String,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var expr by rememberSaveable { mutableStateOf(if (initialMinor > 0) Money.toEditString(initialMinor) else "") }

    val result = CalculatorEngine.evaluate(expr)
    val amountMinor = CalculatorEngine.toPositiveMinor(result)
    val currentMinor = result?.movePointRight(2)?.longValueExact() ?: 0L

    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            CalculatorAmountDisplay(expr = expr, currentMinor = currentMinor, accent = accent)
            Spacer(Modifier.height(12.dp))
            CalculatorKeypad(
                onKey = { key -> expr = CalculatorEngine.append(expr, key) },
                canSave = amountMinor != null,
                saving = false,
                onSave = {
                    val a = amountMinor
                    if (a != null) {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        onConfirm(a)
                        dismiss()
                    }
                },
                saveLabel = "Done",
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Map the stored ASCII operators to their display glyphs for the expression line. */
private fun displayExpression(expr: String): String =
    expr.replace("*", " × ").replace("/", " ÷ ").replace("+", " + ").replace("-", " − ")

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
private fun SaveKey(modifier: Modifier, enabled: Boolean, saving: Boolean, label: String, onClick: () -> Unit) {
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
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = content)
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
