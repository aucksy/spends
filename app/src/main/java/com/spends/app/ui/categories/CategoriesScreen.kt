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
import com.spends.app.ui.components.CategoryAvatar
import com.spends.app.ui.components.CategoryEditorSheet
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
    var editTarget by remember { mutableStateOf<CategoryEntity?>(null) }
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
            categoryRows(state.expense, onEdit = { editTarget = it }, onDelete = { deleteTarget = it })

            section("Income", state.income)
            categoryRows(state.income, onEdit = { editTarget = it }, onDelete = { deleteTarget = it })

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
        CategoryEditorSheet(
            initial = null,
            onSave = { name, usage, iconKey, customized ->
                viewModel.add(name, usage, iconKey = if (customized) iconKey else null)
                showAdd = false
            },
            onDismiss = { showAdd = false },
        )
    }
    editTarget?.let { target ->
        CategoryEditorSheet(
            initial = target,
            onSave = { name, _, iconKey, customized ->
                viewModel.saveEdits(target.id, name, iconKey, customized)
                editTarget = null
            },
            onDismiss = { editTarget = null },
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
    onEdit: (CategoryEntity) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
) {
    items(items, key = { it.id }) { cat ->
        CategoryRow(cat, trailing = {
            IconButton(onClick = { onEdit(cat) }) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit category")
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

// Add + edit both go through CategoryEditorSheet (com.spends.app.ui.components) now — it carries the
// name, the type selector (add only), and the new icon picker (#5).
