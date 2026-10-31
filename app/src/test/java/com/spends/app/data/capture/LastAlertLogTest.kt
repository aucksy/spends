package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import com.spends.app.data.capture.LastAlertLog.Reason
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The one line under each watched app that says what happened to its last money-looking alert. */
class LastAlertLogTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun millis(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    @Test fun android_placeholder_reads_as_hidden() {
        assertThat(LastAlertLog.reasonWithoutCandidate(null, "Sensitive notification content hidden"))
            .isEqualTo(Reason.HIDDEN_BY_ANDROID)
    }

    @Test fun money_text_from_an_unknown_sender_is_named() {
        assertThat(LastAlertLog.reasonWithoutCandidate("Rs.1,250.00 spent on your SBI Credit Card ending 1234"))
            .isEqualTo(Reason.UNKNOWN_SENDER)
        assertThat(LastAlertLog.reasonWithoutCandidate("₹499 debited")).isEqualTo(Reason.UNKNOWN_SENDER)
    }

    @Test fun a_chat_or_missed_call_leaves_no_line() {
        assertThat(LastAlertLog.reasonWithoutCandidate("Missed call", "Are you coming at 5?", null)).isNull()
    }

    @Test fun encode_round_trips_and_strips_the_separator() {
        val e = LastAlertLog.Entry(1_700_000_000_000L, Reason.ADDED_TO_REVIEW, "SBI|Card")
        assertThat(LastAlertLog.decode(LastAlertLog.encode(e))).isEqualTo(e.copy(sender = "SBI Card"))
        assertThat(LastAlertLog.decode(LastAlertLog.encode(e.copy(sender = null)))?.sender).isNull()
        assertThat(LastAlertLog.decode("garbage")).isNull()
    }

    @Test fun today_shows_time_only_and_older_shows_the_date() {
        val e = LastAlertLog.Entry(millis(LocalDateTime.of(2026, 10, 31, 14, 14)), Reason.HIDDEN_BY_ANDROID, "SBI Card")
        assertThat(LastAlertLog.describe(e, LocalDate.of(2026, 10, 31), zone))
            .isEqualTo("Last alert, 2:14 pm (from SBI Card): Android hid the text, so Spends could not read it")
        assertThat(LastAlertLog.describe(e.copy(sender = null), LocalDate.of(2026, 11, 1), zone))
            .isEqualTo("Last alert, 31 Oct 2:14 pm: Android hid the text, so Spends could not read it")
    }
}
