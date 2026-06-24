package com.spends.app.core.category

/**
 * Deterministically assigns each category a **distinct** accent color from a curated palette
 * (PRD §4.4) — no color picker. A stable hash of the name selects a palette slot; linear probing
 * avoids collisions against already-assigned colors so every category in a set looks distinct.
 *
 * The palette (Tailwind 500-ish hues) is cohesive and legible in both light and dark themes.
 */
object ColorAssigner {

    val PALETTE: List<String> = listOf(
        "#EF4444", // red
        "#F97316", // orange
        "#F59E0B", // amber
        "#EAB308", // yellow
        "#84CC16", // lime
        "#22C55E", // green
        "#10B981", // emerald
        "#14B8A6", // teal
        "#06B6D4", // cyan
        "#0EA5E9", // sky
        "#3B82F6", // blue
        "#6366F1", // indigo
        "#8B5CF6", // violet
        "#A855F7", // purple
        "#D946EF", // fuchsia
        "#EC4899", // pink
        "#F43F5E", // rose
        "#78716C", // stone
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
}
