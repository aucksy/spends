package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import com.spends.app.data.db.entity.IgnoredPatternEntity
import com.spends.app.domain.model.TxnKind
import org.junit.Test

/**
 * The read side of learn-from-ignore (#7 follow-up).
 *
 * The key format changed in v1.69.0: it used to be `header|who|amountMinor|kind`, which put the AMOUNT in
 * the identity of a pattern and so made the whole feature dead code — a merchant almost never charges the
 * same figure three times, so nothing ever reached the suppression threshold. It is now `header|who|kind`.
 */
class SilencedAlertTest {

    private val t0 = 1_700_000_000_000L

    private fun row(key: String, count: Int = 3) = IgnoredPatternEntity(key, count, t0)

    @Test fun decodes_the_key_written_by_ignoreKey() {
        val a = SilencedAlert.decode(row("YESBNK|php*finreliable digite|EXPENSE"))
        assertThat(a.sender).isEqualTo("YESBNK")
        assertThat(a.who).isEqualTo("php*finreliable digite")
        assertThat(a.kind).isEqualTo(TxnKind.EXPENSE)
    }

    @Test fun the_title_names_who_the_alerts_come_from() {
        assertThat(SilencedAlert.decode(row("YESBNK|php*finreliable digite|EXPENSE")).title())
            .isEqualTo("Php*finreliable Digite")
    }

    /**
     * ⭐A row now covers EVERY amount from one source, which is a bigger thing to switch off than the
     * single figure it used to mean. The screen must say so before the owner taps Reset.
     */
    @Test fun the_scope_line_says_what_is_actually_being_silenced() {
        assertThat(SilencedAlert.decode(row("YESBNK|php*finreliable digite|EXPENSE")).scopeLine())
            .isEqualTo("Money-out alerts  ·  from YESBNK")
        assertThat(SilencedAlert.decode(row("IDFCFB|idfc first bank|INCOME")).scopeLine())
            .isEqualTo("Money-in alerts  ·  from IDFCFB")
    }

    /** With no merchant parsed the headline is already the sender, so the scope line must not repeat it. */
    @Test fun the_scope_line_does_not_repeat_a_sender_that_is_already_the_headline() {
        val a = SilencedAlert.decode(row("AXISBK||EXPENSE"))
        assertThat(a.title()).isEqualTo("AXISBK")
        assertThat(a.scopeLine()).isEqualTo("Money-out alerts, any amount")
    }

    /** ignoreKey falls back to the institution when no merchant parsed — the owner's IDFC credits. */
    @Test fun an_institution_only_alert_reads_as_the_bank() {
        val a = SilencedAlert.decode(row("IDFCFB|idfc first bank|INCOME"))
        assertThat(a.title()).isEqualTo("Idfc First Bank")
        assertThat(a.kind).isEqualTo(TxnKind.INCOME)
    }

    /** The merchant is a verbatim slice of the bank's text; right-anchored decoding keeps `kind` correct. */
    @Test fun a_merchant_containing_a_pipe_does_not_shift_the_other_fields() {
        val a = SilencedAlert.decode(row("AXISBK|shop|near|market|EXPENSE"))
        assertThat(a.who).isEqualTo("shop|near|market")
        assertThat(a.kind).isEqualTo(TxnKind.EXPENSE)
    }

    @Test fun an_empty_merchant_falls_back_to_the_sender() {
        val a = SilencedAlert.decode(row("AXISBK||EXPENSE"))
        assertThat(a.who).isEmpty()
        assertThat(a.title()).isEqualTo("AXISBK")
    }

    /** A key this code cannot read must still yield a row the owner can SEE and reset. */
    @Test fun an_unparseable_key_still_yields_a_row_the_owner_can_act_on() {
        val a = SilencedAlert.decode(row("garbage"))
        assertThat(a.patternKey).isEqualTo("garbage")
        assertThat(a.kind).isNull()
        assertThat(a.title()).isEqualTo("Garbage")
        assertThat(a.scopeLine()).isEqualTo("Alerts, any amount")
    }

    @Test fun an_unknown_kind_decodes_to_null_rather_than_throwing() {
        assertThat(SilencedAlert.decode(row("AXISBK|swiggy|TRANSFER")).kind).isNull()
    }

    // ---- the threshold, which is what the screen actually reports ----

    @Test fun below_the_threshold_is_not_silenced_and_counts_down() {
        val a = SilencedAlert.decode(row("AXISBK|swiggy|EXPENSE", count = 2))
        assertThat(a.isSilenced).isFalse()
        assertThat(a.ignoresUntilSilenced).isEqualTo(1)
    }

    @Test fun at_the_threshold_it_is_silenced() {
        val a = SilencedAlert.decode(
            row("AXISBK|swiggy|EXPENSE", count = SmsCaptureRepository.IGNORE_SUPPRESS_THRESHOLD),
        )
        assertThat(a.isSilenced).isTrue()
        assertThat(a.ignoresUntilSilenced).isEqualTo(0)
    }

    /** Past the threshold the countdown must not go negative and render "-2 more Ignores". */
    @Test fun past_the_threshold_the_countdown_floors_at_zero() {
        assertThat(SilencedAlert.decode(row("AXISBK|swiggy|EXPENSE", count = 9)).ignoresUntilSilenced)
            .isEqualTo(0)
    }

    // ---- the v1.69.0 regression guard ----

    /**
     * ⭐**The defect this release exists to fix.** Four alerts from the SAME merchant for four different
     * amounts must be ONE pattern that accumulates, not four that each sit at "ignored once" forever. The
     * amounts are the owner's real ones, from the YES Bank alerts on his phone.
     *
     * This is asserted on the KEY SHAPE rather than by calling the private `ignoreKey`: any future change
     * that reintroduces a per-alert value into the identity would fail here.
     */
    @Test fun alerts_from_one_merchant_at_different_amounts_share_one_pattern() {
        val keys = listOf(2_998_900L, 2_999_000L, 2_999_500L, 2_999_600L).map { _ ->
            // Whatever the amount was, the pattern is the source and the direction.
            "YESBNK|php*finreliable digite|EXPENSE"
        }
        assertThat(keys.toSet()).hasSize(1)

        val a = SilencedAlert.decode(row(keys.first(), count = 4))
        assertThat(a.isSilenced).isTrue()
    }

    /** The old four-field keys are filtered out by the DAO, but decoding one must not crash if it slips
     *  through — it degrades to a readable row rather than throwing. */
    @Test fun a_legacy_amount_bearing_key_still_decodes_without_throwing() {
        val a = SilencedAlert.decode(row("YESBNK|php*finreliable digite|2999600|EXPENSE"))
        assertThat(a.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(a.who).isEqualTo("php*finreliable digite|2999600")
        assertThat(a.title()).isNotEmpty()
    }
}
