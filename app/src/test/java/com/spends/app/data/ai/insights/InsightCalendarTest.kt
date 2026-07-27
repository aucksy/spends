package com.spends.app.data.ai.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The calendar arithmetic the over-time cards stand on.
 *
 * Every case here is a real date rather than a round number of milliseconds, because the bugs this catches
 * are all shape-of-the-calendar bugs: a cycle that straddles two months being named after the wrong one, a
 * leap day, a comparison window drifting a day out of step across a year.
 */
class InsightCalendarTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val day = 24L * 60 * 60 * 1000

    private fun at(date: String): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `the first day of a cycle is day one, not day zero`() {
        assertEquals(1, InsightCalendar.daysElapsed(at("2026-07-25"), at("2026-07-25"), zone))
        assertEquals(12, InsightCalendar.daysElapsed(at("2026-07-25"), at("2026-08-05"), zone))
    }

    @Test
    fun `a cycle that has not begun reports no elapsed days`() {
        // Reachable: card spends rolled forward make the next cycle navigable before it starts.
        assertTrue(InsightCalendar.daysElapsed(at("2026-08-25"), at("2026-07-25"), zone) < 1)
    }

    @Test
    fun `cycle length counts whole days and excludes the end boundary`() {
        // 25 June to 25 July is 30 days, not 31 — the end is exclusive.
        assertEquals(30, InsightCalendar.cycleDays(at("2026-06-25"), at("2026-07-25"), zone))
        // February in a leap year, so the length comes from the calendar rather than an assumed 30.
        assertEquals(29, InsightCalendar.cycleDays(at("2024-02-01"), at("2024-03-01"), zone))
        assertEquals(0, InsightCalendar.cycleDays(at("2026-07-25"), at("2026-07-25"), zone))
    }

    @Test
    fun `a year earlier is a calendar year, not 365 days`() {
        assertEquals(at("2025-07-25"), InsightCalendar.oneYearEarlier(at("2026-07-25"), zone))
        // The case that makes it matter: subtracting 365 days from a leap day lands on 1 March, putting the
        // whole comparison window a day out of step — enough to move one rent payment across the boundary.
        assertEquals(at("2023-02-28"), InsightCalendar.oneYearEarlier(at("2024-02-29"), zone))
    }

    @Test
    fun `a cycle straddling two months is named after the one it mostly sits in`() {
        // 25 June to 25 July has 24 of its 30 days in July, and its owner calls it the July cycle. Naming it
        // from its start date would call it June and make the year-on-year card read wrong.
        assertEquals("July", InsightCalendar.cycleMonthLabel(at("2026-06-25"), 30 * day, zone))
        assertEquals("July", InsightCalendar.cycleMonthLabel(at("2026-07-01"), 31 * day, zone))
        assertEquals("January", InsightCalendar.cycleMonthLabel(at("2026-01-01"), 31 * day, zone))
    }

    @Test
    fun `the month named is one the user has actually reached`() {
        // ⭐REGRESSION GUARD. Taking the midpoint of the WHOLE cycle names a month that hasn't happened yet:
        // three days into a 25 July – 25 August cycle the full-cycle midpoint is 9 August, so the card read
        // "₹4,000 so far this August" — on the 27th of July. Measuring the elapsed stretch instead says July
        // on day 3 and only rolls over once most of what has happened really is in August.
        assertEquals("July", InsightCalendar.cycleMonthLabel(at("2026-07-25"), 3 * day, zone))
        assertEquals("August", InsightCalendar.cycleMonthLabel(at("2026-07-25"), 26 * day, zone))
    }

    @Test
    fun `payday buckets split money and days across complete prior cycles`() {
        // Real salary cycles anchored on the 25th: 25 June – 25 July is 30 days, 25 May – 25 June is 31.
        val boundaries = listOf(at("2026-07-25"), at("2026-06-25"), at("2026-05-25"))
        val charges = listOf(
            // Window 0 runs 25 June to 25 July.
            DatedCharge(at("2026-06-25"), 1_000_00L), // day 0 — payday week
            DatedCharge(at("2026-06-27"), 1_000_00L), // day 2 — payday week
            DatedCharge(at("2026-07-10"), 1_000_00L), // day 15 — not
            // Window 1 runs 25 May to 25 June.
            DatedCharge(at("2026-05-26"), 1_000_00L), // day 1 — payday week
            DatedCharge(at("2026-06-20"), 1_000_00L), // day 26 — not
            // Outside the compared span in both directions.
            DatedCharge(at("2026-07-26"), 9_999_00L), // in the CURRENT cycle
            DatedCharge(at("2026-05-01"), 9_999_00L), // older than the compared span
        )

        val buckets = InsightCalendar.habitBuckets(boundaries, charges, zone)

        assertEquals(5_000_00L, buckets.totalMinor)
        assertEquals(3_000_00L, buckets.paydayWeekMinor)
        // 30 + 31, taken from the calendar rather than assumed equal — the whole reason boundaries are passed in.
        assertEquals(61, buckets.totalDays)
        assertEquals(14, buckets.paydayWeekDays)
        assertEquals(2, buckets.windowsWithData)
    }

    @Test
    fun `the payday week follows payday in every cycle, not a drifting seven days`() {
        // ⭐REGRESSION GUARD for the blocker. With windows built by subtracting a fixed span, the "week after
        // payday" slid off payday by up to a fortnight as you went back, so the percentage described a
        // different week from the one the card's sentence names. Here a charge on the 25th — payday — of each
        // of the six prior cycles must ALL land in the payday bucket.
        val boundaries = listOf(
            at("2026-07-25"), at("2026-06-25"), at("2026-05-25"), at("2026-04-25"),
            at("2026-03-25"), at("2026-02-25"), at("2026-01-25"),
        )
        val paydayCharges = boundaries.drop(1).map { DatedCharge(it, 1_000_00L) }
        val buckets = InsightCalendar.habitBuckets(boundaries, paydayCharges, zone)

        assertEquals("every payday charge must be in the payday week", 6_000_00L, buckets.paydayWeekMinor)
        assertEquals(6_000_00L, buckets.totalMinor)
        assertEquals(6, buckets.windowsWithData)
        // 30 + 31 + 30 + 31 + 28 + 31 — February included, and counted as itself.
        assertEquals(181, buckets.totalDays)
    }

    @Test
    fun `cycles with no spending still count their days but not as data`() {
        // The day denominator has to stay honest for cycles the user simply didn't record — but a cycle with
        // nothing in it is not evidence of a habit, which is what windowsWithData gates on.
        val boundaries = listOf(at("2026-07-25"), at("2026-06-25"), at("2026-05-25"), at("2026-04-25"))
        val buckets = InsightCalendar.habitBuckets(
            boundaries,
            listOf(DatedCharge(at("2026-06-26"), 5_000_00L)),
            zone,
        )
        assertEquals(91, buckets.totalDays) // 30 + 31 + 30
        assertEquals(21, buckets.paydayWeekDays)
        assertEquals(1, buckets.windowsWithData)
        assertEquals(5_000_00L, buckets.paydayWeekMinor)
    }

    @Test
    fun `boundaries describing no complete cycle produce empty buckets rather than a divide by zero`() {
        val charges = listOf(DatedCharge(at("2026-06-26"), 5_000_00L))
        assertEquals(0, InsightCalendar.habitBuckets(emptyList(), charges, zone).totalDays)
        assertEquals(0, InsightCalendar.habitBuckets(listOf(at("2026-07-25")), charges, zone).totalDays)
    }
}
