package com.spends.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spends.app.core.category.CategoryIcons
import com.spends.app.core.money.Money
import kotlin.math.abs

/** Parse a "#RRGGBB" hex into a Compose Color, falling back to neutral stone on bad input. */
fun parseHexColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    Color(0xFF78716C)
}

/** A rounded category avatar: the category icon tinted with its color over a soft tint. */
@Composable
fun CategoryAvatar(
    iconKey: String,
    colorHex: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val color = parseHexColor(colorHex)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = CategoryIcons.vectorFor(iconKey),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/**
 * A rupee value that smoothly tweens between updates (PRD's "animated counters"). The resting
 * value is exact — the float progress only drives the in-flight interpolation, never the final
 * displayed amount, so no precision is lost on money.
 */
@Composable
fun AnimatedRupee(
    minor: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineSmall,
    color: Color = Color.Unspecified,
    withSign: Boolean = false,
    withSymbol: Boolean = true,
) {
    val start = remember { mutableLongStateOf(minor) }
    val end = remember { mutableLongStateOf(minor) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(minor) {
        val current = interpolate(start.longValue, end.longValue, progress.value)
        start.longValue = current
        end.longValue = minor
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(durationMillis = 450))
    }

    val shown = interpolate(start.longValue, end.longValue, progress.value)
    val text = if (withSign) {
        val sign = if (shown > 0) "+" else if (shown < 0) "-" else ""
        sign + Money.formatRupees(abs(shown), withSymbol = withSymbol)
    } else {
        Money.formatRupees(shown, withSymbol = withSymbol)
    }
    Text(text = text, style = style, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun interpolate(from: Long, to: Long, fraction: Float): Long =
    if (fraction >= 1f) to else from + ((to - from).toDouble() * fraction).toLong()
