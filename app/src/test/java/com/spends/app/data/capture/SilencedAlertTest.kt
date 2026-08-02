package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import com.spends.app.data.db.entity.IgnoredPatternEntity
import com.spends.app.domain.model.TxnKind
import org.junit.Test

/**
 * The other side of the learn-from-ignore door (#7 follow-up). These keys were only ever written, so
 * every case here is a first read of a format that has been shipping since v0.x.
 */
class SilencedAlertTest {

    private val t0 = 1_700_000_000_000L

    private fun row(key: String, count: Int = 3) = IgnoredPatternEntity(key, count, t0)

    @Test fun decodes_the_ordinary_key_written_by_ignoreKey() {
        val a = SilencedAlert.decode(row("AXISBK|swiggy|45000|EXPENSE"))
        assertThat(a.sender).isEqualTo("AXISBK")
        assertThat(a.who).isEqualTo("swiggy")
        assertThat(a.amountMinor).isEqualTo(45_000L)
        assertThat(a.kind).isEqualTo(TxnKind.EXPENSE)
    }

    @Test fun title_reads_as_money_at_merchant() {
        assertThat(SilencedAlert.decode(row("AXISBK|swiggy|45000|EXPENSE")).title())
            .isEqualTo("₹450.00 at Swiggy")
    }

    @Test fun income_reads_as_from_not_at() {
        assertThat(SilencedAlert.decode(row("HDFCBK|acme payroll|5000000|INCOME")).title())
            .isEqualTo("₹50,000.00 from Acme Payroll")
    }

    /** The merchant is a verbatim slice of the bank's text, so one day it WILL contain the separator.
     *  Right-anchored decoding keeps amount and kind correct; the stray pipe only widens `who`. */
    @Test fun a_merchant_containing_a_pipe_does_not_shift_the_other_fields() {
        val a = SilencedAlert.decode(row("AXISBK|shop|near|market|45000|EXPENSE"))
        assertThat(a.who).isEqualTo("shop|near|market")
        assertThat(a.amountMinor).isEqualTo(45_000L)
        assertThat(a.kind).isEqualTo(TxnKind.EXPENSE)
    }

    /** ignoreKey writes an empty merchant when neither merchant nor institution parsed. A sender
     *  header is already upper-case and must stay that way — "AXISBK" is what the owner recognises. */
    @Test fun an_empty_merchant_falls_back_to_the_sender() {
        val a = SilencedAlert.decode(row("AXISBK||45000|EXPENSE"))
        assertThat(a.who).isEmpty()
        assertThat(a.title()).isEqualTo("₹450.00 at AXISBK")
    }

    @Test fun a_zero_amount_still_renders_something_actionable() {
        assertThat(SilencedAlert.decode(row("AXISBK|swiggy|0|EXPENSE")).title()).isEqualTo("Swiggy")
    }

    /** A key this code cannot read is exactly the one that would otherwise silence an alert forever
     *  with no way out, so decoding must never throw and never drop the row. */
    @Test fun an_unparseable_key_still_yields_a_row_the_owner_can_act_on() {
        val a = SilencedAlert.decode(row("garbage"))
        assertThat(a.patternKey).isEqualTo("garbage")
        assertThat(a.amountMinor).isEqualTo(0L)
        assertThat(a.kind).isNull()
        assertThat(a.title()).isEqualTo("Garbage")
    }

    @Test fun an_unknown_kind_decodes_to_null_rather_than_throwing() {
        assertThat(SilencedAlert.decode(row("AXISBK|swiggy|45000|TRANSFER")).kind).isNull()
    }

    @Test fun a_non_numeric_amount_decodes_to_zero_rather_than_throwing() {
        assertThat(SilencedAlert.decode(row("AXISBK|swiggy|NaN|EXPENSE")).amountMinor).isEqualTo(0L)
    }

    // ---- the threshold, which is what the screen actually reports ----

    @Test fun below_the_threshold_is_not_silenced_and_counts_down() {
        val a = SilencedAlert.decode(row("AXISBK|swiggy|45000|EXPENSE", count = 2))
        assertThat(a.isSilenced).isFalse()
        assertThat(a.ignoresUntilSilenced).isEqualTo(1)
    }

    @Test fun at_the_threshold_it_is_silenced() {
        val a = SilencedAlert.decode(
            row("AXISBK|swiggy|45000|EXPENSE", count = SmsCaptureRepository.IGNORE_SUPPRESS_THRESHOLD),
        )
        assertThat(a.isSilenced).isTrue()
        assertThat(a.ignoresUntilSilenced).isEqualTo(0)
    }

    /** Past the threshold the countdown must not go negative and render "-2 more Ignores". */
    @Test fun past_the_threshold_the_countdown_floors_at_zero() {
        assertThat(SilencedAlert.decode(row("AXISBK|swiggy|45000|EXPENSE", count = 9)).ignoresUntilSilenced)
            .isEqualTo(0)
    }

    /** The sender line is suppressed when it would only repeat the headline. */
    @Test fun the_sender_line_is_hidden_when_it_is_already_the_headline() {
        assertThat(SilencedAlert.decode(row("AXISBK||45000|EXPENSE")).senderLine()).isNull()
        assertThat(SilencedAlert.decode(row("AXISBK|swiggy|45000|EXPENSE")).senderLine()).isEqualTo("AXISBK")
    }
}
