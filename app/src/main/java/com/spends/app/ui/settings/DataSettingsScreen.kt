package com.spends.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.ui.backup.SpreadsheetSection

/**
 * "Data & Trash" settings sub-page: import/export via spreadsheet (Excel / CSV), and the Trash bin with its
 * auto-purge window. Lifted verbatim from the old settings page's "Spreadsheet" + "Data" sections.
 */
@Composable
fun DataSettingsScreen(
    onBack: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenTrash: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SettingsSubScaffold(title = "Data & Trash", onBack = onBack) {
        SettingsSection("Spreadsheet (Excel / CSV)") {
            SpreadsheetSection(onImport = onOpenImport)
        }

        SettingsSection("Trash") {
            ClickableRow(
                title = "Trash",
                value = "Auto-purge after ${state.trashRetentionDays} days",
                onClick = onOpenTrash,
                leading = { Icon(Icons.Filled.Delete, contentDescription = null) },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}
