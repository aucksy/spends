package com.spends.app.core.period

import com.google.common.truth.Truth.assertThat
import com.spends.app.core.time.DateUtils
import org.junit.Test
import java.time.LocalDate

class SmartCycleDetectorTest {

    /** Income at noon on the given IST calendar day. */
    private fun income(year: Int, month: Int, day: Int): Long =
        DateUtils.epochMillisFor(LocalDate.of(year, month, day))

    @Test fun detects_the_mode_day_of_month() {
        val samples = listOf(
            income(2026, 3, 5),
            income(2026, 4, 5),
            income(2026, 5, 5),
            income(2026, 6, 12), // outlier
        )
        assertThat(SmartCycleDetector.detectSalaryDay(samples, fallbackDay = 1)).isEqualTo(5)
    }

    @Test fun ties_resolve_to_the_smaller_day() {
        val samples = listOf(
            income(2026, 3, 10),
            income(2026, 4, 10),
            income(2026, 5, 20),
            income(2026, 6, 20),
        )
        // Day 10 and day 20 each appear twice -> smaller day wins.
        assertThat(SmartCycleDetector.detectSalaryDay(samples, fallbackDay = 1)).isEqualTo(10)
    }

    @Test fun fewer_than_two_samples_returns_fallback() {
        assertThat(SmartCycleDetector.detectSalaryDay(emptyList(), fallbackDay = 7)).isEqualTo(7)
        assertThat(
            SmartCycleDetector.detectSalaryDay(listOf(income(2026, 6, 3)), fallbackDay = 7),
        ).isEqualTo(7)
    }

    @Test fun day_31_clamps_to_28() {
        val samples = listOf(
            income(2026, 1, 31),
            income(2026, 3, 31),
            income(2026, 5, 31),
        )
        assertThat(SmartCycleDetector.detectSalaryDay(samples, fallbackDay = 1)).isEqualTo(28)
    }

    @Test fun days_29_and_30_clamp_to_28_and_can_aggregate() {
        val samples = listOf(
            income(2026, 1, 29), // -> 28
            income(2026, 3, 30), // -> 28
            income(2026, 5, 15), // -> 15
        )
        // Both month-end samples clamp to 28, so 28 is the mode.
        assertThat(SmartCycleDetector.detectSalaryDay(samples, fallbackDay = 1)).isEqualTo(28)
    }
}
