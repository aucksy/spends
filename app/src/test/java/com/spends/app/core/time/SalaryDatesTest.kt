package com.spends.app.core.time

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class SalaryDatesTest {

    private fun d(s: String) = LocalDate.parse(s)
    private val pay = 75_000_00L

    @After
    fun reset() = SalaryDates.publish(SalaryDates.Calendar())

    @Test
    fun earlySalaryBelongsToTheNextMonth() {
        val starts = SalaryDates.derive(listOf(d("2026-10-01") to pay, d("2026-10-30") to pay), usualDay = 1)
        assertEquals(mapOf(YearMonth.of(2026, 10) to d("2026-10-01"), YearMonth.of(2026, 11) to d("2026-10-30")), starts)
    }

    @Test
    fun lateSalaryMovesItsMonthLater() {
        val starts = SalaryDates.derive(listOf(d("2026-11-04") to pay), usualDay = 1)
        assertEquals(mapOf(YearMonth.of(2026, 11) to d("2026-11-04")), starts)
    }

    @Test
    fun smallCreditsAndMidMonthBonusesAreIgnored() {
        val starts = SalaryDates.derive(
            listOf(d("2026-10-01") to pay, d("2026-10-03") to 2_000_00L, d("2026-10-16") to pay),
            usualDay = 1,
        )
        assertEquals(mapOf(YearMonth.of(2026, 10) to d("2026-10-01")), starts)
    }

    @Test
    fun anEarlySalaryStartsTheCycleOnTheDayItCame() {
        SalaryDates.publish(SalaryDates.Calendar(1, mapOf(YearMonth.of(2026, 11) to d("2026-10-30"))))
        // October ends the day before the early salary; the new cycle runs to the usual 1 Dec.
        assertEquals(CycleWindow(d("2026-10-01"), d("2026-10-29")), CycleUtils.windowFor(d("2026-10-15"), 1))
        assertEquals(CycleWindow(d("2026-10-30"), d("2026-11-30")), CycleUtils.windowFor(d("2026-10-31"), 1))
        assertEquals(CycleWindow(d("2026-10-30"), d("2026-11-30")), CycleUtils.windowFor(d("2026-11-20"), 1))
    }

    @Test
    fun aLateSalaryKeepsTheOldCycleUntilItCame() {
        SalaryDates.publish(SalaryDates.Calendar(1, mapOf(YearMonth.of(2026, 11) to d("2026-11-04"))))
        assertEquals(CycleWindow(d("2026-10-01"), d("2026-11-03")), CycleUtils.windowFor(d("2026-11-02"), 1))
        assertEquals(CycleWindow(d("2026-11-04"), d("2026-11-30")), CycleUtils.windowFor(d("2026-11-04"), 1))
    }

    @Test
    fun otherAnchorsIgnoreTheSalaryCalendar() {
        SalaryDates.publish(SalaryDates.Calendar(1, mapOf(YearMonth.of(2026, 11) to d("2026-10-30"))))
        assertEquals(CycleWindow(d("2026-10-15"), d("2026-11-14")), CycleUtils.windowFor(d("2026-10-31"), 15))
    }

    @Test
    fun withNoCalendarCyclesAreTheFixedDay() {
        assertEquals(CycleWindow(d("2026-10-25"), d("2026-11-24")), CycleUtils.windowFor(d("2026-11-02"), 25))
        assertEquals(CycleWindow(d("2026-02-28"), d("2026-03-30")), CycleUtils.windowFor(d("2026-03-01"), 31))
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val cal = SalaryDates.Calendar(1, mapOf(YearMonth.of(2026, 11) to d("2026-10-30"), YearMonth.of(2026, 12) to d("2026-12-02")))
        assertEquals(cal, SalaryDates.decode(SalaryDates.encode(cal)))
        assertEquals(SalaryDates.Calendar(), SalaryDates.decode("garbage"))
        assertEquals(SalaryDates.Calendar(3, emptyMap()), SalaryDates.decode("3|"))
    }

    @Test
    fun latestSalaryDaySkipsSmallCredits() {
        assertEquals(d("2026-10-28"), SalaryDates.latestSalaryDay(listOf(d("2026-10-28") to pay, d("2026-10-30") to 1_000_00L)))
        assertNull(SalaryDates.latestSalaryDay(emptyList()))
    }
}
