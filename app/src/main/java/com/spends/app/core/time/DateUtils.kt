package com.spends.app.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Storage rule: amounts of time are stored as epoch-millis (UTC). Display and all
 * day/month/cycle math happen in [ZONE] (Asia/Kolkata). minSdk 26 gives us java.time natively,
 * so no desugaring is required.
 */
object DateUtils {

    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    private val dayFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val dayMonthFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
    private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val monthFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

    fun nowMillis(): Long = System.currentTimeMillis()

    fun toZdt(epochMillis: Long): ZonedDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(ZONE)

    fun toLocalDate(epochMillis: Long): LocalDate = toZdt(epochMillis).toLocalDate()

    fun toLocalDateTime(epochMillis: Long): LocalDateTime = toZdt(epochMillis).toLocalDateTime()

    /** Start-of-day epoch millis for a local date in IST. */
    fun startOfDayMillis(date: LocalDate): Long =
        date.atStartOfDay(ZONE).toInstant().toEpochMilli()

    /** Exclusive end-of-day epoch millis (start of the next day). */
    fun endOfDayExclusiveMillis(date: LocalDate): Long =
        date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()

    fun epochMillisFor(date: LocalDate, hour: Int = 12, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(ZONE).toInstant().toEpochMilli()

    /** The IST calendar date of an instant, expressed as the UTC-midnight millis a Compose
     *  DatePicker expects as its initial/selected value. */
    fun toPickerUtcMillis(epochMillis: Long): Long =
        toLocalDate(epochMillis).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Convert a Compose DatePicker selection (UTC-midnight millis) back into an IST occurredAt
     *  anchored at local noon, so the user's chosen calendar day is preserved across time zones. */
    fun fromPickerUtcMillis(utcMillis: Long): Long =
        epochMillisFor(Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate(), 12, 0)

    fun formatDay(epochMillis: Long): String = dayFormatter.format(toZdt(epochMillis))
    fun formatDay(date: LocalDate): String = dayFormatter.format(date)
    fun formatDayHeader(date: LocalDate): String = dayMonthFormatter.format(date)
    fun formatTime(epochMillis: Long): String = timeFormatter.format(toZdt(epochMillis))
    fun formatMonth(yearMonth: YearMonth): String = monthFormatter.format(yearMonth.atDay(1))
}
