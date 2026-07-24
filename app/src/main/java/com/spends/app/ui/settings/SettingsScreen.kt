package com.spends.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Settings HUB. The old single long-scroll page was split into a small set of tappable category cards, each
 * opening its own focused sub-page (Money & Cycles / Automatic Entries / Categories / Appearance / Backup &
 * Restore / Data & Trash) — far less intimidating, and easy to scan. Each card navigates via the callbacks;
 * the actual controls live in the sub-screens (see MoneySettingsScreen, AppearanceSettingsScreen, etc.).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenMoney: () -> Unit,
    onOpenAutomatic: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenData: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            SettingsHubRow(
                icon = Icons.Filled.AccountBalanceWallet,
                title = "Money & Cycles",
                subtitle = "Salary day, carry forward, Smart Cycle, banks & cards",
                onClick = onOpenMoney,
            )
            SettingsHubRow(
                icon = Icons.Filled.Bolt,
                title = "Automatic Entries",
                subtitle = "Detect from SMS & notifications, recurring transactions",
                onClick = onOpenAutomatic,
            )
            SettingsHubRow(
                icon = Icons.Filled.Category,
                title = "Categories",
                subtitle = "Add, rename, archive or delete",
                onClick = onOpenCategories,
            )
            SettingsHubRow(
                icon = Icons.Filled.Palette,
                title = "Appearance",
                subtitle = "Theme, the screen you open on, and the widget",
                onClick = onOpenAppearance,
            )
            SettingsHubRow(
                icon = Icons.Filled.Backup,
                title = "Backup & Restore",
                subtitle = "Back up, restore and auto-backup",
                onClick = onOpenBackup,
            )
            SettingsHubRow(
                icon = Icons.Filled.Storage,
                title = "Data & Trash",
                subtitle = "Spreadsheet import/export and the trash bin",
                onClick = onOpenData,
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "App lock arrives in an upcoming update.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
