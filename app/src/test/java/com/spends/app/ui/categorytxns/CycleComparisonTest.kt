package com.spends.app.ui.categorytxns

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The sentence the category screen leads with. Every branch is checked, because this is the one place
 * where a wrong word makes a real figure about real money read as the opposite of what it is.
 */
class CycleComparisonTest {

    /** The owner's actual House Maintenance Jpr figures, from the screenshot that prompted the redesign. */
    private val realCycle = 1_533_900L // ₹15,339.00
    private val realUsual = 1_381_421L // ₹13,814.21

    @Test fun the_owners_real_numbers_read_as_a_plain_sentence() {
        val c = CycleComparison.of(realCycle, realUsual, cycleStillRunning = true)!!
        assertThat(c.sentence).isEqualTo("About ₹1,500 more than your usual month.")
    }

    @Test fun the_bigger_figure_fills_the_bar_and_the_other_is_proportional() {
        val c = CycleComparison.of(realCycle, realUsual, cycleStillRunning = true)!!
        assertThat(c.cycleFraction).isEqualTo(1f)
        assertThat(c.usualFraction).isWithin(0.001f).of(0.901f)
    }

    /** No history means no "usual" — there is nothing honest to compare against, so say nothing. */
    @Test fun no_history_produces_no_comparison_at_all() {
        assertThat(CycleComparison.of(realCycle, usualMonthMinor = 0L, cycleStillRunning = true)).isNull()
        assertThat(CycleComparison.of(realCycle, usualMonthMinor = -5L, cycleStillRunning = true)).isNull()
    }

    @Test fun an_empty_cycle_says_so_and_draws_no_bar() {
        val c = CycleComparison.of(0L, realUsual, cycleStillRunning = true)!!
        assertThat(c.sentence).isEqualTo("Nothing in this cycle yet.")
        assertThat(c.cycleFraction).isEqualTo(0f)
        assertThat(c.usualFraction).isEqualTo(1f)
    }

    /** A 1.3% wobble is not a finding; calling it one would cry wolf every single cycle. */
    @Test fun a_small_difference_is_reported_as_about_the_same() {
        val c = CycleComparison.of(1_400_000L, realUsual, cycleStillRunning = true)!!
        assertThat(c.sentence).isEqualTo("About the same as your usual month.")
    }

    /**
     * ⭐The honesty rule. A cycle that has not finished has not finished spending, so under-spending is
     * only ever reported "so far" — otherwise the screen congratulates a half-finished month that may
     * still overshoot. Over-spending needs no such hedge: once you are above a usual month, remaining
     * time cannot make that untrue.
     */
    @Test fun under_spending_says_so_far_while_the_cycle_is_still_running() {
        val running = CycleComparison.of(900_000L, realUsual, cycleStillRunning = true)!!
        assertThat(running.sentence).isEqualTo("About ₹4,800 under your usual month so far.")

        val finished = CycleComparison.of(900_000L, realUsual, cycleStillRunning = false)!!
        assertThat(finished.sentence).isEqualTo("About ₹4,800 less than your usual month.")
    }

    @Test fun over_spending_is_stated_flatly_whether_or_not_the_cycle_is_over() {
        val running = CycleComparison.of(realCycle, realUsual, cycleStillRunning = true)!!
        val finished = CycleComparison.of(realCycle, realUsual, cycleStillRunning = false)!!
        assertThat(running.sentence).isEqualTo(finished.sentence)
    }

    // ---- rounding: "about" has already disclaimed the paise ----

    @Test fun four_figure_differences_round_to_the_nearest_hundred_rupees() {
        // ₹1,524.79 more → "about ₹1,500", not "about ₹1,524.79".
        assertThat(CycleComparison.of(realCycle, realUsual, true)!!.sentence).contains("₹1,500")
    }

    @Test fun smaller_differences_round_to_the_nearest_ten_rupees() {
        // ₹547.00 this cycle against a ₹500.00 usual — a ₹47 gap, 9.4%, so it clears "about the same".
        val c = CycleComparison.of(54_700L, 50_000L, cycleStillRunning = false)!!
        assertThat(c.sentence).isEqualTo("About ₹50 more than your usual month.")
    }

    /** A real, non-zero difference must never be rounded away into "₹0 more than usual". */
    @Test fun a_tiny_difference_is_never_rounded_away_to_zero() {
        val c = CycleComparison.of(50_400L, 50_000L, cycleStillRunning = false)
        // 0.8% — below the threshold, so it is reported as "about the same" rather than "about ₹0 more".
        assertThat(c!!.sentence).isEqualTo("About the same as your usual month.")
        // And where a sub-₹10 gap DOES clear the threshold, it is shown as itself, not as ₹0.
        val small = CycleComparison.of(504L, 400L, cycleStillRunning = false)!!
        assertThat(small.sentence).doesNotContain("₹0 ")
    }

    @Test fun fractions_stay_inside_zero_to_one() {
        listOf(
            CycleComparison.of(1L, 9_999_999L, true)!!,
            CycleComparison.of(9_999_999L, 1L, true)!!,
        ).forEach { c ->
            assertThat(c.cycleFraction).isAtLeast(0f)
            assertThat(c.cycleFraction).isAtMost(1f)
            assertThat(c.usualFraction).isAtLeast(0f)
            assertThat(c.usualFraction).isAtMost(1f)
        }
    }
}
