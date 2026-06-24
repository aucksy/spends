package com.spends.app.domain.allocation

/**
 * Split a total amount (paise) across N parts so the parts **always sum exactly to the total**
 * (PRD §4.6). Uses the largest-remainder method to distribute leftover paise, so ₹5000 across 3
 * categories reconciles to exactly ₹5000. Pure integer arithmetic — no Float/Double.
 */
object AllocationMath {

    /** Equal split: total / n with the remainder paise handed to the earliest parts. */
    fun splitEqual(total: Long, parts: Int): LongArray {
        require(parts > 0) { "parts must be > 0" }
        return splitByWeights(total, LongArray(parts) { 1L })
    }

    /** Percentage split: weights are the integer percentages (need not sum to 100). */
    fun splitByPercent(total: Long, percents: IntArray): LongArray =
        splitByWeights(total, LongArray(percents.size) { percents[it].toLong() })

    /**
     * Weighted split with largest-remainder rounding. Each part gets floor(total*w/Σw); the
     * leftover paise are given one-by-one to the parts with the largest fractional remainders
     * (ties broken by index, so the result is deterministic).
     */
    fun splitByWeights(total: Long, weights: LongArray): LongArray {
        require(weights.isNotEmpty()) { "weights must not be empty" }
        require(weights.all { it >= 0 }) { "weights must be non-negative" }
        val sumW = weights.sum()
        val n = weights.size
        val result = LongArray(n)
        if (sumW == 0L) {
            // No weights: fall back to an equal split so we still reconcile.
            return splitByWeights(total, LongArray(n) { 1L })
        }
        val remainders = LongArray(n)
        var allocated = 0L
        for (i in 0 until n) {
            val numerator = total * weights[i]
            val q = Math.floorDiv(numerator, sumW)
            result[i] = q
            remainders[i] = numerator - q * sumW
            allocated += q
        }
        var leftover = total - allocated
        if (leftover != 0L) {
            // Order indices by remainder desc, then index asc; distribute ±1 paise.
            val order = (0 until n).sortedWith(
                compareByDescending<Int> { remainders[it] }.thenBy { it }
            )
            val step = if (leftover > 0) 1L else -1L
            var idx = 0
            var remaining = Math.abs(leftover)
            while (remaining > 0) {
                result[order[idx % n]] += step
                idx++
                remaining--
            }
            leftover = 0
        }
        return result
    }

    /** True when [parts] reconcile exactly to [total]. */
    fun reconciles(total: Long, parts: LongArray): Boolean = parts.sum() == total
}
