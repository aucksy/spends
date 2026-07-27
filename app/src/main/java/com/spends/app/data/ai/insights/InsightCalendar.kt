package com.spends.app.data.ai.insights

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One charge reduced to what the calendar detectors need: when it happened, and how much it was. */
data class DatedCharge(val occurredAt: Long, val amountMinor: Long)

/**
 * How a run of complete cycles splits between the days just after payday and the rest.
 *
 * Both a money total and a *day count* are carried, because the only honest way to say "you spend more after
 * payday" is to compare money's share against the days' share. Saying "38% of your spending lands in the
 * payday week" alone is meaningless without "and that week is under a quarter of the cycle".
 */
data class HabitBuckets(
    val totalMinor: Long,
    val totalDays: Int,
    val paydayWeekMinor: Long,
    val paydayWeekDays: Int,
    /** Prior cycles that contained at least one charge — the "is there enough here to judge" gate. */
    val windowsWithData: Int,
)

/** The same stretch of a cycle, one year earlier. Only ever built when there is genuinely data back there. */
data class YearAgoWindow(
    val expenseMinor: Long,
    /** The calendar month this cycle mostly falls in, e.g. "July" — the same name applies to both sides. */
    val monthLabel: String,
)

/**
 * Calendar arithmetic for the Phase B detectors, kept **pure** so every rule here is unit-testable.
 *
 * `InsightEngine` deliberately knows nothing about dates or time zones — it is handed finished buckets. This
 * object is where the awkward parts live: how far into a cycle we are, which calendar month a cycle belongs
 * to when it straddles two, and where each charge falls relative to payday.
 *
 * Everything takes an explicit [ZoneId] rather than reaching for the system default, so a test can pin a zone
 * and a month-end or leap-year case is provable rather than hopeful.
 */
object InsightCalendar {

    /** "The week after payday" — the first seven days of a cycle that starts on the user's salary day. */
    const val PAYDAY_WINDOW_DAYS = 7

    private fun dateOf(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /**
     * Which day of the cycle we are on, counting the start day as day 1.
     *
     * Returns 0 or less for a cycle that has not begun (reachable — card spends rolled forward make the next
     * cycle navigable), which callers treat as "nothing to say yet".
     */
    fun daysElapsed(startMillis: Long, nowMillis: Long, zone: ZoneId): Int =
        ChronoUnit.DAYS.between(dateOf(startMillis, zone), dateOf(nowMillis, zone)).toInt() + 1

    /** Length of the cycle in whole days. `endExclusiveMillis` is exclusive, hence the step back of 1 ms. */
    fun cycleDays(startMillis: Long, endExclusiveMillis: Long, zone: ZoneId): Int {
        if (endExclusiveMillis <= startMillis) return 0
        return ChronoUnit.DAYS.between(dateOf(startMillis, zone), dateOf(endExclusiveMillis - 1, zone)).toInt() + 1
    }

    /**
     * The same instant one calendar year earlier — `minusYears`, not `- 365 days`.
     *
     * Subtracting a fixed number of days drifts across a leap year and lands the comparison window a day out
     * of step with the cycle, which is exactly the sort of quiet skew that makes a year-on-year figure wrong
     * by one rent payment. java.time also handles 29 Feb → 28 Feb for us.
     */
    fun oneYearEarlier(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).minusYears(1).toInstant().toEpochMilli()

    /**
     * The calendar month to name in a year-on-year comparison: the month at the **midpoint of the stretch
     * being compared**, which is `[startMillis, startMillis + elapsedMillis)`.
     *
     * A salary cycle straddles two months, so it needs a name, and the one its owner uses is the month most
     * of it sits in — 25 June to 24 July is "the July cycle" because 24 of its days are in July.
     *
     * ⭐But the month must come from the **elapsed** stretch, not the whole cycle. Taking the midpoint of the
     * full cycle names a month the user has not reached yet: on 27 July, three days into a 25 July – 25
     * August cycle, the full-cycle midpoint is 9 August and the card would read *"₹4,000 so far this
     * August"* — in July. Measuring the midpoint of what has actually happened names July on day 3 and
     * rolls over to August only once most of the elapsed days really are in August.
     */
    fun cycleMonthLabel(startMillis: Long, elapsedMillis: Long, zone: ZoneId): String {
        val mid = startMillis + elapsedMillis.coerceAtLeast(0L) / 2
        return dateOf(mid, zone).month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    }

    /**
     * Split [charges] across the complete cycles described by [boundaries] into "the first
     * [PAYDAY_WINDOW_DAYS] days" and everything else, counting days as well as money.
     *
     * [boundaries] are **real** cycle starts, descending, newest first — `boundaries[0]` is the start of the
     * cycle being viewed and prior window `k` spans `[boundaries[k + 1], boundaries[k])`. ⭐They must be real
     * rather than a fixed span subtracted repeatedly: the whole card claims that a particular seven days
     * follow payday, and with synthetic windows those seven days drift off payday by up to a fortnight as you
     * go back, so the number would describe a different week from the one the sentence names.
     *
     * **Complete cycles only, and never the current one.** A part-finished cycle would contribute its payday
     * week in full while contributing only some of its ordinary days, which by itself would manufacture a
     * payday habit for every user, every cycle, from day 8 onwards.
     */
    fun habitBuckets(
        boundaries: List<Long>,
        charges: List<DatedCharge>,
        zone: ZoneId,
    ): HabitBuckets {
        val windows = boundaries.size - 1
        if (windows <= 0) return HabitBuckets(0, 0, 0, 0, 0)

        var totalDays = 0
        var paydayDays = 0
        for (index in 0 until windows) {
            val days = cycleDays(boundaries[index + 1], boundaries[index], zone)
            totalDays += days
            paydayDays += minOf(PAYDAY_WINDOW_DAYS, days)
        }

        var totalMinor = 0L
        var paydayMinor = 0L
        val seen = BooleanArray(windows)
        for (charge in charges) {
            val index = InsightWindows.fullBucketIndexIn(boundaries, charge.occurredAt) ?: continue
            seen[index] = true
            totalMinor += charge.amountMinor
            val dayIndex = ChronoUnit.DAYS.between(
                dateOf(boundaries[index + 1], zone),
                dateOf(charge.occurredAt, zone),
            )
            if (dayIndex in 0 until PAYDAY_WINDOW_DAYS.toLong()) paydayMinor += charge.amountMinor
        }

        return HabitBuckets(
            totalMinor = totalMinor,
            totalDays = totalDays,
            paydayWeekMinor = paydayMinor,
            paydayWeekDays = paydayDays,
            windowsWithData = seen.count { it },
        )
    }
}
