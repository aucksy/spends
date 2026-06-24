package com.spends.app.core.category

/**
 * Deterministically assigns each category a **distinct** accent color from the design system's
 * fixed 16-hue categorical palette (PRD §4.4, Spends Design System) — no color picker. The palette
 * is colour-blind-safe ordered and AA on both surfaces. A stable hash selects a slot; linear
 * probing avoids collisions. Dark-mode fills lift the same hue (~+12% lightness) so charts never
 * shift under Material You.
 *
 * Pure Kotlin (no Android deps) so the assignment stays unit-testable.
 */
object ColorAssigner {

    /** cat/01…16 light fills, in the design's fixed order. */
    val PALETTE: List<String> = listOf(
        "#C2410C", // 01 food / dining
        "#15803D", // 02 groceries
        "#1D4ED8", // 03 transport
        "#9333EA", // 04 shopping
        "#0E7490", // 05 bills & utils
        "#DB2777", // 06 entertainment
        "#0F766E", // 07 health
        "#B45309", // 08 travel
        "#6D28D9", // 09 rent & home
        "#2563EB", // 10 education
        "#7C3AED", // 11 subscriptions
        "#A16207", // 12 fuel
        "#5B5BD6", // 13 investments
        "#475569", // 14 cash / atm
        "#BE185D", // 15 gifts & donate
        "#57534E", // 16 other
    )

    /** Exact dark-mode fills for the 16 palette hues. */
    private val DARK_BY_LIGHT: Map<String, String> = mapOf(
        "#C2410C" to "#E26A3A", "#15803D" to "#4FB477", "#1D4ED8" to "#4F8BF0",
        "#9333EA" to "#B968E8", "#0E7490" to "#3AA8C0", "#DB2777" to "#EE6BA8",
        "#0F766E" to "#4FC9BD", "#B45309" to "#E0A463", "#6D28D9" to "#9D7BEE",
        "#2563EB" to "#5B95F0", "#7C3AED" to "#A579F0", "#A16207" to "#C99A45",
        "#5B5BD6" to "#9D9DF0", "#475569" to "#8595A8", "#BE185D" to "#E8568F",
        "#57534E" to "#9C958C",
    )

    /** A small, stable FNV-1a hash so slot selection never depends on JVM String.hashCode quirks. */
    fun stableHash(input: String): Int {
        var hash = -0x7ee3623b // 2166136261 as Int
        for (ch in input.lowercase()) {
            hash = hash xor ch.code
            hash *= 0x01000193 // FNV prime
        }
        return hash
    }

    /**
     * Pick a distinct palette color for [name], avoiding any hex already in [taken]
     * (case-insensitive). Falls back to the base slot if the whole palette is exhausted.
     */
    fun colorFor(name: String, taken: Set<String> = emptySet()): String {
        val takenUpper = taken.map { it.uppercase() }.toSet()
        val base = (stableHash(name) % PALETTE.size + PALETTE.size) % PALETTE.size
        for (offset in PALETTE.indices) {
            val candidate = PALETTE[(base + offset) % PALETTE.size]
            if (candidate.uppercase() !in takenUpper) return candidate
        }
        return PALETTE[base]
    }

    /** The dark-mode fill for a light category hex: exact for palette hues, else a lightness lift. */
    fun darkVariant(lightHex: String): String =
        DARK_BY_LIGHT[lightHex.uppercase()] ?: lighten(lightHex, 0.16f)

    private fun lighten(hex: String, amount: Float): String {
        val h = hex.removePrefix("#")
        if (h.length != 6) return hex
        return try {
            val r = h.substring(0, 2).toInt(16)
            val g = h.substring(2, 4).toInt(16)
            val b = h.substring(4, 6).toInt(16)
            fun lift(c: Int) = (c + (255 - c) * amount).toInt().coerceIn(0, 255)
            "#%02X%02X%02X".format(lift(r), lift(g), lift(b))
        } catch (e: NumberFormatException) {
            hex
        }
    }
}
