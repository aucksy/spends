package com.spends.app.core.time

import com.spends.app.domain.model.RecurrenceFreq
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Pure date math for recurring rules — no clock access, so it's trivially testable. */
object RecurrenceMath {

    /**
     * The next occurrence strictly after [current], honouring [interval] (>=1) and, for
     * month/year cadences, clamping [anchorDay] to the target month's length (so a "31st" rule lands
     * on the last day of shorter months).
     */
    fun nextDate(current: LocalDate, freq: RecurrenceFreq, interval: Int, anchorDay: Int): LocalDate {
        val n = interval.coerceAtLeast(1).toLong()
        return when (freq) {
            RecurrenceFreq.DAILY -> current.plusDays(n)
            RecurrenceFreq.WEEKLY -> current.plusWeeks(n)
            RecurrenceFreq.MONTHLY -> {
                val ym = YearMonth.from(current).plusMonths(n)
                ym.atDay(anchorDay.coerceIn(1, ym.lengthOfMonth()))
            }
            RecurrenceFreq.YEARLY -> {
                val ym = YearMonth.from(current).plusYears(n)
                ym.atDay(anchorDay.coerceIn(1, ym.lengthOfMonth()))
            }
        }
    }

    /** The anchor (day-of-month, or day-of-week 1..7 for weekly) implied by a rule's start date. */
    fun anchorFor(freq: RecurrenceFreq, start: LocalDate): Int = when (freq) {
        RecurrenceFreq.WEEKLY -> start.dayOfWeek.value
        else -> start.dayOfMonth
    }

    /** A short human description, e.g. "Every month on the 5th" / "Every 2 weeks on Mon". */
    fun describe(freq: RecurrenceFreq, interval: Int, anchorDay: Int): String {
        val n = interval.coerceAtLeast(1)
        val every = if (n == 1) "Every" else "Every $n"
        return when (freq) {
            RecurrenceFreq.DAILY -> if (n == 1) "Every day" else "Every $n days"
            RecurrenceFreq.WEEKLY -> "$every ${if (n == 1) "week" else "weeks"} on ${weekday(anchorDay)}"
            RecurrenceFreq.MONTHLY -> "$every ${if (n == 1) "month" else "months"} on the ${ordinal(anchorDay)}"
            RecurrenceFreq.YEARLY -> if (n == 1) "Every year" else "Every $n years"
        }
    }

    private fun weekday(dow: Int): String =
        DayOfWeek.of(dow.coerceIn(1, 7)).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    private fun ordinal(day: Int): String {
        val suffix = when {
            day in 11..13 -> "th"
            day % 10 == 1 -> "st"
            day % 10 == 2 -> "nd"
            day % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$day$suffix"
    }
}
