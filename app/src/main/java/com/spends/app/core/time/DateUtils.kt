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
 * Storage rule: amounts of time are stored as epoch-millis (UTC). Display and all day/month/cycle math
 * happen in [ZONE] — the **device's current time zone** (#3), so cycles, day grouping, the auto-dark
 * window, the daily-backup time, and recurring all follow wherever the user actually is (not a fixed
 * IST). minSdk 26 gives us java.time natively, so no desugaring is required.
 */
object DateUtils {

    /** The device's current time zone, re-read on each access so a travel / DST change is picked up live. */
    val ZONE: ZoneId get() = ZoneId.systemDefault()

    /**
     * A FIXED zone used ONLY for dedupe day-bucketing (SMS capture / manual-vs-scan keys), never for
     * display. It stays constant regardless of the device zone so (a) hashes computed by earlier versions
     * (which always used IST) still match — no mass re-duplication — and (b) re-scanning the same SMS after
     * the user travels can't bucket it to a different day and slip past dedupe. Display/cycles use [ZONE].
     */
    private val DEDUPE_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    /** The dedupe day-bucket (epoch day) of an instant, in the fixed [DEDUPE_ZONE] — travel-stable. */
    fun dedupeEpochDay(epochMillis: Long): Long =
        Instant.ofEpochMilli(epochMillis).atZone(DEDUPE_ZONE).toLocalDate().toEpochDay()

    private val dayFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    // Timeline day-headers always carry the year (#2) so browsing older data (e.g. 2022) is never ambiguous.
    private val dayMonthFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
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
    fun formatDayTime(epochMillis: Long): String = "${formatDay(epochMillis)}, ${formatTime(epochMillis)}"
    fun formatMonth(yearMonth: YearMonth): String = monthFormatter.format(yearMonth.atDay(1))

    /**
     * Render an RFC-3339 timestamp (e.g. Google Drive's `modifiedTime`, "2026-06-25T03:34:56Z") as a
     * friendly IST date + 12-hour time. Falls back to the raw string if it can't be parsed, so the
     * restore picker never shows a blank row (#11/#12).
     */
    fun formatIsoInstant(iso: String): String =
        runCatching { formatDayTime(Instant.parse(iso).toEpochMilli()) }.getOrDefault(iso)
}
