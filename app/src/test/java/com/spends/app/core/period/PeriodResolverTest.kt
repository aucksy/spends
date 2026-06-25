package com.spends.app.core.period

import com.google.common.truth.Truth.assertThat
import com.spends.app.core.time.CycleUtils
import com.spends.app.core.time.DateUtils
import org.junit.Test
import java.time.LocalDate

class PeriodResolverTest {

    private val today = LocalDate.of(2026, 6, 25)

    private fun resolve(
        type: PeriodType,
        range: PeriodRange,
        salaryDay: Int = 25,
        smartDay: Int = 25,
        earliestDataDay: LocalDate? = null,
        customStartMillis: Long? = null,
        customEndExclusiveMillis: Long? = null,
    ): ResolvedPeriod = PeriodResolver.resolve(
        type = type,
        range = range,
        salaryDay = salaryDay,
        smartDay = smartDay,
        today = today,
        earliestDataDay = earliestDataDay,
        customStartMillis = customStartMillis,
        customEndExclusiveMillis = customEndExclusiveMillis,
    )

    private fun startMillis(date: LocalDate) = DateUtils.startOfDayMillis(date)

    @Test fun month_current_is_the_calendar_month_window() {
        val p = resolve(PeriodType.MONTH, PeriodRange.CURRENT)
        assertThat(p.startMillis).isEqualTo(startMillis(LocalDate.of(2026, 6, 1)))
        assertThat(p.endExclusiveMillis).isEqualTo(startMillis(LocalDate.of(2026, 7, 1)))
        assertThat(p.label).isEqualTo("Jun 2026")
    }

    @Test fun salary_cycle_current_matches_cycleutils_windowfor() {
        val expected = CycleUtils.windowFor(today, 25)
        val p = resolve(PeriodType.SALARY_CYCLE, PeriodRange.CURRENT, salaryDay = 25)
        assertThat(p.startMillis).isEqualTo(expected.startMillis())
        assertThat(p.endExclusiveMillis).isEqualTo(expected.endExclusiveMillis())
        // 2026-06-25 .. 2026-07-24
        assertThat(p.startMillis).isEqualTo(startMillis(LocalDate.of(2026, 6, 25)))
        assertThat(p.endExclusiveMillis).isEqualTo(startMillis(LocalDate.of(2026, 7, 25)))
        assertThat(p.label).isEqualTo("25 Jun – 24 Jul")
    }

    @Test fun last_3_spans_exactly_three_cycles_ending_at_current_end() {
        val current = CycleUtils.windowFor(today, 25)
        val p = resolve(PeriodType.SALARY_CYCLE, PeriodRange.LAST_3, salaryDay = 25)
        // Start of the cycle 2 before current: 2026-04-25.
        assertThat(p.startMillis).isEqualTo(startMillis(LocalDate.of(2026, 4, 25)))
        assertThat(p.endExclusiveMillis).isEqualTo(current.endExclusiveMillis())
        assertThat(p.endExclusiveMillis).isEqualTo(startMillis(LocalDate.of(2026, 7, 25)))
        assertThat(p.label).isEqualTo("Last 3 cycles")
    }

    @Test fun last_3_for_month_spans_three_calendar_months() {
        val p = resolve(PeriodType.MONTH, PeriodRange.LAST_3)
        // Apr, May, Jun 2026 -> start 2026-04-01, endExclusive 2026-07-01.
        assertThat(p.startMillis).isEqualTo(startMillis(LocalDate.of(2026, 4, 1)))
        assertThat(p.endExclusiveMillis).isEqualTo(startMillis(LocalDate.of(2026, 7, 1)))
        assertThat(p.label).isEqualTo("Last 3 months")
    }

    @Test fun last_6_spans_exactly_six_cycles() {
        val current = CycleUtils.windowFor(today, 25)
        val p = resolve(PeriodType.SALARY_CYCLE, PeriodRange.LAST_6, salaryDay = 25)
        // 5 cycles before current: Jun->May->Apr->Mar->Feb->Jan, start 2026-01-25.
        assertThat(p.startMillis).isEqualTo(startMillis(LocalDate.of(2026, 1, 25)))
        assertThat(p.endExclusiveMillis).isEqualTo(current.endExclusiveMillis())
        assertThat(p.label).isEqualTo("Last 6 cycles")
    }

    @Test fun all_starts_at_earliest_data_day() {
        val earliest = LocalDate.of(2024, 2, 3)
        val current = CycleUtils.windowFor(today, 25)
        val p = resolve(PeriodType.SALARY_CYCLE, PeriodRange.ALL, salaryDay = 25, earliestDataDay = earliest)
        assertThat(p.startMillis).isEqualTo(startMillis(earliest))
        assertThat(p.endExclusiveMillis).isEqualTo(current.endExclusiveMillis())
        assertThat(p.label).isEqualTo("All time")
    }

    @Test fun all_falls_back_to_five_years_before_today_when_no_data() {
        val p = resolve(PeriodType.MONTH, PeriodRange.ALL, earliestDataDay = null)
        assertThat(p.startMillis).isEqualTo(startMillis(today.minusYears(5)))
        assertThat(p.label).isEqualTo("All time")
    }

    @Test fun custom_passes_through_bounds() {
        val start = startMillis(LocalDate.of(2026, 3, 10))
        val endExclusive = startMillis(LocalDate.of(2026, 4, 11))
        val p = resolve(
            PeriodType.MONTH,
            PeriodRange.CUSTOM,
            customStartMillis = start,
            customEndExclusiveMillis = endExclusive,
        )
        assertThat(p.startMillis).isEqualTo(start)
        assertThat(p.endExclusiveMillis).isEqualTo(endExclusive)
        // Inclusive last day shown is 2026-04-10.
        assertThat(p.label).isEqualTo("10 Mar 26 – 10 Apr 26")
    }

    @Test fun custom_falls_back_to_current_when_start_null() {
        val p = resolve(
            PeriodType.MONTH,
            PeriodRange.CUSTOM,
            customStartMillis = null,
            customEndExclusiveMillis = startMillis(LocalDate.of(2026, 4, 11)),
        )
        assertThat(p.startMillis).isEqualTo(startMillis(LocalDate.of(2026, 6, 1)))
        assertThat(p.endExclusiveMillis).isEqualTo(startMillis(LocalDate.of(2026, 7, 1)))
        assertThat(p.label).isEqualTo("Jun 2026")
    }

    @Test fun custom_falls_back_to_current_when_end_null() {
        val p = resolve(
            PeriodType.MONTH,
            PeriodRange.CUSTOM,
            customStartMillis = startMillis(LocalDate.of(2026, 3, 10)),
            customEndExclusiveMillis = null,
        )
        assertThat(p.startMillis).isEqualTo(startMillis(LocalDate.of(2026, 6, 1)))
        assertThat(p.endExclusiveMillis).isEqualTo(startMillis(LocalDate.of(2026, 7, 1)))
    }

    @Test fun smart_cycle_with_different_smart_day_differs_from_salary_cycle() {
        val salary = resolve(
            PeriodType.SALARY_CYCLE, PeriodRange.CURRENT, salaryDay = 25, smartDay = 10,
        )
        val smart = resolve(
            PeriodType.SMART_CYCLE, PeriodRange.CURRENT, salaryDay = 25, smartDay = 10,
        )
        // Smart uses smartDay=10: windowFor(2026-06-25, 10) -> 2026-06-10 .. 2026-07-09.
        assertThat(smart.startMillis).isEqualTo(startMillis(LocalDate.of(2026, 6, 10)))
        assertThat(smart.endExclusiveMillis).isEqualTo(startMillis(LocalDate.of(2026, 7, 10)))
        // Salary uses salaryDay=25 and so must differ.
        assertThat(smart.startMillis).isNotEqualTo(salary.startMillis)
        assertThat(smart.endExclusiveMillis).isNotEqualTo(salary.endExclusiveMillis)
        assertThat(smart.label).isEqualTo("10 Jun – 9 Jul")
    }
}
