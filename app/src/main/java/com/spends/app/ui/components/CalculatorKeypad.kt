package com.spends.app.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
    // Keypad keys use performHapticFeedback (NOT the raw Vibrator): it's routed through the input system
    // so it fires on the same touch frame — instant, no lag (#2) — and LONG_PRESS is the system's firm
    // haptic, stronger than the old KEYBOARD_TAP (#3). The raw Vibrator path was both delayed and, on
    // amplitude-control-less devices, not actually stronger.
    fun keyTap() = view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
                        keyTap()
                        onKey(key)
                    })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            KeypadKey(key = "0", modifier = Modifier.weight(1f), onClick = {
                keyTap(); onKey("0")
            })
            KeypadKey(key = ".", modifier = Modifier.weight(1f), onClick = {
                keyTap(); onKey(".")
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
 *
 * [referenceRemaining] (split use, #3/#4): how much this slice may take to hit exactly zero remainder.
 * When set, a live "₹X left" figure shows beside the title; entering more than it disables Done and shakes
 * the figure red — so a slice can never over-assign the total. Null (default) = a plain amount entry.
 *
 * A dedicated X (and the system back gesture) dismiss the sheet. NOTE: this sheet deliberately does NOT
 * veto swipe-to-dismiss. When it is opened nested inside another ModalBottomSheet (the quick-add split
 * flow), a second vetoing sheet deadlocked touch handling (froze the UI). The swipe-veto therefore lives
 * only on the single outer quick-add sheet, which still protects the overall split work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmountKeypadSheet(
    initialMinor: Long,
    accent: Color,
    title: String,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
    referenceRemaining: Long? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val startExpr = remember(initialMinor) { if (initialMinor > 0) Money.toEditString(initialMinor) else "" }
    var expr by rememberSaveable { mutableStateOf(startExpr) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    // Unsaved work = a non-blank amount the user has actually changed from what the field opened with, so an
    // accidental swipe / ✕ can't silently throw away a typed amount (confirmed first, like the quick-add sheet).
    val hasWork = expr.isNotBlank() && expr != startExpr

    val result = CalculatorEngine.evaluate(expr)
    val amountMinor = CalculatorEngine.toPositiveMinor(result)
    val currentMinor = result?.movePointRight(2)?.longValueExact() ?: 0L

    // Live "left to assign" + over-assign guard (#3/#4).
    val leftToAssign = referenceRemaining?.let { it - currentMinor }
    val over = referenceRemaining != null && amountMinor != null && amountMinor > referenceRemaining
    val remainderShake = remember { Animatable(0f) }
    LaunchedEffect(over) { if (over) shakeAmount(remainderShake) }

    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }

    ModalBottomSheet(
        // Guard an accidental swipe / tap-outside / back: if the amount was changed, re-show the sheet and
        // confirm before discarding (no confirmValueChange veto — that deadlocks touch).
        onDismissRequest = {
            if (hasWork) {
                scope.launch { sheetState.show() }
                showDiscardConfirm = true
            } else {
                onDismiss()
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (leftToAssign != null) {
                    Text(
                        text = if (leftToAssign >= 0L) {
                            "${Money.formatRupees(leftToAssign)} left"
                        } else {
                            "Over by ${Money.formatRupees(-leftToAssign)}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (leftToAssign < 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.offset(x = remainderShake.value.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(
                    onClick = { if (hasWork) showDiscardConfirm = true else dismiss() },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            CalculatorAmountDisplay(expr = expr, currentMinor = currentMinor, accent = accent)
            Spacer(Modifier.height(12.dp))
            CalculatorKeypad(
                onKey = { key -> expr = CalculatorEngine.append(expr, key) },
                canSave = amountMinor != null && !over,
                saving = false,
                onSave = {
                    val a = amountMinor
                    if (a != null && !over) {
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

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard this amount?") },
            text = { Text("The amount you've entered will be lost.") },
            confirmButton = { TextButton(onClick = { showDiscardConfirm = false; dismiss() }) { Text("Discard") } },
            // Bring the sheet back if a swipe had slid it away — recovers even if the onDismiss re-show didn't take.
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false; scope.launch { sheetState.show() } }) { Text("Keep editing") }
            },
        )
    }
}

/** A quick horizontal wobble to nudge attention to the over-assigned "left" figure (#4). */
private suspend fun shakeAmount(anim: Animatable<Float, *>) {
    val steps = listOf(-8f, 8f, -6f, 6f, -3f, 0f)
    anim.snapTo(0f)
    for (t in steps) anim.animateTo(t, animationSpec = tween(durationMillis = 34))
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
