package com.spends.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * "Automatic Entries" settings sub-page: the two ways transactions get added without typing — capture from
 * bank SMS/notifications, and scheduled recurring transactions. Both open their own dedicated screens.
 */
@Composable
fun AutomaticSettingsScreen(
    onBack: () -> Unit,
    onOpenCapture: () -> Unit,
    onOpenRecurring: () -> Unit,
) {
    SettingsSubScaffold(title = "Automatic Entries", onBack = onBack) {
        SettingsSection("Add without typing") {
            ClickableRow(
                title = "Detect from SMS & notifications",
                value = "Review & add from bank alerts",
                onClick = onOpenCapture,
                leading = { Icon(Icons.Filled.Sms, contentDescription = null) },
            )
            RowDivider()
            ClickableRow(
                title = "Recurring transactions",
                value = "Scheduled rent, EMIs & subscriptions",
                onClick = onOpenRecurring,
                leading = { Icon(Icons.Filled.Autorenew, contentDescription = null) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
