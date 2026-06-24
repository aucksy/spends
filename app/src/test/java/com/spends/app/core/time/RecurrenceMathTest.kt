package com.spends.app.core.time

import com.google.common.truth.Truth.assertThat
import com.spends.app.domain.model.RecurrenceFreq
import org.junit.Test
import java.time.LocalDate

/** Pure date-math tests for recurring rule scheduling (PRD §4.8). */
class RecurrenceMathTest {

    @Test fun daily_advancesByInterval() {
        val d = LocalDate.of(2026, 6, 24)
        assertThat(RecurrenceMath.nextDate(d, RecurrenceFreq.DAILY, 1, 1)).isEqualTo(LocalDate.of(2026, 6, 25))
        assertThat(RecurrenceMath.nextDate(d, RecurrenceFreq.DAILY, 3, 1)).isEqualTo(LocalDate.of(2026, 6, 27))
    }

    @Test fun weekly_keepsWeekday() {
        val wed = LocalDate.of(2026, 6, 24) // Wednesday
        val next = RecurrenceMath.nextDate(wed, RecurrenceFreq.WEEKLY, 1, wed.dayOfWeek.value)
        assertThat(next).isEqualTo(LocalDate.of(2026, 7, 1))
        assertThat(next.dayOfWeek).isEqualTo(wed.dayOfWeek)
        assertThat(RecurrenceMath.nextDate(wed, RecurrenceFreq.WEEKLY, 2, wed.dayOfWeek.value))
            .isEqualTo(LocalDate.of(2026, 7, 8))
    }

    @Test fun monthly_normal() {
        val d = LocalDate.of(2026, 6, 15)
        assertThat(RecurrenceMath.nextDate(d, RecurrenceFreq.MONTHLY, 1, 15)).isEqualTo(LocalDate.of(2026, 7, 15))
    }

    @Test fun monthly_clampsShortMonth() {
        // Anchored on the 31st: January -> February clamps to the 28th (2026 is not a leap year).
        val jan31 = LocalDate.of(2026, 1, 31)
        assertThat(RecurrenceMath.nextDate(jan31, RecurrenceFreq.MONTHLY, 1, 31)).isEqualTo(LocalDate.of(2026, 2, 28))
        // ...then back out to the 31st when the month is long enough.
        val feb28 = LocalDate.of(2026, 2, 28)
        assertThat(RecurrenceMath.nextDate(feb28, RecurrenceFreq.MONTHLY, 1, 31)).isEqualTo(LocalDate.of(2026, 3, 31))
    }

    @Test fun yearly_clampsLeapDay() {
        val leap = LocalDate.of(2024, 2, 29)
        assertThat(RecurrenceMath.nextDate(leap, RecurrenceFreq.YEARLY, 1, 29)).isEqualTo(LocalDate.of(2025, 2, 28))
    }

    @Test fun anchorFor_dependsOnFrequency() {
        val d = LocalDate.of(2026, 6, 24) // 24th, a Wednesday (dayOfWeek 3)
        assertThat(RecurrenceMath.anchorFor(RecurrenceFreq.MONTHLY, d)).isEqualTo(24)
        assertThat(RecurrenceMath.anchorFor(RecurrenceFreq.YEARLY, d)).isEqualTo(24)
        assertThat(RecurrenceMath.anchorFor(RecurrenceFreq.WEEKLY, d)).isEqualTo(d.dayOfWeek.value)
    }

    @Test fun describe_isHumanReadable() {
        assertThat(RecurrenceMath.describe(RecurrenceFreq.MONTHLY, 1, 5)).contains("month")
        assertThat(RecurrenceMath.describe(RecurrenceFreq.WEEKLY, 2, 3)).contains("2")
        assertThat(RecurrenceMath.describe(RecurrenceFreq.DAILY, 1, 1)).isEqualTo("Every day")
    }
}
