package com.spends.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spends.app.core.category.CategoryIcons
import com.spends.app.core.category.IconAssigner
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.domain.model.CategoryUsage

/**
 * One bottom sheet for both creating and editing a category (#5). It shows a tappable icon (now
 * user-choosable), a name field, and — only when creating — a Spending/Income selector. Tapping the icon
 * swaps the sheet's body to a grouped icon grid (same sheet, no nesting) and back. A hand-picked icon is
 * reported as customized so it sticks (the launch-time auto re-icon then leaves it alone).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryEditorSheet(
    initial: CategoryEntity?,
    onSave: (name: String, usage: CategoryUsage, iconKey: String, iconCustomized: Boolean) -> Unit,
    onDismiss: () -> Unit,
    // When set (creating a category from the transaction flow, #5), the usage is fixed to the
    // transaction's kind and the Spending/Income selector is hidden — you can't make a mismatched one.
    fixedUsage: CategoryUsage? = null,
) {
    val creating = initial == null
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var income by remember {
        mutableStateOf(fixedUsage?.let { it == CategoryUsage.INCOME } ?: (initial?.usage == CategoryUsage.INCOME))
    }
    var iconKey by remember { mutableStateOf(initial?.iconKey ?: IconAssigner.FALLBACK) }
    var customized by remember { mutableStateOf(initial?.iconCustomized ?: false) }
    var picking by remember { mutableStateOf(false) }

    // New categories get their real distinct colour on save; preview with the brand teal so the icon reads.
    val previewColor = initial?.colorHex ?: "#0F766E"

    // While the icon is still auto-managed, follow the typed name — so the preview matches what a plain
    // "Add" would produce. Once the user hand-picks, `customized` is true and this no longer overrides.
    LaunchedEffect(name) {
        if (!customized) iconKey = IconAssigner.keyFor(name)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        if (picking) {
            IconGridBody(
                currentKey = iconKey,
                colorHex = previewColor,
                onBack = { picking = false },
                onPick = { picked -> iconKey = picked; customized = true; picking = false },
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    if (creating) "New category" else "Edit category",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.size(16.dp))

                // Icon row: tappable avatar + "Change". Opens the grid (#5).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { picking = true },
                    ) {
                        CategoryAvatar(iconKey = iconKey, colorHex = previewColor, size = 52.dp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Icon", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "Tap to choose an icon",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { picking = true }) { Text("Change") }
                }
                Spacer(Modifier.size(14.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (creating && fixedUsage == null) {
                    Spacer(Modifier.size(12.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !income,
                            onClick = { income = false },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Spending") }
                        SegmentedButton(
                            selected = income,
                            onClick = { income = true },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("Income") }
                    }
                }

                Spacer(Modifier.size(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                name.trim(),
                                fixedUsage ?: (if (income) CategoryUsage.INCOME else CategoryUsage.EXPENSE),
                                iconKey,
                                customized,
                            )
                        },
                        enabled = name.isNotBlank(),
                    ) { Text(if (creating) "Add" else "Save") }
                }
                Spacer(Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun IconGridBody(
    currentKey: String,
    colorHex: String,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Choose an icon", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CategoryIcons.groups.forEach { group ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "hdr-${group.title}") {
                    Text(
                        group.title.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                items(group.keys, key = { "${group.title}-$it" }) { key ->
                    IconTile(
                        iconKey = key,
                        colorHex = colorHex,
                        selected = key == currentKey,
                        onClick = { onPick(key) },
                    )
                }
            }
        }
        Spacer(Modifier.size(16.dp))
    }
}

@Composable
private fun IconTile(iconKey: String, colorHex: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CategoryAvatar(iconKey = iconKey, colorHex = colorHex, size = 44.dp)
    }
}
