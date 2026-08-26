package com.spends.app.ui.analytics

import com.google.common.truth.Truth.assertThat
import com.spends.app.domain.model.TxnKind
import org.junit.Test

/**
 * The income/spending lens.
 *
 * Small surface, but the failure it guards against is silent and expensive: a lens that returns the
 * expense list while the header says "Income by category" is a chart that lies without ever crashing.
 * Nothing on screen would look wrong, so an assertion is the only thing that would catch a swap.
 */
class AnalyticsLensTest {

    private fun slice(id: Long, name: String, amount: Long) =
        CategorySlice(categoryId = id, name = name, colorHex = "#111111", iconKey = "tag", amountMinor = amount, percent = 50)

    private val state = AnalyticsUiState(
        loading = false,
        expenseMinor = 300_00,
        incomeMinor = 900_00,
        categorisedSpendMinor = 250_00,
        categories = listOf(slice(1, "Food", 250_00)),
        categorisedIncomeMinor = 900_00,
        incomeCategories = listOf(slice(2, "Salary", 900_00)),
        weekly = listOf(10f, 20f),
        incomeWeekly = listOf(30f, 40f),
    )

    @Test fun each_lens_returns_its_own_slices() {
        assertThat(state.slicesFor(Lens.SPENDING).map { it.name }).containsExactly("Food")
        assertThat(state.slicesFor(Lens.INCOME).map { it.name }).containsExactly("Salary")
    }

    @Test fun each_lens_returns_its_own_categorised_total() {
        assertThat(state.categorisedFor(Lens.SPENDING)).isEqualTo(250_00)
        assertThat(state.categorisedFor(Lens.INCOME)).isEqualTo(900_00)
    }

    @Test fun each_lens_returns_its_own_bars() {
        assertThat(state.barsFor(Lens.SPENDING)).containsExactly(10f, 20f).inOrder()
        assertThat(state.barsFor(Lens.INCOME)).containsExactly(30f, 40f).inOrder()
    }

    @Test fun each_lens_returns_its_own_headline_total() {
        assertThat(state.totalFor(Lens.SPENDING)).isEqualTo(300_00)
        assertThat(state.totalFor(Lens.INCOME)).isEqualTo(900_00)
    }

    @Test fun the_donut_centre_reconciles_with_its_own_wedges() {
        // The centre figure is the CATEGORISED total, so it must equal the sum of what is drawn — for
        // income exactly as for spending. An income total that quietly used the uncategorised headline
        // would leave the donut adding up to something other than the number in the middle of it.
        Lens.entries.forEach { lens ->
            assertThat(state.categorisedFor(lens)).isEqualTo(state.slicesFor(lens).sumOf { it.amountMinor })
        }
    }

    @Test fun a_lens_maps_to_the_transaction_kind_it_counts() {
        assertThat(Lens.SPENDING.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(Lens.INCOME.kind).isEqualTo(TxnKind.INCOME)
    }

    @Test fun both_lenses_are_offered_and_spending_stays_the_default() {
        // The toggle renders Lens.entries in order and the screen opens on the first one; income arriving
        // as the default would silently change what every existing user sees on launch.
        assertThat(Lens.entries.map { it.label }).containsExactly("Spending", "Income").inOrder()
    }

    @Test fun an_empty_period_is_empty_under_both_lenses() {
        val empty = AnalyticsUiState(loading = false)
        assertThat(empty.isEmpty).isTrue()
        Lens.entries.forEach { lens ->
            assertThat(empty.slicesFor(lens)).isEmpty()
            assertThat(empty.categorisedFor(lens)).isEqualTo(0)
        }
    }

    @Test fun income_only_period_is_not_reported_as_empty() {
        // A month with income and no spending must still chart: treating it as empty would hide the very
        // thing the income view exists to show.
        val incomeOnly = AnalyticsUiState(
            loading = false,
            incomeMinor = 900_00,
            categorisedIncomeMinor = 900_00,
            incomeCategories = listOf(slice(2, "Salary", 900_00)),
        )
        assertThat(incomeOnly.isEmpty).isFalse()
        assertThat(incomeOnly.slicesFor(Lens.INCOME)).hasSize(1)
    }
}
