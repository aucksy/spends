package com.spends.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import com.spends.app.data.settings.SettingsState
import com.spends.app.domain.model.DefaultLanding
import com.spends.app.ui.analytics.AnalyticsScreen
import com.spends.app.ui.components.LocalAmountsHidden
import com.spends.app.ui.quickadd.QuickAddSheet
import com.spends.app.ui.transactions.TransactionsScreen
import kotlinx.coroutines.launch

private enum class HomeTab { TRANSACTIONS, ANALYTICS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settings: SettingsState,
    onAddTransaction: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenCategory: (categoryId: Long, name: String, startMillis: Long, endExclusiveMillis: Long) -> Unit,
    openQuickAddSignal: Boolean = false,
    onQuickAddConsumed: () -> Unit = {},
) {
    val initialTab = if (settings.defaultLanding == DefaultLanding.ANALYTICS) HomeTab.ANALYTICS else HomeTab.TRANSACTIONS
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Balances are hidden by default (privacy); the eye in the bottom-left reveals them. Saveable so a
    // reveal survives tab switches/rotation, but a fresh launch starts hidden again.
    var amountsHidden by rememberSaveable { mutableStateOf(true) }
    // The + opens the fast half-screen quick-add sheet (calculator keypad). Editing still uses the full screen.
    var showQuickAdd by remember { mutableStateOf(false) }

    // The home-screen widget (#14) launches the app with this signal — open the quick-add sheet, once.
    androidx.compose.runtime.LaunchedEffect(openQuickAddSignal) {
        if (openQuickAddSignal) {
            showQuickAdd = true
            onQuickAddConsumed()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == HomeTab.TRANSACTIONS,
                    onClick = { tab = HomeTab.TRANSACTIONS },
                    icon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null) },
                    label = { Text("Transactions") },
                )
                NavigationBarItem(
                    selected = tab == HomeTab.ANALYTICS,
                    onClick = { tab = HomeTab.ANALYTICS },
                    icon = { Icon(Icons.Filled.PieChart, contentDescription = null) },
                    label = { Text("Analytics") },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showQuickAdd = true },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add transaction")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        CompositionLocalProvider(LocalAmountsHidden provides amountsHidden) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    HomeTab.TRANSACTIONS -> TransactionsScreen(
                        snackbarHostState = snackbarHostState,
                        onEditTransaction = onEditTransaction,
                        onOpenSettings = onOpenSettings,
                    )
                    HomeTab.ANALYTICS -> AnalyticsScreen(
                        onOpenRecurring = onOpenRecurring,
                        onOpenCategory = onOpenCategory,
                        onOpenSettings = onOpenSettings,
                    )
                }
                // Privacy eye — bottom-left, balancing the + FAB on the right. Same tint as the + FAB
                // (primaryContainer) so the two read as a matched pair (#1).
                SmallFloatingActionButton(
                    onClick = { amountsHidden = !amountsHidden },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                ) {
                    Icon(
                        if (amountsHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = if (amountsHidden) "Show balances" else "Hide balances",
                    )
                }
            }
        }

        if (showQuickAdd) {
            QuickAddSheet(
                onDismiss = { showQuickAdd = false },
                onSaved = { scope.launch { snackbarHostState.showSnackbar("Transaction added") } },
            )
        }
    }
}
