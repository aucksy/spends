package com.spends.app.data.ai.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Boundary maths for the "same point in a previous cycle" comparison.
 *
 * An off-by-one here silently attributes a charge to the wrong month, which then shifts a median, which then
 * changes what the app tells the user is unusual. Worth pinning exactly.
 */
class InsightWindowsTest {

    private val span = 30L * 24 * 60 * 60 * 1000 // a 30-day cycle
    private val start = 1_000_000_000_000L
    private val count = 6

    private fun index(occurredAt: Long, elapsed: Long = span) =
        InsightWindows.bucketIndex(start, span, elapsed, occurredAt, count)

    @Test
    fun `windows are half-open so a charge at a boundary belongs to the later one`() {
        assertEquals(0, index(start - 1))          // last instant before the current cycle
        assertEquals(0, index(start - span))       // first instant of the previous cycle
        assertEquals(1, index(start - span - 1))   // one instant earlier is the window before that
        assertEquals(5, index(start - span * 6))   // the oldest compared window
    }

    @Test
    fun `anything outside the compared span is ignored`() {
        assertNull("the current cycle is not history", index(start))
        assertNull("later than the current cycle start", index(start + 1))
        assertNull("older than the compared span", index(start - span * 6 - 1))
    }

    @Test
    fun `only the matching part of each past cycle counts when this one is unfinished`() {
        // ⭐The reason this class exists. Four days into a thirty-day cycle we compare against the FIRST FOUR
        // DAYS of each previous cycle — not against whole ones scaled down. Rent paid on day 1 then appears
        // on both sides, instead of the app inventing a "usual rent" of a tenth the real figure.
        val fourDays = 4L * 24 * 60 * 60 * 1000
        val previousStart = start - span

        // Day 1 of the previous cycle — inside the matching stretch.
        assertEquals(0, index(previousStart + 1000L, elapsed = fourDays))
        // Day 3 — still inside.
        assertEquals(0, index(previousStart + 3 * 24 * 60 * 60 * 1000L, elapsed = fourDays))
        // Day 20 of the previous cycle — beyond where this cycle has reached, so it must not count.
        assertNull(index(previousStart + 20L * 24 * 60 * 60 * 1000, elapsed = fourDays))
    }

    @Test
    fun `a completed cycle compares against whole previous cycles`() {
        val previousStart = start - span
        assertEquals(0, index(previousStart + 29L * 24 * 60 * 60 * 1000, elapsed = span))
    }

    @Test
    fun `a zero or negative span is refused rather than dividing by zero`() {
        assertNull(InsightWindows.bucketIndex(start, 0L, 0L, start - 1, count))
        assertNull(InsightWindows.bucketIndex(start, -5L, 0L, start - 1, count))
    }
}
