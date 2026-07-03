package com.spends.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spends.app.data.db.entity.CategoryEntity

/**
 * The compact, tappable category field shown in a form: a tinted avatar + the selected name (or a
 * placeholder) with a "change" affordance. Tapping opens [CategoryPickerSheet].
 */
@Composable
fun CategoryPickerField(
    selected: CategoryEntity?,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                CategoryAvatar(selected.iconKey, selected.colorHex, size = 40.dp)
                Spacer(Modifier.width(14.dp))
                Text(
                    selected.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                // "Change" only when something is selected — the empty state's search icon already
                // signals the field is tappable, so dropping it there avoids crowding the label (#3).
                Text(
                    "Change",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            } else {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A searchable category chooser as a bottom sheet: a search box plus a grid of large-icon
 * categories. [categories] should already be ordered most-used-first; search filters by name.
 *
 * When [onSplit] is provided, a small "Split" affordance lets the user flip the grid into
 * MULTI-SELECT mode, pick several categories, and confirm — the entry point for splitting one amount
 * across categories (#1). Single-select behaviour is unchanged when the user doesn't enter split mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    categories: List<CategoryEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
    onAddNew: (() -> Unit)? = null,
    onSplit: ((List<Long>) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Local snapshot state so the cursor never jumps while typing.
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim().lowercase()
    val filtered = if (trimmed.isEmpty()) categories else categories.filter { it.name.lowercase().contains(trimmed) }

    // Multi-select "split" mode (only reachable when onSplit != null). Seeds with the already-chosen
    // single category so it carries over into the split.
    var splitting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(selectedId?.let { setOf(it) } ?: emptySet()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (splitting) "Select categories to split" else "Choose category",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (onSplit != null) {
                    if (!splitting) {
                        TextButton(onClick = { splitting = true }) { Text("Split") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(onClick = { splitting = false; picked = emptySet() }) { Text("Cancel") }
                            TextButton(
                                enabled = picked.size >= 2,
                                onClick = { onSplit(picked.toList()) },
                            ) { Text("Split ${picked.size}") }
                        }
                    }
                }
            }
            if (splitting) {
                Text(
                    "Pick 2 or more, then set each amount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search categories") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.size(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // "New" isn't offered while multi-selecting for a split — the user is choosing from existing ones.
                if (onAddNew != null && !splitting) {
                    item(key = "__add_new__") {
                        AddNewCell(onClick = onAddNew)
                    }
                }
                items(filtered, key = { it.id }) { category ->
                    CategoryCell(
                        category = category,
                        selected = if (splitting) category.id in picked else category.id == selectedId,
                        onClick = {
                            if (splitting) {
                                picked = if (category.id in picked) picked - category.id else picked + category.id
                            } else {
                                onSelect(category.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryCell(category: CategoryEntity, selected: Boolean, onClick: () -> Unit) {
    val cellBg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cellBg)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CategoryAvatar(category.iconKey, category.colorHex, size = 56.dp)
        Spacer(Modifier.size(6.dp))
        Text(
            category.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AddNewCell(onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.size(6.dp))
        Text(
            "New",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}
