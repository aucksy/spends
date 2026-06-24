package com.spends.app.core

import com.google.common.truth.Truth.assertThat
import com.spends.app.core.category.ColorAssigner
import com.spends.app.core.category.IconAssigner
import org.junit.Test

class AssignerTest {

    @Test fun icon_keywords_map_as_specified() {
        assertThat(IconAssigner.keyFor("Food")).isEqualTo("food")
        assertThat(IconAssigner.keyFor("Swiggy dinner")).isEqualTo("food")
        assertThat(IconAssigner.keyFor("Dog Food")).isEqualTo("pet") // pet beats food
        assertThat(IconAssigner.keyFor("Guitar")).isEqualTo("music")
        assertThat(IconAssigner.keyFor("Fitness")).isEqualTo("fitness")
        assertThat(IconAssigner.keyFor("Fuel")).isEqualTo("fuel")
        assertThat(IconAssigner.keyFor("Shopping")).isEqualTo("shopping")
        assertThat(IconAssigner.keyFor("Investments")).isEqualTo("investments")
        assertThat(IconAssigner.keyFor("Loan/EMI")).isEqualTo("loan_emi")
        assertThat(IconAssigner.keyFor("Groceries")).isEqualTo("grocery")
        assertThat(IconAssigner.keyFor("Quantum Flux")).isEqualTo(IconAssigner.FALLBACK)
        assertThat(IconAssigner.keyFor("")).isEqualTo(IconAssigner.FALLBACK)
    }

    @Test fun palette_entries_are_valid_distinct_hex() {
        val hexRegex = Regex("^#[0-9A-Fa-f]{6}$")
        ColorAssigner.PALETTE.forEach { assertThat(it).matches(hexRegex.pattern) }
        assertThat(ColorAssigner.PALETTE.toSet()).hasSize(ColorAssigner.PALETTE.size)
    }

    @Test fun color_assignment_is_deterministic() {
        assertThat(ColorAssigner.colorFor("Food")).isEqualTo(ColorAssigner.colorFor("Food"))
        assertThat(ColorAssigner.colorFor("Food")).isIn(ColorAssigner.PALETTE)
    }

    @Test fun colors_are_distinct_across_a_seed_set() {
        val names = listOf(
            "Food", "Groceries", "Shopping", "Entertainment", "Health", "Fitness",
            "Travel", "Fuel", "Utilities", "Bills", "Rent", "Subscriptions",
            "Personal Care", "Education", "Investments", "Loan/EMI", "Gifts", "Transport",
        )
        val taken = mutableSetOf<String>()
        val assigned = names.map { name ->
            ColorAssigner.colorFor(name, taken).also { taken.add(it) }
        }
        // Up to PALETTE.size names get fully distinct colors.
        assertThat(assigned.toSet()).hasSize(names.size)
    }

    @Test fun collision_avoidance_probes_to_a_free_slot() {
        val base = ColorAssigner.colorFor("Food")
        val next = ColorAssigner.colorFor("Food", taken = setOf(base))
        assertThat(next).isNotEqualTo(base)
        assertThat(next).isIn(ColorAssigner.PALETTE)
    }
}
