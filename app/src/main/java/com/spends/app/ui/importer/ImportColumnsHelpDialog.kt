package com.spends.app.ui.importer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Explains which columns the generic spreadsheet importer looks for (#3). Shown from Settings and from
 * onboarding so the same guidance applies everywhere — no Monito-specific wording in the UI; instead we
 * describe the generic format and note that Monito (and the app's own export) already match it.
 */
@Composable
fun ImportColumnsHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which columns does import need?") },
        text = {
            Column {
                Text(
                    "Spends reads any Excel (.xlsx/.xls) or CSV with a header row. It auto-detects these " +
                        "columns by name (in any order):",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Bullet("Date — when it happened (e.g. 2024-03-15 or 15/03/2024)")
                Bullet("Amount — the value (₹ and commas are fine)")
                Bullet("Type — Income or Expense (optional; otherwise treated as expense)")
                Bullet("Category — the category name (created if new)")
                Bullet("Note or Merchant — the payee/description (optional)")
                Spacer(Modifier.height(8.dp))
                Text(
                    "New rows are added and duplicates are skipped — importing the same file twice is safe. " +
                        "Exports from Monito and from Spends already use this format.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text", style = MaterialTheme.typography.bodyMedium)
}
