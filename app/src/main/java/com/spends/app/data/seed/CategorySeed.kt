package com.spends.app.data.seed

import com.spends.app.core.category.ColorAssigner
import com.spends.app.core.category.IconAssigner

/**
 * The prebuilt categories (PRD §4.4). Icons come from [IconAssigner], colors from [ColorAssigner]
 * (assigned in order so they stay distinct). Investments and Loan/EMI default to excludeFromSpend.
 */
object CategorySeed {

    data class Seed(val name: String, val excludeFromSpend: Boolean = false)

    private val defaults: List<Seed> = listOf(
        Seed("Food"),
        Seed("Groceries"),
        Seed("Shopping"),
        Seed("Entertainment"),
        Seed("Health"),
        Seed("Fitness"),
        Seed("Travel"),
        Seed("Fuel"),
        Seed("Utilities"),
        Seed("Bills"),
        Seed("Rent"),
        Seed("Subscriptions"),
        Seed("Personal Care"),
        Seed("Education"),
        Seed("Investments", excludeFromSpend = true),
        Seed("Loan/EMI", excludeFromSpend = true),
        Seed("Gifts"),
        Seed("Transport"),
        Seed("Other"),
    )

    data class SeedRow(
        val name: String,
        val iconKey: String,
        val colorHex: String,
        val excludeFromSpend: Boolean,
        val sortOrder: Int,
    )

    /** Resolve the prebuilt categories with distinct icon/color assignments. */
    fun rows(): List<SeedRow> {
        val taken = mutableSetOf<String>()
        return defaults.mapIndexed { index, seed ->
            val icon = IconAssigner.keyFor(seed.name)
            val color = ColorAssigner.colorFor(seed.name, taken).also { taken.add(it) }
            SeedRow(seed.name, icon, color, seed.excludeFromSpend, index)
        }
    }
}
