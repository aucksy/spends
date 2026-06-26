package com.spends.app.core.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.spends.app.domain.model.ThemeMode
import kotlinx.coroutines.delay
import java.time.LocalTime

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightPrimary,
    onSecondary = LightOnPrimary,
    secondaryContainer = LightPrimaryContainer,
    onSecondaryContainer = LightOnPrimaryContainer,
    tertiary = NonConsumptionLight,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = NegativeLight,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkPrimary,
    onSecondary = DarkOnPrimary,
    secondaryContainer = DarkPrimaryContainer,
    onSecondaryContainer = DarkOnPrimaryContainer,
    tertiary = NonConsumptionDark,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = NegativeDark,
    onError = Color(0xFF3A0A0A),
)

/** Money-semantic colours resolved for the current theme (kept outside the M3 scheme). */
data class SemanticColors(
    val income: Color,
    val expense: Color,
    val negative: Color,
    val negativeContainer: Color,
    val transfer: Color,
    val nonConsumption: Color,
    val review: Color,
    val dark: Boolean,
)

private val LightSemantic = SemanticColors(
    income = IncomeLight, expense = ExpenseInkLight, negative = NegativeLight,
    negativeContainer = NegativeContainerLight,
    transfer = TransferLight, nonConsumption = NonConsumptionLight, review = ReviewLight, dark = false,
)
private val DarkSemantic = SemanticColors(
    income = IncomeDark, expense = ExpenseInkDark, negative = NegativeDark,
    negativeContainer = NegativeContainerDark,
    transfer = TransferDark, nonConsumption = NonConsumptionDark, review = ReviewDark, dark = true,
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemantic }

/**
 * The app theme. We always use the hand-tuned design-system palette (no Material You / dynamic colour —
 * the user prefers the brand green and wallpaper extraction never matched it well). [ThemeMode.AUTO]
 * flips to dark inside the user's daily window ([autoDarkStartMinute], [autoDarkEndMinute) minutes-of-day).
 */
@Composable
fun SpendsTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    autoDarkStartMinute: Int = 20 * 60,
    autoDarkEndMinute: Int = 6 * 60,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val autoDark = if (themeMode == ThemeMode.AUTO) {
        rememberAutoDark(autoDarkStartMinute, autoDarkEndMinute)
    } else {
        false
    }
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.AUTO -> autoDark
    }
    val colorScheme = if (dark) DarkColors else LightColors

    // Drive the status/navigation-bar ICON colours off the EFFECTIVE theme (so the forced-light
    // onboarding gets dark, visible icons on a system-dark device — not white-on-white). enableEdgeToEdge
    // only set them once off the system uiMode, which is wrong here.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalSemanticColors provides if (dark) DarkSemantic else LightSemantic) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SpendsTypography,
            shapes = SpendsShapes,
            content = content,
        )
    }
}

/** Recomputes "is it dark now" every 30s so an open app flips at the window boundary, not just on launch. */
@Composable
private fun rememberAutoDark(startMinute: Int, endMinute: Int): Boolean {
    var nowMinute by remember { mutableIntStateOf(currentMinuteOfDay()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMinute = currentMinuteOfDay()
            delay(30_000L)
        }
    }
    return isWithinDarkWindow(nowMinute, startMinute, endMinute)
}

private fun currentMinuteOfDay(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

/** True if [nowMinute] is inside [start, end); handles windows that wrap past midnight (start > end). */
internal fun isWithinDarkWindow(nowMinute: Int, start: Int, end: Int): Boolean = when {
    start == end -> false // empty window → never auto-dark
    start < end -> nowMinute in start until end
    else -> nowMinute >= start || nowMinute < end // wraps midnight, e.g. 20:00 → 06:00
}
