package com.spends.app.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
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
    onOpenBreakdown: () -> Unit = {},
    openQuickAddSignal: Boolean = false,
    onQuickAddConsumed: () -> Unit = {},
) {
    val initialTab = if (settings.defaultLanding == DefaultLanding.ANALYTICS) HomeTab.ANALYTICS else HomeTab.TRANSACTIONS
    // Persist the tab by NAME with a guarded restore: a saved-state bundle from an older build could still
    // hold the removed "CARDS" value (#3), and letting rememberSaveable deserialize that enum would crash on
    // relaunch (Enum.valueOf throws inside Bundle unmarshalling). Coerce anything unknown back to initialTab.
    val tabSaver = remember {
        Saver<HomeTab, String>(save = { it.name }, restore = { runCatching { HomeTab.valueOf(it) }.getOrDefault(initialTab) })
    }
    var tab by rememberSaveable(stateSaver = tabSaver) { mutableStateOf(initialTab) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    // Balances are hidden by default (privacy); the eye in the bottom-left reveals them. Saveable so a
    // reveal survives tab switches/rotation, but a fresh launch starts hidden again.
    var amountsHidden by rememberSaveable { mutableStateOf(true) }
    // Auto-hide revealed balances after 15s IN-APP (the home-screen widget uses ~5s) so figures don't
    // linger but you have time to read them. Keyed on amountsHidden — revealing starts the timer; hiding
    // manually (or this auto-hide firing) cancels it by re-keying the effect.
    androidx.compose.runtime.LaunchedEffect(amountsHidden) {
        if (!amountsHidden) {
            kotlinx.coroutines.delay(15_000)
            amountsHidden = true
        }
    }
    // The + opens the fast half-screen quick-add sheet (calculator keypad). Editing still uses the full screen.
    var showQuickAdd by remember { mutableStateOf(false) }
    // Search is driven from the bottom bar (#5) — lifted here so the "Search" tab can toggle it and
    // reflect its active state; the Transactions screen owns the actual query text. Cards/Banks now live
    // in Settings (#3), so the bottom nav is a fixed Transactions | Analytics | Search regardless of Smart
    // Cycle. Search only makes sense on the timeline — clear it if we're on Analytics.
    var searchActive by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(tab) {
        if (tab != HomeTab.TRANSACTIONS && searchActive) searchActive = false
    }

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
                    selected = tab == HomeTab.TRANSACTIONS && !searchActive,
                    onClick = { tab = HomeTab.TRANSACTIONS; searchActive = false },
                    icon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null) },
                    label = { Text("Transactions") },
                )
                NavigationBarItem(
                    selected = tab == HomeTab.ANALYTICS && !searchActive,
                    onClick = { tab = HomeTab.ANALYTICS; searchActive = false },
                    icon = { Icon(Icons.Filled.PieChart, contentDescription = null) },
                    label = { Text("Analytics") },
                )
                // Search is a permanent bottom tab now (#3): from Analytics it jumps to the timeline and opens
                // search; on the timeline it toggles. Highlights while searching.
                NavigationBarItem(
                    selected = searchActive,
                    onClick = {
                        if (tab != HomeTab.TRANSACTIONS) {
                            tab = HomeTab.TRANSACTIONS
                            searchActive = true
                        } else {
                            searchActive = !searchActive
                        }
                    },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("Search") },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); showQuickAdd = true },
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
                        searchActive = searchActive,
                        onSearchActiveChange = { searchActive = it },
                    )
                    HomeTab.ANALYTICS -> AnalyticsScreen(
                        onOpenRecurring = onOpenRecurring,
                        onOpenCategory = onOpenCategory,
                        onOpenSettings = onOpenSettings,
                        onOpenBreakdown = onOpenBreakdown,
                    )
                }
                // Privacy eye — bottom-left, balancing the + FAB on the right. Same tint as the + FAB
                // (primaryContainer) so the two read as a matched pair (#1).
                SmallFloatingActionButton(
                    onClick = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); amountsHidden = !amountsHidden },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    // 42dp + 12dp corners + flat so it sits cleanly over a transaction row's category avatar (#8).
                    shape = RoundedCornerShape(12.dp),
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp,
                        focusedElevation = 0.dp,
                        hoveredElevation = 0.dp,
                    ),
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).size(42.dp),
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
