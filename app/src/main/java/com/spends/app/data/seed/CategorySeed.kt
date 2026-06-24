package com.spends.app.data.seed

import com.spends.app.core.category.ColorAssigner
import com.spends.app.core.category.IconAssigner
import com.spends.app.domain.model.CategoryUsage

/**
 * Prebuilt categories (PRD §4.4). Icons come from [IconAssigner], colors from [ColorAssigner]
 * (assigned in order so they stay distinct across the whole set). Investments and Loan/EMI default
 * to excludeFromSpend. Income gets its own short, relevant set so the income picker isn't cluttered
 * with spend categories.
 */
object CategorySeed {

    data class Seed(val name: String, val excludeFromSpend: Boolean = false)

    private val expenseSeeds: List<Seed> = listOf(
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

    private val incomeSeeds: List<String> = listOf(
        "Salary",
        "Business",
        "Refund",
        "Interest",
        "Investment Returns",
        "Cashback",
        "Gift Received",
        "Other Income",
    )

    data class SeedRow(
        val name: String,
        val iconKey: String,
        val colorHex: String,
        val excludeFromSpend: Boolean,
        val sortOrder: Int,
        val usage: CategoryUsage,
    )

    fun expenseRows(): List<SeedRow> {
        val taken = mutableSetOf<String>()
        return expenseSeeds.mapIndexed { index, seed ->
            SeedRow(
                name = seed.name,
                iconKey = IconAssigner.keyFor(seed.name),
                colorHex = ColorAssigner.colorFor(seed.name, taken).also { taken.add(it) },
                excludeFromSpend = seed.excludeFromSpend,
                sortOrder = index,
                usage = CategoryUsage.EXPENSE,
            )
        }
    }

    /** Income rows, colored to avoid the expense palette so the two sets look distinct. */
    fun incomeRows(): List<SeedRow> {
        val taken = expenseRows().map { it.colorHex }.toMutableSet()
        return incomeSeeds.mapIndexed { index, name ->
            SeedRow(
                name = name,
                iconKey = IconAssigner.keyFor(name),
                colorHex = ColorAssigner.colorFor(name, taken).also { taken.add(it) },
                excludeFromSpend = false,
                sortOrder = 100 + index,
                usage = CategoryUsage.INCOME,
            )
        }
    }

    fun allRows(): List<SeedRow> = expenseRows() + incomeRows()
}
