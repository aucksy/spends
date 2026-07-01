package com.spends.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.view.HapticFeedbackConstants
import kotlin.math.abs

/**
 * A vertical number wheel (iOS-style spinner). The item nearest the centre snaps under a tinted
 * selection band and is reported via [onValueChange]. Fixed height, so it's safe inside a
 * verticalScroll parent (e.g. onboarding).
 */
@Composable
fun NumberWheelPicker(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 48.dp,
    visibleCount: Int = 5,
) {
    val items = remember(range) { range.toList() }
    val initialIndex = (value - range.first).coerceIn(0, items.lastIndex)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val fling = rememberSnapFlingBehavior(lazyListState = listState) // default SnapPosition.Center
    val wheelHeight = itemHeight * visibleCount

    // The item whose centre is closest to the viewport centre.
    val centeredIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) {
                initialIndex
            } else {
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                visible.minByOrNull { abs((it.offset + it.size / 2f) - center) }?.index ?: initialIndex
            }
        }
    }

    // A felt "tick" each time a new number snaps under the band (#12) — the detent feel of a real wheel.
    // Seeded to the starting index so the initial layout pass doesn't buzz.
    val view = LocalView.current
    var lastBuzzedIndex by remember { mutableStateOf(initialIndex) }
    LaunchedEffect(centeredIndex) {
        if (centeredIndex != lastBuzzedIndex) {
            lastBuzzedIndex = centeredIndex
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        val v = range.first + centeredIndex.coerceIn(0, items.lastIndex)
        if (v != value) onValueChange(v)
    }

    Box(modifier = modifier.height(wheelHeight), contentAlignment = Alignment.Center) {
        // Centre selection band.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
        )
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = (wheelHeight - itemHeight) / 2),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(items, key = { it }) { item ->
                val selected = (item - range.first) == centeredIndex
                Box(modifier = Modifier.height(itemHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = item.toString(),
                        style = if (selected) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                    )
                }
            }
        }
    }
}
