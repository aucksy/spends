package com.spends.app.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.spends.app.R

// Bundled OFL fonts: Manrope for UI text, IBM Plex Mono for (tabular) numerals.
val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

val PlexMono = FontFamily(
    Font(R.font.ibmplexmono_regular, FontWeight.Normal),
    Font(R.font.ibmplexmono_medium, FontWeight.Medium),
    Font(R.font.ibmplexmono_semibold, FontWeight.SemiBold),
)

/**
 * Numeral styles for money. IBM Plex Mono with tabular figures ("tnum") so columns of amounts
 * align across the breakdown. These live outside the Material Typography because money is special.
 */
object Numerals {
    val balanceHero = TextStyle(
        fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum",
    )
    val amountLg = TextStyle(
        fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp, fontFeatureSettings = "tnum",
    )
    val amountRow = TextStyle(
        fontFamily = PlexMono, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum",
    )
    val amountSmall = TextStyle(
        fontFamily = PlexMono, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum",
    )
}

// Material 3 typography mapped to Manrope, sized to the design's UI scale.
val SpendsTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
        headlineLarge = headlineLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold),
        headlineMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.01).em),
        headlineSmall = headlineSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
        titleLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
        titleMedium = titleMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
        titleSmall = titleSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = bodyMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.Normal),
        bodySmall = bodySmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Normal),
        labelLarge = labelLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
        labelMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.04.em),
        labelSmall = labelSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.SemiBold),
    )
}
