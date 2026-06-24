package com.spends.app.core.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// Design tokens — "Calm Minimal" (Spends Design System). Teal accent on warm paper.
// Money + category colours live OUTSIDE the M3 ColorScheme so they stay fixed under
// Material You dynamic recolouring.
// ─────────────────────────────────────────────────────────────────────────────

// M3 color roles — Light
internal val LightPrimary = Color(0xFF0F766E)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFCFEEE9)
internal val LightOnPrimaryContainer = Color(0xFF0B5249)
internal val LightBackground = Color(0xFFFAFAF8)
internal val LightSurface = Color(0xFFFFFFFF)
internal val LightSurfaceVariant = Color(0xFFF2EFE8)
internal val LightOnSurface = Color(0xFF111827)
internal val LightOnSurfaceVariant = Color(0xFF6B7280)
internal val LightOutline = Color(0xFFC9C4B8)
internal val LightOutlineVariant = Color(0xFFECEAE3)

// M3 color roles — Dark
internal val DarkPrimary = Color(0xFF4FC9BD)
internal val DarkOnPrimary = Color(0xFF06231F)
internal val DarkPrimaryContainer = Color(0xFF1C4A45)
internal val DarkOnPrimaryContainer = Color(0xFFA7E0D8)
internal val DarkBackground = Color(0xFF0E1512)
internal val DarkSurface = Color(0xFF16201B)
internal val DarkSurfaceVariant = Color(0xFF243029)
internal val DarkOnSurface = Color(0xFFF3F4F1)
internal val DarkOnSurfaceVariant = Color(0xFF7E8C85)
internal val DarkOutline = Color(0xFF3A463F)
internal val DarkOutlineVariant = Color(0xFF243029)

// Money-semantic roles (fixed). Note: expense is INK, not red — red is reserved for negatives.
internal val IncomeLight = Color(0xFF15803D)
internal val IncomeDark = Color(0xFF5FBF7E)
internal val ExpenseInkLight = Color(0xFF1C1C1A)
internal val ExpenseInkDark = Color(0xFFECEAE3)
internal val NegativeLight = Color(0xFFB91C1C)
internal val NegativeDark = Color(0xFFF08A8A)
internal val TransferLight = Color(0xFF94908A)
internal val TransferDark = Color(0xFF6E6A64)
internal val NonConsumptionLight = Color(0xFF5B5BD6)
internal val NonConsumptionDark = Color(0xFF9D9DF0)
internal val ReviewLight = Color(0xFFB45309)
internal val ReviewDark = Color(0xFFE0A463)
