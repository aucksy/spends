package com.spends.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.spends.app.data.settings.SettingsState
import com.spends.app.domain.model.DefaultLanding
import com.spends.app.ui.analytics.AnalyticsScreen
import com.spends.app.ui.transactions.TransactionsScreen

private enum class HomeTab { TRANSACTIONS, ANALYTICS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settings: SettingsState,
    onAddTransaction: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val initialTab = if (settings.defaultLanding == DefaultLanding.ANALYTICS) HomeTab.ANALYTICS else HomeTab.TRANSACTIONS
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spends") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
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
            FloatingActionButton(onClick = onAddTransaction) {
                Icon(Icons.Filled.Add, contentDescription = "Add transaction")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                HomeTab.TRANSACTIONS -> TransactionsScreen(
                    snackbarHostState = snackbarHostState,
                    onEditTransaction = onEditTransaction,
                )
                HomeTab.ANALYTICS -> AnalyticsScreen()
            }
        }
    }
}
