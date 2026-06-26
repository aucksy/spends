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
) {
    /**
     * Human words for the current selection, e.g. "Current Salary Cycle", "Last 3 Months",
     * "This Month's Smart Cycle", "Custom Range", "All Time" (#5). The concrete date range is shown
     * separately as a secondary line.
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
            PeriodRange.CURRENT -> if (type == PeriodType.SMART_CYCLE) "This Month's Smart Cycle" else "Current $noun"
            PeriodRange.LAST_3 -> "Last 3 ${noun}s"
            PeriodRange.LAST_6 -> "Last 6 ${noun}s"
        }
    }
}
