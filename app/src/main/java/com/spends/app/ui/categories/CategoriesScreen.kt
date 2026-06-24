package com.spends.app.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.repo.CategoryDeleteResult
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.ui.components.CategoryAvatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    viewModel: CategoriesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showAdd by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<CategoryEntity?>(null) }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Categories") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add category")
            }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            section("Spending", state.expense)
            categoryRows(state.expense, onRename = { renameTarget = it }, onDelete = { deleteTarget = it })

            section("Income", state.income)
            categoryRows(state.income, onRename = { renameTarget = it }, onDelete = { deleteTarget = it })

            if (state.archived.isNotEmpty()) {
                section("Archived", state.archived)
                items(state.archived, key = { "arch-${it.id}" }) { cat ->
                    CategoryRow(cat, trailing = {
                        IconButton(onClick = { viewModel.restore(cat.id) }) {
                            Icon(Icons.Filled.Unarchive, contentDescription = "Restore")
                        }
                    })
                }
            }
        }
    }

    if (showAdd) {
        AddCategoryDialog(
            onConfirm = { name, usage -> viewModel.add(name, usage); showAdd = false },
            onDismiss = { showAdd = false },
        )
    }
    renameTarget?.let { target ->
        RenameDialog(
            initial = target.name,
            onConfirm = { newName -> viewModel.rename(target.id, newName); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${target.name}?") },
            text = { Text("If it's used by existing transactions it will be archived instead, so your history stays intact.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.deleteOrArchive(target.id) { result ->
                        scope.launch {
                            snackbar.showSnackbar(
                                if (result == CategoryDeleteResult.ARCHIVED) {
                                    "Archived (still used by transactions)"
                                } else {
                                    "Deleted"
                                },
                            )
                        }
                    }
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, items: List<CategoryEntity>) {
    if (items.isEmpty()) return
    item(key = "section-$title") {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.categoryRows(
    items: List<CategoryEntity>,
    onRename: (CategoryEntity) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
) {
    items(items, key = { it.id }) { cat ->
        CategoryRow(cat, trailing = {
            IconButton(onClick = { onRename(cat) }) {
                Icon(Icons.Filled.Edit, contentDescription = "Rename")
            }
            IconButton(onClick = { onDelete(cat) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        })
    }
}

@Composable
private fun CategoryRow(cat: CategoryEntity, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(cat.iconKey, cat.colorHex)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(cat.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (cat.excludeFromSpend) {
                Text(
                    "Excluded from spend charts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row { trailing() }
    }
}

@Composable
private fun AddCategoryDialog(onConfirm: (String, CategoryUsage) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var income by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New category") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Spacer(Modifier.size(12.dp))
                androidx.compose.material3.SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.SegmentedButton(
                        selected = !income,
                        onClick = { income = false },
                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Spending") }
                    androidx.compose.material3.SegmentedButton(
                        selected = income,
                        onClick = { income = true },
                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Income") }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(name, if (income) CategoryUsage.INCOME else CategoryUsage.EXPENSE) },
                enabled = name.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename category") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
