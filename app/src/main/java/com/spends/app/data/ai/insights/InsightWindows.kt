package com.spends.app.data.ai.insights

/**
 * Slots a historical charge into the prior cycle it belongs to — **and only if it happened at the same point
 * in that cycle as we have reached in this one**.
 *
 * ## Why the second half matters
 * The obvious way to compare a part-finished cycle against six complete ones is to scale the old figures
 * down: four days into thirty, multiply the old totals by 4/30. That silently assumes spending is spread
 * evenly through a cycle. Rent, EMI, insurance and school fees are the exact opposite — one big charge on a
 * fixed day.
 *
 * With scaling, a user who pays ₹20,000 rent on day 1 gets told on day 3:
 *
 * > "Rent is ₹20,000 this cycle, against ₹2,000 in a usual one — about 10× as much."
 *
 * They know rent is ₹20,000 every month. The ₹2,000 is a figure the app made up, and it would say this for
 * most of every cycle. The mirror case is just as bad: on day 25, before rent is due, the same maths produces
 * a cheerful "Rent is ₹0, down from ₹16,667".
 *
 * So instead of scaling the answer, this narrows the question: compare the first N days of this cycle against
 * **the first N days of each previous cycle**. Rent-on-day-1 then appears in both sides and nothing is
 * invented — the baseline is a real figure the user actually spent, and "usually ₹X by this point" is true.
 */
object InsightWindows {

    /**
     * Which prior window [occurredAt] belongs to, or null if it is outside the compared span or falls later
     * in its cycle than we have yet reached in the current one.
     *
     * Window 0 is the cycle immediately before the current one; window `count - 1` is the oldest.
     *
     * @param currentStart  start of the cycle being viewed
     * @param span          cycle length in millis
     * @param elapsedMillis how far into the current cycle we are; equals [span] for a completed cycle
     */
    fun bucketIndex(
        currentStart: Long,
        span: Long,
        elapsedMillis: Long,
        occurredAt: Long,
        count: Int,
    ): Int? {
        if (span <= 0L || occurredAt >= currentStart) return null
        // The `- 1` makes each window half-open [start, end): a charge at exactly a window's start belongs to
        // that window, not the one before it.
        val index = ((currentStart - occurredAt - 1) / span).toInt()
        if (index !in 0 until count) return null
        val windowStart = currentStart - (index + 1) * span
        return if (occurredAt - windowStart < elapsedMillis) index else null
    }
}
