package com.spends.app.core.period

import com.spends.app.core.time.DateUtils

/**
 * Auto-detects the user's salary cycle start day from the timing of their income transactions,
 * so the "Smart Cycle" period can anchor on the day money actually arrives.
 */
object SmartCycleDetector {

    /** Day boundary clamp: 1..28 so the cycle anchor exists in every month (incl. February). */
    private const val MIN_DAY = 1
    private const val MAX_DAY = 28

    /** Need at least this many income samples before trusting a detected day. */
    private const val MIN_SAMPLES = 2

    /**
     * Detect the dominant salary day-of-month from income occurredAt timestamps.
     *
     * @param incomeOccurredAtMillis epoch-millis of INCOME transactions (largest/most-frequent income).
     * @param fallbackDay returned when detection isn't possible (fewer than [MIN_SAMPLES] samples).
     * @return the most common day-of-month, clamped to 1..28 so the cycle boundary exists every
     *   month; ties resolve to the smaller day. Returns [fallbackDay] when there are < 2 samples.
     */
    fun detectSalaryDay(incomeOccurredAtMillis: List<Long>, fallbackDay: Int): Int {
        if (incomeOccurredAtMillis.size < MIN_SAMPLES) return fallbackDay

        // Tally clamped day-of-month across all samples.
        val counts = HashMap<Int, Int>()
        for (millis in incomeOccurredAtMillis) {
            val day = clamp(DateUtils.toLocalDate(millis).dayOfMonth)
            counts[day] = (counts[day] ?: 0) + 1
        }

        // Mode; ties broken toward the smaller day.
        var bestDay = fallbackDay
        var bestCount = -1
        for ((day, count) in counts) {
            if (count > bestCount || (count == bestCount && day < bestDay)) {
                bestDay = day
                bestCount = count
            }
        }
        return bestDay
    }

    private fun clamp(day: Int): Int = day.coerceIn(MIN_DAY, MAX_DAY)
}
