package com.spends.app.core.time

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * The days salary ACTUALLY arrived, so a salary cycle runs from one real salary to the next.
 *
 * A cycle exists to answer "how much of this salary have I spent?". With a fixed salary day of the 1st, a
 * salary that lands early on 30 Oct falls into October: October shows two salaries and November none, and
 * the spends of 30-31 Oct (paid from the new salary) count against the old one. A salary that lands late
 * does the mirror image. So each month's cycle starts on the day that month's salary came; a month whose
 * salary has not come yet starts on the usual day, and moves the moment it arrives.
 *
 * "That month's salary" = the arrival closest to the month's usual day, within [MAX_SHIFT_DAYS] either way
 * (a bonus mid-month is not a salary day). Small credits under half the typical salary are ignored, so a
 * reimbursement filed under Salary cannot move a cycle.
 *
 * [CycleUtils.windowFor] reads [active] for the salary anchor, so every screen, the widget and the backup
 * export follow the same dates. The settings flow publishes it BEFORE it emits, so a screen that
 * recomputes on a settings change always sees the dates that came with that change. A card whose bill day
 * equals the salary day moves with it too (the calendar is keyed by day number) — rare, and harmless.
 */
object SalaryDates {

    const val MAX_SHIFT_DAYS = 10

    /** The anchor day these starts belong to, plus the per-month start dates. Empty = plain fixed day. */
    data class Calendar(val usualDay: Int = 0, val starts: Map<YearMonth, LocalDate> = emptyMap()) {
        /** The start of [ym]'s cycle for [anchorDay]: the real arrival when known, else the usual day. */
        fun startOf(ym: YearMonth, anchorDay: Int): LocalDate =
            (if (anchorDay == usualDay) starts[ym] else null) ?: CycleUtils.clampDay(ym, anchorDay)
    }

    @Volatile
    var active: Calendar = Calendar()
        private set

    fun publish(calendar: Calendar) {
        active = calendar
    }

    /**
     * Per-month cycle starts from salary credits ([date] to amount in minor units) for a [usualDay]. PURE.
     * Each credit goes to the month whose usual day is nearest; the earliest credit wins a month.
     */
    fun derive(credits: List<Pair<LocalDate, Long>>, usualDay: Int): Map<YearMonth, LocalDate> {
        if (usualDay !in 1..31 || credits.isEmpty()) return emptyMap()
        val sorted = credits.map { it.second }.sorted()
        val typical = sorted[sorted.size / 2]
        val starts = HashMap<YearMonth, LocalDate>()
        for ((date, amount) in credits) {
            if (amount * 2 < typical) continue
            val own = YearMonth.from(date)
            val best = listOf(own.minusMonths(1), own, own.plusMonths(1))
                .minByOrNull { abs(ChronoUnit.DAYS.between(CycleUtils.clampDay(it, usualDay), date)) }!!
            if (abs(ChronoUnit.DAYS.between(CycleUtils.clampDay(best, usualDay), date)) > MAX_SHIFT_DAYS) continue
            val prev = starts[best]
            if (prev == null || date.isBefore(prev)) starts[best] = date
        }
        return starts
    }

    /** "1|2026-11=2026-10-30;2026-12=2026-12-01" — the usual day, then each month's start. PURE. */
    fun encode(cal: Calendar): String =
        "${cal.usualDay}|" + cal.starts.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    /** Inverse of [encode]; anything unreadable is dropped rather than trusted. PURE. */
    fun decode(raw: String?): Calendar {
        if (raw.isNullOrBlank()) return Calendar()
        val day = raw.substringBefore('|').toIntOrNull() ?: return Calendar()
        val starts = raw.substringAfter('|', "").split(';').mapNotNull { e ->
            runCatching { YearMonth.parse(e.substringBefore('=')) to LocalDate.parse(e.substringAfter('=')) }.getOrNull()
        }.toMap()
        return Calendar(day, starts)
    }

    /** The latest salary credit date, for spotting the usual day when the owner did not know it. PURE. */
    fun latestSalaryDay(credits: List<Pair<LocalDate, Long>>): LocalDate? {
        if (credits.isEmpty()) return null
        val sorted = credits.map { it.second }.sorted()
        val typical = sorted[sorted.size / 2]
        return credits.filter { it.second * 2 >= typical }.maxOfOrNull { it.first }
    }
}
