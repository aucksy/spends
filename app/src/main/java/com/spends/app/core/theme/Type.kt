package com.spends.app.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Lightly tuned Material 3 typography: slightly tighter, heavier display/headline weights for the
// premium feel. Uses the platform default font family (no bundled fonts in Phase 1).
private val default = Typography()

val SpendsTypography = Typography(
    displaySmall = default.displaySmall.copy(fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Default),
    headlineMedium = default.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = default.titleMedium.copy(fontWeight = FontWeight.Medium),
    labelLarge = default.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    // A tabular-feeling large number style for amounts.
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
)
