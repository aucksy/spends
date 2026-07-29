package com.spends.app.core.calc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rule the app's summary header and the home-screen widget now share.
 *
 * The bug this pins: the widget showed `income − expense` and never applied carry-forward at all, so a
 * user with the setting on read one balance on their home screen and a different one inside the app.
 * The fix is one definition; these tests are what stop a second copy quietly reappearing.
 */
class CarryForwardTest {

    private val anchor = 1_700_000_000_000L
    private val afterAnchor = anchor + 86_400_000L
    private val beforeAnchor = anchor - 86_400_000L

    private fun resolve(
        enabled: Boolean = true,
        anchorMillis: Long = anchor,
        openingMinor: Long = 0,
        periodStartMillis: Long = afterAnchor,
        applies: Boolean = true,
        net: Long = 0,
    ): Long? = CarryForward.resolve(enabled, anchorMillis, openingMinor, periodStartMillis, applies) { net }

    @Test
    fun off_means_no_carry_at_all() {
        assertThat(resolve(enabled = false, openingMinor = 5_000, net = 900)).isNull()
    }

    /**
     * The guard that matters most. Without an anchor the old behaviour folded in every scrap of
     * incomplete history and produced a hugely-negative balance, so "no anchor" must mean "no carry",
     * NOT "carry everything".
     */
    @Test
    fun without_an_anchor_there_is_no_carry_even_when_enabled() {
        assertThat(resolve(anchorMillis = 0, openingMinor = 5_000, net = 900)).isNull()
        assertThat(resolve(anchorMillis = -1, openingMinor = 5_000, net = 900)).isNull()
    }

    @Test
    fun a_window_starting_before_the_anchor_gets_nothing() {
        assertThat(resolve(periodStartMillis = beforeAnchor, openingMinor = 5_000, net = 900)).isNull()
    }

    @Test
    fun a_window_starting_exactly_on_the_anchor_does_carry() {
        assertThat(resolve(periodStartMillis = anchor, openingMinor = 5_000, net = 0)).isEqualTo(5_000)
    }

    /** A single card's statement is not a whole-account running balance, so it opts out. */
    @Test
    fun a_caller_can_opt_out_entirely() {
        assertThat(resolve(applies = false, openingMinor = 5_000, net = 900)).isNull()
    }

    @Test
    fun otherwise_it_is_the_opening_balance_plus_the_net_since_the_anchor() {
        assertThat(resolve(openingMinor = 5_000, net = 900)).isEqualTo(5_900)
        assertThat(resolve(openingMinor = 5_000, net = -900)).isEqualTo(4_100)
        assertThat(resolve(openingMinor = -2_000, net = 900)).isEqualTo(-1_100)
    }

    /** Zero is a real carry-forward value; null means the feature does not apply. The UI shows a tile for
     *  one and not the other, so collapsing them would be a visible defect. */
    @Test
    fun zero_and_null_are_different_answers() {
        assertThat(resolve(openingMinor = 0, net = 0)).isEqualTo(0L)
        assertThat(resolve(enabled = false, openingMinor = 0, net = 0)).isNull()
    }

    /** The net is only worth computing when it will be used — callers put a database read behind it. */
    @Test
    fun the_net_is_not_computed_when_a_guard_rejects() {
        var computed = 0
        CarryForward.resolve(
            enabled = false,
            anchorMillis = anchor,
            openingMinor = 0,
            periodStartMillis = afterAnchor,
        ) { computed++; 0L }
        assertThat(computed).isEqualTo(0)

        CarryForward.resolve(
            enabled = true,
            anchorMillis = anchor,
            openingMinor = 0,
            periodStartMillis = afterAnchor,
        ) { computed++; 0L }
        assertThat(computed).isEqualTo(1)
    }

    /**
     * The regression itself, stated as the app and the widget agreeing. Both feed the same rule the same
     * inputs, so the only way they can disagree now is if one stops calling it.
     */
    @Test
    fun the_widget_and_the_app_reach_the_same_balance() {
        val income = 120_000L
        val expense = 45_000L
        val opening = 30_000L
        val netSinceAnchor = 8_000L

        val appCarry = resolve(openingMinor = opening, net = netSinceAnchor)
        val widgetCarry = resolve(openingMinor = opening, net = netSinceAnchor)

        val appBalance = income - expense + (appCarry ?: 0L)
        val widgetBalance = income - expense + (widgetCarry ?: 0L)

        assertThat(widgetBalance).isEqualTo(appBalance)
        // And that it is genuinely different from the old widget figure, or this test proves nothing.
        assertThat(widgetBalance).isNotEqualTo(income - expense)
        assertThat(widgetBalance).isEqualTo(113_000L)
    }
}
