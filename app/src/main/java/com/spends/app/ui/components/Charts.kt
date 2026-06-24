package com.spends.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background

/** One wedge of the category donut. */
data class DonutSlice(val color: Color, val value: Float)

/**
 * Category donut (comp/charts): a thick ring of proportional wedges with a hollow centre for a
 * label/total. Drawn with Canvas arcs so it scales cleanly and matches the design's conic look.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    ringWidth: Dp = 30.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    center: @Composable () -> Unit = {},
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val total = slices.sumOf { it.value.toDouble() }.toFloat()
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = ringWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            // Track first, so a single tiny slice still reads as part of a ring.
            drawArc(trackColor, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            if (total > 0f) {
                var start = -90f
                slices.forEach { s ->
                    val sweep = s.value / total * 360f
                    drawArc(s.color, start, sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
                    start += sweep
                }
            }
        }
        center()
    }
}

/**
 * Vertical bars (comp/charts · spend over time). The tallest bar is highlighted in primary; the
 * rest use the primary container tint. Equal-width, rounded tops.
 */
@Composable
fun WeeklyBars(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    barsHeight: Dp = 140.dp,
) {
    val rawMax = values.maxOrNull() ?: 0f
    val maxV = rawMax.coerceAtLeast(1f)
    // Only highlight a peak when there's actual spend; an all-zero period highlights nothing.
    val peak = if (rawMax > 0f) values.indexOfFirst { it == rawMax } else -1
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(barsHeight),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            values.forEachIndexed { i, v ->
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    val frac = (v / maxV).coerceIn(0.02f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.66f)
                            .fillMaxHeight(frac)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (i == peak) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            labels.forEachIndexed { i, label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (i == peak) FontWeight.Bold else FontWeight.Medium,
                    color = if (i == peak) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
