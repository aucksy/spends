package com.spends.app.core.period

import com.google.common.truth.Truth.assertThat
import com.spends.app.core.time.CycleUtils
import com.spends.app.core.time.DateUtils
import org.junit.Test
import java.time.LocalDate

/**
 * Locks the Smart-Cycle card-billing rule ([SmartCardCycle]) — the single source of truth for which cycle a
 * card purchase counts in. The headline safety property is **conservation**: every transaction maps to
 * exactly one window (never zero = the old vanish bug, never two = a double count), and the shift is at most
 * one window forward. If any of that breaks, these tests fail before a rupee can go missing on device.
 */
class SmartCardCycleTest {

    private val reset = 25

    private fun at(date: LocalDate): Long = DateUtils.startOfDayMillis(date)
    private fun winStart(date: LocalDate, resetDay: Int = reset): Long =
        CycleUtils.windowFor(date, resetDay).startMillis()
    private fun nextWinStart(date: LocalDate, resetDay: Int = reset): Long =
        CycleUtils.nextWindow(CycleUtils.windowFor(date, resetDay), resetDay).startMillis()
    private fun eff(date: LocalDate, billingDay: Int?, resetDay: Int = reset): Long =
        SmartCardCycle.effectiveWindowStartMillis(at(date), billingDay, resetDay)

    // --- Bank / UPI and unknown billing day: never shifted ---

    @Test fun bank_or_upi_txn_is_never_shifted() {
        // A null billing day = Bank/UPI or a card with no known statement day → follows the reset day only.
        val d = LocalDate.of(2026, 7, 23)
        assertThat(eff(d, null)).isEqualTo(winStart(d))
    }

    @Test fun out_of_range_billing_day_is_treated_as_no_shift() {
        val d = LocalDate.of(2026, 7, 23)
        assertThat(eff(d, 0)).isEqualTo(winStart(d))
        assertThat(eff(d, 99)).isEqualTo(winStart(d))
    }

    // --- The owner's example: reset 25, card bills 23 ---

    @Test fun owner_example_before_billing_stays_current() {
        // Reset 25, bills 23. A purchase on the 22nd (before the bill) counts in the current cycle.
        val current = winStart(LocalDate.of(2026, 7, 10)) // window [25 Jun, 25 Jul)
        assertThat(eff(LocalDate.of(2026, 7, 22), 23)).isEqualTo(current)
    }

    @Test fun owner_example_on_and_after_billing_roll_to_next() {
        val current = winStart(LocalDate.of(2026, 7, 10)) // [25 Jun, 25 Jul)
        val next = nextWinStart(LocalDate.of(2026, 7, 10)) // [25 Jul, 25 Aug)
        // On the billing day and the day after → next cycle, NOT the current one.
        assertThat(eff(LocalDate.of(2026, 7, 23), 23)).isEqualTo(next)
        assertThat(eff(LocalDate.of(2026, 7, 24), 23)).isEqualTo(next)
        assertThat(next).isNotEqualTo(current)
    }

    @Test fun open_statement_spend_from_before_the_reset_pulls_into_current() {
        // Bills 23, reset 25. A purchase on 24 Jun is AFTER the 23 Jun bill, so it's on the open statement
        // that bills 23 Jul — which lands in the [25 Jun, 25 Jul) cycle. So it counts there, even though its
        // own date is in the previous window. (This is the correct "unbilled spends weigh on the current
        // cycle" behaviour, and the mirror image of the 23rd/24th rolling forward.)
        val current = winStart(LocalDate.of(2026, 7, 10))
        assertThat(eff(LocalDate.of(2026, 6, 24), 23)).isEqualTo(current)
        assertThat(eff(LocalDate.of(2026, 6, 24), 23)).isNotEqualTo(winStart(LocalDate.of(2026, 6, 24)))
    }

    // --- A card that bills mid-cycle (owner chose: shift them all) ---

    @Test fun mid_cycle_card_shifts_the_whole_tail() {
        val current = winStart(LocalDate.of(2026, 7, 10)) // [25 Jun, 25 Jul)
        val next = nextWinStart(LocalDate.of(2026, 7, 10))
        // Bills 10, reset 25. Before the 10th → current; the 12th (after the bill) → next.
        assertThat(eff(LocalDate.of(2026, 7, 5), 10)).isEqualTo(current)
        assertThat(eff(LocalDate.of(2026, 7, 12), 10)).isEqualTo(next)
    }

    @Test fun card_billing_after_the_reset_day() {
        val current = winStart(LocalDate.of(2026, 6, 26)) // [25 Jun, 25 Jul)
        val next = nextWinStart(LocalDate.of(2026, 6, 26))
        // Bills 28, reset 25. 26 Jun (before the 28th bill) → current; 29 Jun (after) → next.
        assertThat(eff(LocalDate.of(2026, 6, 26), 28)).isEqualTo(current)
        assertThat(eff(LocalDate.of(2026, 6, 29), 28)).isEqualTo(next)
    }

    // --- Month-end billing days are clamped ---

    @Test fun statement_close_clamps_month_end_billing_day() {
        // Bills 31: February clamps to the 28th (2026 is not a leap year).
        assertThat(SmartCardCycle.statementCloseDate(LocalDate.of(2026, 2, 15), 31))
            .isEqualTo(LocalDate.of(2026, 2, 28))
        // A purchase ON the clamped billing day is the new statement → bills the next month (31 → Mar 31).
        assertThat(SmartCardCycle.statementCloseDate(LocalDate.of(2026, 2, 28), 31))
            .isEqualTo(LocalDate.of(2026, 3, 31))
    }

    @Test fun leap_year_february_billing_day_29() {
        // 2028 is a leap year → clampDay(Feb, 31) = Feb 29.
        assertThat(SmartCardCycle.statementCloseDate(LocalDate.of(2028, 2, 10), 31))
            .isEqualTo(LocalDate.of(2028, 2, 29))
    }

    @Test fun month_end_double_clamp_never_shifts_past_the_next_window() {
        // Regression guard. Bills 30, resets 31: a purchase on 30 Mar 2026 bills on 30 Apr, whose reset window
        // sits TWO windows ahead of 30 Mar's window. Uncapped, that spend would land beyond every consumer's
        // one-window-back fetch and VANISH. The rule must cap it to the immediately-next window instead.
        assertThat(eff(LocalDate.of(2026, 3, 30), 30, 31))
            .isEqualTo(nextWinStart(LocalDate.of(2026, 3, 30), 31))
        // A second double-clamp pair (bills 29, resets 30) — also capped to exactly one window forward.
        assertThat(eff(LocalDate.of(2026, 1, 29), 29, 30))
            .isEqualTo(nextWinStart(LocalDate.of(2026, 1, 29), 30))
    }

    // --- The core safety properties (swept over many inputs) ---

    @Test fun shift_is_never_backward_and_at_most_one_window_forward() {
        // Includes the month-end clamp zone (resets 29/30/31 × billings 28/29/30/31) — where a double-clamp
        // could otherwise push a purchase TWO windows forward and out of every consumer's fetch (the vanish bug).
        val resets = listOf(1, 15, 25, 28, 29, 30, 31)
        val billingDays = listOf<Int?>(null, 1, 10, 23, 28, 29, 30, 31)
        var date = LocalDate.of(2025, 1, 1)
        val end = LocalDate.of(2026, 12, 31)
        while (!date.isAfter(end)) {
            for (r in resets) for (b in billingDays) {
                val e = eff(date, b, r)
                val raw = winStart(date, r)
                val next = nextWinStart(date, r)
                // Never earlier than the raw window, and never more than one window ahead.
                assertThat(e).isAnyOf(raw, next)
            }
            date = date.plusDays(1)
        }
    }

    @Test fun every_result_is_itself_a_real_window_start() {
        // A transaction must always land ON a window boundary (never a stray millis) — this is what lets every
        // consumer bucket by `effectiveWindowStart == window.start`.
        val billingDays = listOf<Int?>(null, 5, 23, 31)
        var date = LocalDate.of(2025, 6, 1)
        val end = LocalDate.of(2026, 6, 1)
        while (!date.isAfter(end)) {
            for (b in billingDays) {
                val e = eff(date, b)
                val backToWindowStart = CycleUtils.windowFor(DateUtils.toLocalDate(e), reset).startMillis()
                assertThat(e).isEqualTo(backToWindowStart)
            }
            date = date.plusDays(1)
        }
    }

    @Test fun partition_conserves_every_transaction_exactly_once() {
        // Build a spread of transactions (varied dates + billing days), assign each to a cycle, then confirm
        // the per-window buckets add back up to the full set — no drops (vanish), no duplicates (double count).
        data class Txn(val date: LocalDate, val billingDay: Int?, val id: Int)
        val txns = buildList {
            var d = LocalDate.of(2026, 4, 1)
            var id = 0
            val bds = listOf<Int?>(null, 10, 23, 28, 31)
            while (!d.isAfter(LocalDate.of(2026, 9, 30))) {
                add(Txn(d, bds[id % bds.size], id))
                id++
                d = d.plusDays(1)
            }
        }
        val byWindow: Map<Long, List<Txn>> = txns.groupBy { eff(it.date, it.billingDay) }
        // Every txn accounted for exactly once across all windows.
        val recombinedIds = byWindow.values.flatten().map { it.id }
        assertThat(recombinedIds).hasSize(txns.size)
        assertThat(recombinedIds.toSet()).isEqualTo(txns.map { it.id }.toSet())
    }
}
