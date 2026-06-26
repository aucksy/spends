package com.spends.app.core.period

/**
 * What the user has chosen in the cycle selector: a [type] (Month / Salary cycle / Smart cycle) crossed
 * with a [range] (All / Current / Last 3 / Last 6 / Custom). Custom carries its own date bounds.
 */
data class PeriodSelection(
    val type: PeriodType = PeriodType.SALARY_CYCLE,
    val range: PeriodRange = PeriodRange.CURRENT,
    val customStartMillis: Long? = null,
    val customEndExclusiveMillis: Long? = null,
    // How many single cycles away from "current" the prev/next arrows have stepped (0 = current,
    // -1 = previous, +1 = next). Only meaningful for [PeriodRange.CURRENT] (#6).
    val cycleOffset: Int = 0,
) {
    /** Prev/next cycle stepping only makes sense for a single current cycle (not All / Last-N / Custom). */
    val isNavigable: Boolean get() = range == PeriodRange.CURRENT

    /**
     * Human words for the current selection, e.g. "Current Salary Cycle", "Last 3 Months",
     * "This Month's Smart Cycle", "Custom Range", "All Time" (#5). With the prev/next arrows it also
     * reads "Previous Salary Cycle" / "Next Month" / "3 Salary Cycles ago" (#6). The concrete date range
     * is shown separately as a secondary line.
     */
    fun describe(): String {
        val noun = when (type) {
            PeriodType.MONTH -> "Month"
            PeriodType.SALARY_CYCLE -> "Salary Cycle"
            PeriodType.SMART_CYCLE -> "Smart Cycle"
        }
        return when (range) {
            PeriodRange.ALL -> "All Time"
            PeriodRange.CUSTOM -> "Custom Range"
            PeriodRange.LAST_3 -> "Last 3 ${noun}s"
            PeriodRange.LAST_6 -> "Last 6 ${noun}s"
            PeriodRange.CURRENT -> when {
                cycleOffset == 0 -> if (type == PeriodType.SMART_CYCLE) "This Month's Smart Cycle" else "Current $noun"
                cycleOffset == -1 -> "Previous $noun"
                cycleOffset == 1 -> "Next $noun"
                cycleOffset < 0 -> "${-cycleOffset} ${noun}s ago"
                else -> "$cycleOffset ${noun}s ahead"
            }
        }
    }
}
