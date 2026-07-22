package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import com.spends.app.data.capture.SmsParser.Result
import com.spends.app.domain.model.TxnKind
import org.junit.Test

/**
 * Phase 4: RCS business chats / Truecaller verified senders show FRIENDLY names, not DLT headers.
 * [SenderAllowlist.canonicalSenderFor] must translate those into a sender the parser accepts —
 * and must leave the SMS header form untouched so an SMS + RCS twin parse identically.
 */
class SenderAllowlistNameTest {

    // ---- friendly names resolve to a parser-accepted canonical header ----

    @Test fun axis_friendly_name_maps_to_canonical_header() {
        assertThat(SenderAllowlist.canonicalSenderFor("Axis Bank")).isEqualTo("AXISBK")
    }

    @Test fun idfc_friendly_name_maps() {
        assertThat(SenderAllowlist.canonicalSenderFor("IDFC FIRST Bank")).isEqualTo("IDFCFB")
    }

    @Test fun sbi_card_friendly_name_maps_to_credit_card_header() {
        assertThat(SenderAllowlist.canonicalSenderFor("SBI Card")).isEqualTo("SBICRD")
    }

    @Test fun hdfc_friendly_name_maps() {
        assertThat(SenderAllowlist.canonicalSenderFor("HDFC Bank")).isEqualTo("HDFCBK")
    }

    @Test fun amex_short_name_maps() {
        assertThat(SenderAllowlist.canonicalSenderFor("Amex")).isEqualTo("AMEXIN")
    }

    @Test fun name_matching_ignores_case_and_punctuation() {
        assertThat(SenderAllowlist.canonicalSenderFor("axis bank")).isEqualTo("AXISBK")
        assertThat(SenderAllowlist.canonicalSenderFor("American Express")).isEqualTo("AMEXIN")
    }

    @Test fun common_agent_name_suffixes_are_stripped() {
        assertThat(SenderAllowlist.canonicalSenderFor("Axis Bank Ltd")).isEqualTo("AXISBK")
        assertThat(SenderAllowlist.canonicalSenderFor("HDFC Bank Cards")).isEqualTo("HDFCBK")
        assertThat(SenderAllowlist.canonicalSenderFor("IDFC FIRST Bank Limited")).isEqualTo("IDFCFB")
        assertThat(SenderAllowlist.canonicalSenderFor("SBI Card India")).isEqualTo("SBICRD")
    }

    @Test fun suffix_stripping_never_invents_a_match() {
        assertThat(SenderAllowlist.canonicalSenderFor("Kotak Bank")).isNull()
        assertThat(SenderAllowlist.canonicalSenderFor("Some Shop Ltd")).isNull()
    }

    // ---- header-form senders pass through unchanged (SMS/RCS twins stay identical) ----

    @Test fun dlt_header_sender_passes_through_untouched() {
        assertThat(SenderAllowlist.canonicalSenderFor("AX-AXISBK")).isEqualTo("AX-AXISBK")
        assertThat(SenderAllowlist.canonicalSenderFor("JK-IDFCFB")).isEqualTo("JK-IDFCFB")
    }

    // ---- non-financial senders are rejected ----

    @Test fun personal_and_unknown_senders_return_null() {
        assertThat(SenderAllowlist.canonicalSenderFor("Mom")).isNull()
        assertThat(SenderAllowlist.canonicalSenderFor("Pizza Hut")).isNull()
        assertThat(SenderAllowlist.canonicalSenderFor("+919876543210")).isNull()
        assertThat(SenderAllowlist.canonicalSenderFor(null)).isNull()
        assertThat(SenderAllowlist.canonicalSenderFor("")).isNull()
    }

    // ---- end-to-end: a friendly-named RCS alert parses exactly like its SMS twin ----

    @Test fun rcs_alert_with_friendly_name_parses_like_sms() {
        val body = "Your A/C XX1234 is debited by INR 850.00 on 21/06/2026 14:32. New Bal :INR 12,400.50. Team IDFC FIRST Bank"
        val canonical = SenderAllowlist.canonicalSenderFor("IDFC FIRST Bank")
        val viaName = SmsParser.parse(canonical, body, NOW)
        val viaHeader = SmsParser.parse("JK-IDFCFB", body, NOW)
        assertThat(viaName.result).isEqualTo(Result.TRANSACTION)
        assertThat(viaName.amountMinor).isEqualTo(viaHeader.amountMinor)
        assertThat(viaName.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(viaName.last4).isEqualTo(viaHeader.last4)
        assertThat(viaName.institution).isEqualTo(viaHeader.institution)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
