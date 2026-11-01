package com.spends.app.core.time

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.min

/** A half-open spending period [start, endInclusive], with helpers for the exclusive end. */
data class CycleWindow(val start: LocalDate, val endInclusive: LocalDate) {
    val endExclusive: LocalDate get() = endInclusive.plusDays(1)

    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)

    /** Epoch-millis bounds in IST, suitable for a `occurredAt >= startMillis AND < endMillis` query. */
    fun startMillis(): Long = DateUtils.startOfDayMillis(start)
    fun endExclusiveMillis(): Long = DateUtils.startOfDayMillis(endExclusive)
}

/**
 * Cycle-aware period math (PRD §4.8). Every payment instrument has its own anchored monthly cycle:
 * a bank/UPI/debit/wallet instrument anchors on the salary day; a credit card anchors on its
 * statement day. This object computes the single window for one anchor day; the composite
 * "My Cycle" view (Phase 2) unions one window per instrument.
 *
 * Month-end anchors are clamped to the length of each month, so e.g. an anchor of 31 lands on
 * Feb 28/29 — consistently and reversibly.
 */
object CycleUtils {

    /** Clamp an anchor day (1..31) into a real date within [ym]. */
    fun clampDay(ym: YearMonth, anchorDay: Int): LocalDate =
        ym.atDay(min(max(anchorDay, 1), ym.lengthOfMonth()))

    /**
     * The cycle window that contains [date] for the given monthly [anchorDay]. For the salary anchor, a
     * month whose salary came early or late starts on the day it came ([SalaryDates]); otherwise every
     * month starts on the anchor day.
     */
    fun windowFor(date: LocalDate, anchorDay: Int): CycleWindow {
        val cal = SalaryDates.active
        // The latest cycle month that has started by [date]. Starts move at most 10 days, so this is one of
        // next month, this month or last month.
        var ym = YearMonth.from(date).plusMonths(1)
        while (cal.startOf(ym, anchorDay).isAfter(date)) ym = ym.minusMonths(1)
        val nextStart = cal.startOf(ym.plusMonths(1), anchorDay)
        return CycleWindow(cal.startOf(ym, anchorDay), nextStart.minusDays(1))
    }

    /** The window immediately before [window] for the same anchor. */
    fun previousWindow(window: CycleWindow, anchorDay: Int): CycleWindow =
        windowFor(window.start.minusDays(1), anchorDay)

    /** The window immediately after [window] for the same anchor. */
    fun nextWindow(window: CycleWindow, anchorDay: Int): CycleWindow =
        windowFor(window.endInclusive.plusDays(1), anchorDay)

    /** Plain calendar-month window (1st .. end-of-month), for the Calendar Month analytics view. */
    fun calendarMonth(date: LocalDate): CycleWindow {
        val ym = YearMonth.from(date)
        return CycleWindow(ym.atDay(1), ym.atEndOfMonth())
    }

    fun calendarMonth(yearMonth: YearMonth): CycleWindow =
        CycleWindow(yearMonth.atDay(1), yearMonth.atEndOfMonth())
}
