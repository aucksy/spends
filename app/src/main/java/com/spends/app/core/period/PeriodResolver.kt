package com.spends.app.core.period

import com.spends.app.core.time.CycleUtils
import com.spends.app.core.time.CycleWindow
import com.spends.app.core.time.DateUtils
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What kind of cycle a period is sliced into. */
enum class PeriodType { MONTH, SALARY_CYCLE, SMART_CYCLE }

/** How many cycles a period spans (relative to the current one). */
enum class PeriodRange { ALL, CURRENT, LAST_3, LAST_6, CUSTOM }

/**
 * A concrete, half-open time window in epoch-millis (IST), ready for an
 * `occurredAt >= startMillis AND < endExclusiveMillis` repository query, plus a short label.
 */
data class ResolvedPeriod(
    val startMillis: Long,
    val endExclusiveMillis: Long,
    val label: String,
)

/**
 * Unified period selector shared by the Transactions and Analytics screens.
 *
 * A "cycle" is one of:
 *  - [PeriodType.MONTH]: a plain calendar month ([CycleUtils.calendarMonth]).
 *  - [PeriodType.SALARY_CYCLE]: the salary-anchored window for [resolve]'s `salaryDay`
 *    ([CycleUtils.windowFor] / [CycleUtils.previousWindow] / [CycleUtils.nextWindow]).
 *  - [PeriodType.SMART_CYCLE]: identical math, but anchored on the auto-detected `smartDay`.
 *
 * All window boundaries are converted to epoch-millis via [DateUtils.startOfDayMillis] — the same
 * computation [CycleWindow.startMillis]/[CycleWindow.endExclusiveMillis] use — so results line up
 * exactly with how the repository slices stored transactions.
 */
object PeriodResolver {

    private val monthLabelFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
    private val cycleDayFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val customDayFormatter = DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH)

    /** A 5-year floor before [today] used as the lower bound for ALL when there is no data. */
    private const val ALL_FALLBACK_YEARS = 5L

    fun resolve(
        type: PeriodType,
        range: PeriodRange,
        salaryDay: Int,
        smartDay: Int,
        today: LocalDate,
        earliestDataDay: LocalDate?,
        customStartMillis: Long?,
        customEndExclusiveMillis: Long?,
        cycleOffset: Int = 0,
    ): ResolvedPeriod {
        // The day-of-month the cycle anchors on (unused for MONTH, which is calendar-based).
        val anchorDay = when (type) {
            PeriodType.MONTH -> 1
            PeriodType.SALARY_CYCLE -> salaryDay
            PeriodType.SMART_CYCLE -> smartDay
        }

        val current = currentWindow(type, anchorDay, today)

        return when (range) {
            // The prev/next arrows step the single current cycle by [cycleOffset] (#6); other ranges ignore it.
            PeriodRange.CURRENT -> {
                val shown = shiftWindow(type, anchorDay, current, cycleOffset)
                ResolvedPeriod(
                    startMillis = startMillis(shown),
                    endExclusiveMillis = endExclusiveMillis(shown),
                    label = currentLabel(type, shown),
                )
            }

            PeriodRange.LAST_3 -> spanEndingAtCurrent(type, anchorDay, current, count = 3)

            PeriodRange.LAST_6 -> spanEndingAtCurrent(type, anchorDay, current, count = 6)

            PeriodRange.ALL -> {
                val floor = earliestDataDay ?: today.minusYears(ALL_FALLBACK_YEARS)
                ResolvedPeriod(
                    startMillis = DateUtils.startOfDayMillis(floor),
                    // Include everything up to and including today by ending at the current cycle's
                    // exclusive end (today always falls inside the current cycle).
                    endExclusiveMillis = endExclusiveMillis(current),
                    label = "All time",
                )
            }

            PeriodRange.CUSTOM -> {
                if (customStartMillis == null || customEndExclusiveMillis == null) {
                    // Fall back to the current cycle when a custom bound is missing.
                    ResolvedPeriod(
                        startMillis = startMillis(current),
                        endExclusiveMillis = endExclusiveMillis(current),
                        label = currentLabel(type, current),
                    )
                } else {
                    ResolvedPeriod(
                        startMillis = customStartMillis,
                        endExclusiveMillis = customEndExclusiveMillis,
                        label = customLabel(customStartMillis, customEndExclusiveMillis),
                    )
                }
            }
        }
    }

    /** The single cycle window that contains [today] for the given [type]/[anchorDay]. */
    private fun currentWindow(type: PeriodType, anchorDay: Int, today: LocalDate): CycleWindow =
        when (type) {
            PeriodType.MONTH -> CycleUtils.calendarMonth(today)
            PeriodType.SALARY_CYCLE, PeriodType.SMART_CYCLE -> CycleUtils.windowFor(today, anchorDay)
        }

    /** The cycle immediately before [window] for the given [type]/[anchorDay]. */
    private fun previousWindow(type: PeriodType, anchorDay: Int, window: CycleWindow): CycleWindow =
        when (type) {
            PeriodType.MONTH -> CycleUtils.calendarMonth(window.start.minusDays(1))
            PeriodType.SALARY_CYCLE, PeriodType.SMART_CYCLE ->
                CycleUtils.previousWindow(window, anchorDay)
        }

    /** The cycle immediately after [window] for the given [type]/[anchorDay]. */
    private fun nextWindow(type: PeriodType, anchorDay: Int, window: CycleWindow): CycleWindow =
        when (type) {
            // window.endExclusive is the first day of the next calendar month.
            PeriodType.MONTH -> CycleUtils.calendarMonth(window.endExclusive)
            PeriodType.SALARY_CYCLE, PeriodType.SMART_CYCLE ->
                CycleUtils.nextWindow(window, anchorDay)
        }

    /** Step [window] back/forward by [offset] whole cycles (negative = earlier, positive = later) (#6). */
    private fun shiftWindow(type: PeriodType, anchorDay: Int, window: CycleWindow, offset: Int): CycleWindow {
        var w = window
        repeat(kotlin.math.abs(offset)) {
            w = if (offset < 0) previousWindow(type, anchorDay, w) else nextWindow(type, anchorDay, w)
        }
        return w
    }

    /**
     * A span of [count] consecutive cycles ending at (and including) [current].
     * LAST_3 => the start of the cycle 2 before current .. endExclusive of current.
     */
    private fun spanEndingAtCurrent(
        type: PeriodType,
        anchorDay: Int,
        current: CycleWindow,
        count: Int,
    ): ResolvedPeriod {
        var first = current
        repeat(count - 1) { first = previousWindow(type, anchorDay, first) }
        return ResolvedPeriod(
            startMillis = startMillis(first),
            endExclusiveMillis = endExclusiveMillis(current),
            label = spanLabel(type, count),
        )
    }

    // --- millis helpers: mirror CycleWindow.startMillis()/endExclusiveMillis() exactly ---

    private fun startMillis(window: CycleWindow): Long = DateUtils.startOfDayMillis(window.start)

    private fun endExclusiveMillis(window: CycleWindow): Long =
        DateUtils.startOfDayMillis(window.endExclusive)

    // --- labels ---

    private fun currentLabel(type: PeriodType, window: CycleWindow): String =
        when (type) {
            PeriodType.MONTH -> monthLabelFormatter.format(YearMonth.from(window.start))
            PeriodType.SALARY_CYCLE, PeriodType.SMART_CYCLE ->
                "${cycleDayFormatter.format(window.start)} – " +
                    cycleDayFormatter.format(window.endInclusive)
        }

    private fun spanLabel(type: PeriodType, count: Int): String =
        when (type) {
            PeriodType.MONTH -> "Last $count months"
            PeriodType.SALARY_CYCLE, PeriodType.SMART_CYCLE -> "Last $count cycles"
        }

    private fun customLabel(startMillis: Long, endExclusiveMillis: Long): String {
        val startDay = DateUtils.toLocalDate(startMillis)
        // The stored end is exclusive; show the inclusive last day to the user.
        val endDay = DateUtils.toLocalDate(endExclusiveMillis).minusDays(1)
        return "${customDayFormatter.format(startDay)} – ${customDayFormatter.format(endDay)}"
    }
}
