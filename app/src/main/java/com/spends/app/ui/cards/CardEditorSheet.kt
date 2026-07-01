package com.spends.app.ui.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spends.app.ui.components.NumberWheelPicker

/** Initial values for [CardEditorSheet] — empty for a fresh add, prefilled for edit/review. */
data class CardEditorInitial(
    val label: String = "",
    val last4: String? = null,
    val institution: String? = null,
    val billingDay: Int? = null,
    val dueDay: Int? = null,
)

/**
 * One bottom sheet for adding, editing, or confirming-a-discovered instrument. [onSave] returns the entered
 * values; the caller decides whether that means create / update / confirm-candidate. Edit mode also gets a
 * Delete. [isBank] = a bank/UPI account (no billing day — it rides the salary cycle, #2); false = a card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardEditorSheet(
    title: String,
    saveLabel: String,
    initial: CardEditorInitial,
    onSave: (label: String, last4: String?, institution: String?, billingDay: Int?, dueDay: Int?) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    isBank: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var label by remember { mutableStateOf(initial.label) }
    var last4 by remember { mutableStateOf(initial.last4 ?: "") }
    var billingDay by remember { mutableStateOf(initial.billingDay) }
    var showBillingPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val canSave = label.isNotBlank() || last4.isNotBlank()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(if (isBank) "Bank name" else "Card name") },
                placeholder = { Text(if (isBank) "e.g. Axis Bank" else "e.g. HDFC Millennia") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = last4,
                onValueChange = { input -> last4 = input.filter { it.isDigit() }.take(4) },
                label = { Text("Last 4 digits (optional)") },
                placeholder = { Text("4821") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            // Billing day (cards only) — opens a wheel; unset means the card follows the salary cycle. Banks
            // always ride the salary cycle, so they have no billing day (#2).
            if (!isBank) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showBillingPicker = true }
                        .padding(vertical = 12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Billing day", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "This card's spending is grouped from this day. Leave it unset and it follows your salary cycle until you know the day.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Text(
                        billingDay?.let(::cardOrdinal) ?: "Not set",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { onSave(label.trim(), last4.ifBlank { null }, initial.institution, billingDay, initial.dueDay) },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(saveLabel) }

            if (onDelete != null) {
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showBillingPicker) {
        BillingDayDialog(
            current = billingDay ?: 1,
            onConfirm = { billingDay = it; showBillingPicker = false },
            onClear = { billingDay = null; showBillingPicker = false },
            onDismiss = { showBillingPicker = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (isBank) "Delete this bank?" else "Delete this card?") },
            text = { Text("Transactions paid with it stay, but become untagged (counted under Bank). This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BillingDayDialog(current: Int, onConfirm: (Int) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(current.coerceIn(1, 28)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Billing day") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "The day this card's statement usually generates (1–28).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                NumberWheelPicker(
                    value = selected,
                    range = 1..28,
                    onValueChange = { selected = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onClear) { Text("No billing day") } },
    )
}

/** day → "17th" etc. */
internal fun cardOrdinal(day: Int): String {
    val suffix = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}
