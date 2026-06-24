package com.spends.app.core

import com.google.common.truth.Truth.assertThat
import com.spends.app.core.time.CycleUtils
import org.junit.Test
import java.time.LocalDate

class CycleUtilsTest {

    @Test fun salary_cycle_when_before_anchor_starts_last_month() {
        val w = CycleUtils.windowFor(LocalDate.of(2026, 6, 24), anchorDay = 25)
        assertThat(w.start).isEqualTo(LocalDate.of(2026, 5, 25))
        assertThat(w.endInclusive).isEqualTo(LocalDate.of(2026, 6, 24))
        assertThat(w.contains(LocalDate.of(2026, 6, 24))).isTrue()
        assertThat(w.contains(LocalDate.of(2026, 5, 25))).isTrue()
        assertThat(w.contains(LocalDate.of(2026, 5, 24))).isFalse()
    }

    @Test fun card_cycle_when_on_or_after_anchor_starts_this_month() {
        val w = CycleUtils.windowFor(LocalDate.of(2026, 6, 24), anchorDay = 17)
        assertThat(w.start).isEqualTo(LocalDate.of(2026, 6, 17))
        assertThat(w.endInclusive).isEqualTo(LocalDate.of(2026, 7, 16))
    }

    @Test fun month_end_anchor_clamps_to_february() {
        val w = CycleUtils.windowFor(LocalDate.of(2026, 2, 10), anchorDay = 31)
        assertThat(w.start).isEqualTo(LocalDate.of(2026, 1, 31))
        assertThat(w.endInclusive).isEqualTo(LocalDate.of(2026, 2, 27))
    }

    @Test fun previous_and_next_windows_step_one_cycle() {
        val w = CycleUtils.windowFor(LocalDate.of(2026, 6, 24), anchorDay = 25)
        val prev = CycleUtils.previousWindow(w, 25)
        assertThat(prev.start).isEqualTo(LocalDate.of(2026, 4, 25))
        assertThat(prev.endInclusive).isEqualTo(LocalDate.of(2026, 5, 24))
        val next = CycleUtils.nextWindow(w, 25)
        assertThat(next.start).isEqualTo(LocalDate.of(2026, 6, 25))
        assertThat(next.endInclusive).isEqualTo(LocalDate.of(2026, 7, 24))
    }

    @Test fun calendar_month_window() {
        val w = CycleUtils.calendarMonth(LocalDate.of(2026, 2, 15))
        assertThat(w.start).isEqualTo(LocalDate.of(2026, 2, 1))
        assertThat(w.endInclusive).isEqualTo(LocalDate.of(2026, 2, 28))
    }

    @Test fun windows_are_contiguous_and_non_overlapping() {
        var w = CycleUtils.windowFor(LocalDate.of(2026, 1, 1), anchorDay = 17)
        repeat(14) {
            val next = CycleUtils.nextWindow(w, 17)
            // next starts exactly the day after this window ends -> no gap, no overlap.
            assertThat(next.start).isEqualTo(w.endExclusive)
            w = next
        }
    }
}
