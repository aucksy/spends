package com.spends.app.core.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.spends.app.domain.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = BrandIndigo,
    secondary = BrandTeal,
    tertiary = Color(0xFF7C6CF0),
)

private val DarkColors = darkColorScheme(
    primary = BrandIndigoDark,
    secondary = BrandTeal,
    tertiary = Color(0xFFB9AEFF),
)

/** Income/expense/transfer colors resolved for the current theme. */
data class SemanticColors(
    val income: Color,
    val expense: Color,
    val transfer: Color,
)

val LocalSemanticColors = staticCompositionLocalOf {
    SemanticColors(IncomeGreenLight, ExpenseRedLight, TransferGrayLight)
}

@Composable
fun SpendsTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val semantic = if (dark) {
        SemanticColors(IncomeGreenDark, ExpenseRedDark, TransferGrayDark)
    } else {
        SemanticColors(IncomeGreenLight, ExpenseRedLight, TransferGrayLight)
    }

    CompositionLocalProvider(LocalSemanticColors provides semantic) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SpendsTypography,
            content = content,
        )
    }
}
