package com.spends.app.data.ai.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Boundary maths for the "same point in a previous cycle" comparison.
 *
 * An off-by-one here silently attributes a charge to the wrong month, which then shifts a median, which then
 * changes what the app tells the user is unusual. Worth pinning exactly — and the cycles below are REAL
 * calendar cycles of 28, 30 and 31 days, because the bug this class was rewritten to kill was precisely the
 * assumption that they are all the same length.
 */
class InsightWindowsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val day = 24L * 60 * 60 * 1000

    private fun at(date: String): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Salary cycles anchored on the 1st, newest first. The current cycle is 1 June – 1 July (30 days) and the
     * six before it run 31, 30, 31, **28**, 31, 31 days. Nothing about this sequence is uniform, which is the
     * point.
     */
    private val boundaries = listOf(
        at("2026-06-01"), at("2026-05-01"), at("2026-04-01"), at("2026-03-01"),
        at("2026-02-01"), at("2026-01-01"), at("2025-12-01"),
    )

    @Test
    fun `windows are half-open so a charge at a boundary belongs to the later one`() {
        assertEquals(0, InsightWindows.fullBucketIndexIn(boundaries, at("2026-06-01") - 1))
        assertEquals(0, InsightWindows.fullBucketIndexIn(boundaries, at("2026-05-01")))
        assertEquals(1, InsightWindows.fullBucketIndexIn(boundaries, at("2026-05-01") - 1))
        assertEquals(5, InsightWindows.fullBucketIndexIn(boundaries, at("2025-12-01")))
    }

    @Test
    fun `anything outside the compared span is ignored`() {
        assertNull("the current cycle is not history", InsightWindows.fullBucketIndexIn(boundaries, at("2026-06-01")))
        assertNull("later than the current cycle start", InsightWindows.fullBucketIndexIn(boundaries, at("2026-06-02")))
        assertNull("older than the compared span", InsightWindows.fullBucketIndexIn(boundaries, at("2025-12-01") - 1))
    }

    @Test
    fun `only the matching part of each past cycle counts when this one is unfinished`() {
        // ⭐The reason this class exists. Four days into a cycle we compare against the FIRST FOUR DAYS of each
        // previous cycle — not against whole ones scaled down. Rent paid on day 1 then appears on both sides,
        // instead of the app inventing a "usual rent" of a tenth the real figure.
        val fourDays = 4 * day
        assertEquals(0, InsightWindows.bucketIndexIn(boundaries, at("2026-05-01"), fourDays))
        assertEquals(0, InsightWindows.bucketIndexIn(boundaries, at("2026-05-04"), fourDays))
        assertNull("day 21 is beyond where this cycle has reached", InsightWindows.bucketIndexIn(boundaries, at("2026-05-21"), fourDays))
    }

    @Test
    fun `a charge on the first day of every past cycle survives, whatever those cycles were worth in days`() {
        // ⭐REGRESSION GUARD for the blocker that stopped the first cut of this shipping.
        //
        // The original walked back by subtracting the CURRENT cycle's length repeatedly. June is 30 days and
        // the cycles behind it are 31, 30, 31, 28, 31, 31 — so those synthetic boundaries slid further out of
        // step every step back, and February dragged them days adrift. A ₹25,000 rent paid on the 1st then
        // landed near the END of a drifted window, past the point this cycle had reached, and vanished from
        // the baseline — while THIS cycle's rent was still counted. The card said "your recent cycles were at
        // ₹11,000 by this point" about cycles that were at ₹36,000.
        //
        // With real boundaries, a first-of-the-cycle charge sits at offset zero in its own window and clears
        // even the tightest elapsed gate. Every prior cycle, every month length.
        val oneDay = 1 * day
        for (index in 0 until boundaries.size - 1) {
            val firstDayOfThatCycle = boundaries[index + 1]
            assertEquals(
                "a day-one charge fell out of window $index",
                index,
                InsightWindows.bucketIndexIn(boundaries, firstDayOfThatCycle, oneDay),
            )
        }
    }

    @Test
    fun `the full bucketing keeps what day-alignment throws away`() {
        // Six cycles of complete history is a different question from "how are we doing so far", and needs a
        // different answer. Four days in, a charge from day 21 of the previous cycle is correctly invisible to
        // the pace comparison and correctly present in the six-cycle trend.
        val fourDays = 4 * day
        assertNull(InsightWindows.bucketIndexIn(boundaries, at("2026-05-21"), fourDays))
        assertEquals(0, InsightWindows.fullBucketIndexIn(boundaries, at("2026-05-21")))
    }

    @Test
    fun `boundaries that do not describe at least one cycle are refused`() {
        assertNull(InsightWindows.fullBucketIndexIn(emptyList(), at("2026-05-01")))
        assertNull(InsightWindows.fullBucketIndexIn(listOf(at("2026-06-01")), at("2026-05-01")))
        assertNull(InsightWindows.bucketIndexIn(emptyList(), at("2026-05-01"), day))
    }
}
