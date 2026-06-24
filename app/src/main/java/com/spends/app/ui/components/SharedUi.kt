package com.spends.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spends.app.core.category.CategoryIcons
import com.spends.app.core.category.ColorAssigner
import com.spends.app.core.money.Money
import com.spends.app.core.theme.LocalSemanticColors
import com.spends.app.core.theme.Numerals
import kotlin.math.abs

/** Parse a "#RRGGBB" hex into a Compose Color, falling back to neutral stone on bad input. */
fun parseHexColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    Color(0xFF57534E)
}

/**
 * A rounded-square category avatar (Design System: radius 12, fill at ~14% alpha, coloured icon).
 * In dark mode the hue lifts to its dark-fill variant so it stays legible.
 */
@Composable
fun CategoryAvatar(
    iconKey: String,
    colorHex: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
) {
    val dark = LocalSemanticColors.current.dark
    val base = parseHexColor(if (dark) ColorAssigner.darkVariant(colorHex) else colorHex)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(base.copy(alpha = if (dark) 0.22f else 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = CategoryIcons.vectorFor(iconKey),
            contentDescription = null,
            tint = base,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/**
 * A rupee value that smoothly tweens between updates (Design System: counter roll). The resting
 * value is exact — float progress only drives the in-flight interpolation, never the final amount.
 * Defaults to the tabular mono row style.
 */
@Composable
fun AnimatedRupee(
    minor: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = Numerals.amountRow,
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
        progress.animateTo(1f, animationSpec = tween(durationMillis = 600))
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

/**
 * A single-line text that shrinks its font size just enough to fit the available width, so big
 * amounts (₹6,57,011.00) never truncate with an ellipsis. Measures once per width/text with a
 * [TextMeasurer] and scales [style] down to at most [minScale]. Never grows past the base size.
 */
@Composable
fun AutoSizeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minScale: Float = 0.45f,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxW = constraints.maxWidth
        var resolved = style
        if (maxW in 1 until Int.MAX_VALUE && style.fontSize.isSp) {
            val measured = measurer.measure(text = text, style = style, maxLines = 1, softWrap = false)
            val w = measured.size.width
            if (w > maxW) {
                val scale = (maxW.toFloat() / w).coerceIn(minScale, 1f)
                resolved = style.copy(
                    fontSize = (style.fontSize.value * scale).sp,
                    lineHeight = if (style.lineHeight.isSp) (style.lineHeight.value * scale).sp else style.lineHeight,
                )
            }
        }
        Text(text = text, style = resolved, color = color, maxLines = 1, softWrap = false)
    }
}

/** Auto-sizing rupee amount (no count animation — sizing wins over the roll for headline figures). */
@Composable
fun AutoSizeRupee(
    minor: Long,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    withSign: Boolean = false,
    minScale: Float = 0.45f,
) {
    val text = if (withSign) {
        val sign = if (minor > 0) "+" else if (minor < 0) "-" else ""
        sign + Money.formatRupees(abs(minor))
    } else {
        Money.formatRupees(minor)
    }
    AutoSizeText(text = text, style = style, color = color, modifier = modifier, minScale = minScale)
}
