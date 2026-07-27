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
 *
 * ## Why cycles arrive as explicit boundaries
 * ⭐The first version of this walked backwards by subtracting a **fixed span** — the current cycle's length —
 * six times. Real cycles are 28, 30 or 31 days long, so those synthetic windows slide further off the real
 * cycle boundaries the further back they go: for a salary day of 1, February drags every earlier boundary
 * days out of step. The rent paid on the 1st then falls near the *end* of a drifted window, past the point
 * this cycle has reached, and drops out of the baseline entirely — while this cycle's rent is still counted.
 * The result is the exact bug the day-alignment above exists to prevent, arriving by a different route:
 *
 * > "Day 12 of the cycle and ₹36,000 spent. Your recent cycles were at ₹11,000 by this point."
 *
 * They were at ₹36,000 too. So the caller now passes the **real** cycle boundaries, produced by the same
 * `CycleUtils`/`PeriodResolver` machinery that drew the window on screen. There is deliberately no
 * fixed-span entry point left to reach for.
 */
object InsightWindows {

    /**
     * Which prior cycle [occurredAt] falls in, or null if it is outside the compared span.
     *
     * [boundaries] are real cycle starts in **descending** order, newest first: `boundaries[0]` is the start
     * of the cycle being viewed, and prior window `k` spans `[boundaries[k + 1], boundaries[k])`. Window 0 is
     * the cycle immediately before the current one.
     */
    fun fullBucketIndexIn(boundaries: List<Long>, occurredAt: Long): Int? {
        if (boundaries.size < 2 || occurredAt >= boundaries[0]) return null
        for (index in 0 until boundaries.size - 1) {
            // Half-open [start, end): a charge at exactly a cycle's start belongs to that cycle.
            if (occurredAt >= boundaries[index + 1]) return index
        }
        return null
    }

    /**
     * As [fullBucketIndexIn], but only when the charge landed no further into its own cycle than we have
     * reached in this one — the day-aligned comparison the class KDoc describes.
     *
     * @param elapsedMillis how far into the current cycle we are; equals its full length once complete.
     */
    fun bucketIndexIn(boundaries: List<Long>, occurredAt: Long, elapsedMillis: Long): Int? {
        val index = fullBucketIndexIn(boundaries, occurredAt) ?: return null
        return if (occurredAt - boundaries[index + 1] < elapsedMillis) index else null
    }
}
