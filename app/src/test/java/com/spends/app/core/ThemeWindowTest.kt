package com.spends.app.core

import com.google.common.truth.Truth.assertThat
import com.spends.app.core.period.PeriodRange
import com.spends.app.core.period.PeriodSelection
import com.spends.app.core.period.PeriodType
import com.spends.app.core.theme.isWithinDarkWindow
import org.junit.Test

/** Auto-dark window math (#1) — the default window wraps past midnight. */
class ThemeWindowTest {

    @Test fun wrapping_window_8pm_to_6am() {
        val start = 20 * 60 // 20:00
        val end = 6 * 60 // 06:00
        assertThat(isWithinDarkWindow(22 * 60, start, end)).isTrue() // 22:00 → dark
        assertThat(isWithinDarkWindow(2 * 60, start, end)).isTrue() // 02:00 → dark
        assertThat(isWithinDarkWindow(20 * 60, start, end)).isTrue() // exactly 20:00 → dark (inclusive start)
        assertThat(isWithinDarkWindow(6 * 60, start, end)).isFalse() // exactly 06:00 → light (exclusive end)
        assertThat(isWithinDarkWindow(12 * 60, start, end)).isFalse() // noon → light
    }

    @Test fun same_day_window() {
        val start = 9 * 60
        val end = 17 * 60
        assertThat(isWithinDarkWindow(12 * 60, start, end)).isTrue()
        assertThat(isWithinDarkWindow(8 * 60, start, end)).isFalse()
        assertThat(isWithinDarkWindow(18 * 60, start, end)).isFalse()
    }

    @Test fun empty_window_is_never_dark() {
        assertThat(isWithinDarkWindow(0, 60, 60)).isFalse()
        assertThat(isWithinDarkWindow(720, 720, 720)).isFalse()
    }
}

/** Descriptive cycle labels (#5). */
class PeriodDescribeTest {
    @Test fun describes_type_and_range_in_words() {
        assertThat(PeriodSelection(PeriodType.SALARY_CYCLE, PeriodRange.CURRENT).describe())
            .isEqualTo("Current Salary Cycle")
        assertThat(PeriodSelection(PeriodType.MONTH, PeriodRange.CURRENT).describe())
            .isEqualTo("Current Month")
        assertThat(PeriodSelection(PeriodType.SMART_CYCLE, PeriodRange.CURRENT).describe())
            .isEqualTo("This Month's Smart Cycle")
        assertThat(PeriodSelection(PeriodType.MONTH, PeriodRange.LAST_3).describe())
            .isEqualTo("Last 3 Months")
        assertThat(PeriodSelection(PeriodType.SALARY_CYCLE, PeriodRange.LAST_6).describe())
            .isEqualTo("Last 6 Salary Cycles")
        assertThat(PeriodSelection(PeriodType.MONTH, PeriodRange.ALL).describe())
            .isEqualTo("All Time")
        assertThat(PeriodSelection(PeriodType.SALARY_CYCLE, PeriodRange.CUSTOM).describe())
            .isEqualTo("Custom Range")
    }
}
