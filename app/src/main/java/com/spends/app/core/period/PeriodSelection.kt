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
)
