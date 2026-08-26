package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import com.spends.app.domain.model.TxnKind
import org.junit.Test

/**
 * Currency detection in the SMS parser.
 *
 * Two obligations, and the second matters more than the first:
 *  1. a foreign alert is recognised AND labelled with its currency, so it can be converted downstream;
 *  2. an Indian rupee alert parses **exactly** as it did before multi-currency existed. The 56 golden
 *     fixtures are a release gate precisely because a parser change that moves one number is a money bug,
 *     so the rupee-first ordering in `extractMoney` is asserted here directly rather than assumed.
 */
class SmsParserCurrencyTest {

    private val now = 1_754_000_000_000L

    private fun parse(sender: String, body: String) = SmsParser.parse(sender, body, now)

    // ---- rupees stay exactly as they were ----

    @Test fun a_rupee_alert_carries_no_currency_code() {
        // null currencyCode is the signal for "already in the ledger's currency, nothing to convert".
        val p = parse("VM-SBICRD", "Rs.2,499.00 spent on SBI Card ending 1234 at AMAZON on 01-08-25.")
        assertThat(p.result).isEqualTo(SmsParser.Result.TRANSACTION)
        assertThat(p.amountMinor).isEqualTo(249900)
        assertThat(p.currencyCode).isNull()
    }

    @Test fun a_rupee_amount_wins_even_when_a_foreign_token_appears_first() {
        // The ordering guarantee. A promo footer naming dollars must not change which number, or which
        // currency, a genuine rupee purchase parses as.
        val p = parse(
            "VM-SBICRD",
            "Get USD 5 cashback! Rs.1,200.00 spent on SBI Card ending 1234 at STORE on 01-08-25.",
        )
        assertThat(p.amountMinor).isEqualTo(120000)
        assertThat(p.currencyCode).isNull()
    }

    @Test fun the_rupee_symbol_is_recognised_too() {
        val p = parse("VM-SBICRD", "₹750.00 spent on SBI Card ending 1234 at CAFE on 01-08-25.")
        assertThat(p.amountMinor).isEqualTo(75000)
        assertThat(p.currencyCode).isNull()
    }

    // ---- ringgit ----

    @Test fun a_ringgit_alert_is_captured_and_labelled_myr() {
        val p = parse("MAYBANK", "RM250.00 spent at TESCO KL on card ending 4321 on 01-08-25.")
        assertThat(p.result).isEqualTo(SmsParser.Result.TRANSACTION)
        assertThat(p.amountMinor).isEqualTo(25000)
        assertThat(p.currencyCode).isEqualTo("MYR")
        assertThat(p.kind).isEqualTo(TxnKind.EXPENSE)
    }

    @Test fun the_myr_code_spelling_works_as_well_as_rm() {
        val p = parse("CIMB", "MYR 1,250.50 debited from your account ending 4321 on 01-08-25.")
        assertThat(p.amountMinor).isEqualTo(125050)
        assertThat(p.currencyCode).isEqualTo("MYR")
    }

    @Test fun a_ringgit_credit_is_income() {
        val p = parse("MAYBANK", "RM3,000.00 credited to your account ending 4321 on 01-08-25.")
        assertThat(p.kind).isEqualTo(TxnKind.INCOME)
        assertThat(p.amountMinor).isEqualTo(300000)
        assertThat(p.currencyCode).isEqualTo("MYR")
    }

    // ---- dollars ----

    @Test fun a_dollar_alert_is_captured_and_labelled_usd() {
        val p = parse("HSBC", "USD 42.10 debited from your account ending 4321 on 01-08-25.")
        assertThat(p.amountMinor).isEqualTo(4210)
        assertThat(p.currencyCode).isEqualTo("USD")
    }

    @Test fun a_bare_dollar_sign_is_read_as_usd() {
        val p = parse("HSBC", "\$42.10 debited from your account ending 4321 on 01-08-25.")
        assertThat(p.amountMinor).isEqualTo(4210)
        assertThat(p.currencyCode).isEqualTo("USD")
    }

    @Test fun the_us_dollar_prefix_does_not_double_match() {
        // "US$42" must read 42 dollars, not match "$" separately at a shifted offset.
        val p = parse("HSBC", "US\$42.10 debited from your account ending 4321 on 01-08-25.")
        assertThat(p.amountMinor).isEqualTo(4210)
        assertThat(p.currencyCode).isEqualTo("USD")
    }

    // ---- currencies we don't keep books in ----

    @Test fun a_third_currency_is_recognised_rather_than_misread() {
        // The point is NOT that Spends keeps books in Singapore dollars — it is that "SGD 88.00" must not
        // be silently logged as 88 of whatever the ledger uses. Labelling it lets conversion handle it.
        val p = parse("UOB", "SGD 88.00 debited from your account ending 4321 on 01-08-25.")
        assertThat(p.amountMinor).isEqualTo(8800)
        assertThat(p.currencyCode).isEqualTo("SGD")
    }

    // ---- things that must NOT read as a currency ----

    @Test fun a_letter_run_ending_in_a_currency_token_is_not_a_currency() {
        // "CASHBACKRM100" and "USDT" are the shapes that would produce a bogus capture.
        assertThat(parse("MAYBANK", "You earned CASHBACKRM100 points on 01-08-25.").currencyCode).isNull()
        assertThat(parse("HSBC", "USDT 42.10 debited from your account on 01-08-25.").currencyCode).isNull()
    }

    @Test fun an_unknown_sender_is_still_ignored_whatever_currency_it_names() {
        // Multi-currency widens which AMOUNTS are readable, never which SENDERS are trusted.
        assertThat(parse("Mom", "RM250.00 spent at TESCO on 01-08-25.").result)
            .isEqualTo(SmsParser.Result.IGNORED)
        assertThat(parse("+60123456789", "RM250.00 spent at TESCO on 01-08-25.").result)
            .isEqualTo(SmsParser.Result.IGNORED)
    }

    @Test fun an_otp_is_still_ignored_in_any_currency() {
        assertThat(parse("MAYBANK", "123456 is your OTP. Do not share it with anyone.").result)
            .isEqualTo(SmsParser.Result.IGNORED)
    }

    // ---- the shapes Malaysian bank alerts actually arrive in ----

    /**
     * Real-world phrasings, not invented ones. Malaysian alerts write the amount as `RM250.00`,
     * `RM 250.00` or `RM1,250.00`, and the digit runs straight up against the "RM" — which is exactly the
     * case a `\b` word boundary silently fails to match, because there is no boundary between a letter and
     * a digit. Each of these was a real miss before that guard became a negative look-ahead.
     */
    @Test fun the_common_ringgit_phrasings_all_capture() {
        val cases = listOf(
            "MAYBANK" to "RM250.00 has been debited from your account 1234 on 26/08/26",
            "MAYBANK" to "You have spent RM 89.90 at SHELL KL on 26 Aug",
            "CIMB" to "RM1,250.00 has been debited from your CIMB account ending 4321",
            "PBEBANK" to "Purchase of RM75.50 at AEON BIG on card ending 9876",
            "TNGDIGITAL" to "RM10.00 paid to GRAB via Touch n Go eWallet",
            "HLBANK" to "Your account ending 4321 has been debited RM 2,000.00",
        )
        cases.forEach { (sender, body) ->
            val p = parse(sender, body)
            assertThat(p.result).isEqualTo(SmsParser.Result.TRANSACTION)
            assertThat(p.currencyCode).isEqualTo("MYR")
            assertThat(p.amountMinor).isNotNull()
            assertThat(p.amountMinor!!).isGreaterThan(0L)
        }
    }

    @Test fun ringgit_amounts_are_read_to_the_sen() {
        // Thousands separators and sen must both survive — a ringgit figure read as 1.00 instead of
        // 1,250.00 is the kind of error that only shows up as a quietly wrong balance.
        assertThat(parse("CIMB", "RM1,250.00 has been debited from your account 4321").amountMinor)
            .isEqualTo(125000)
        assertThat(parse("MAYBANK", "You have spent RM 89.90 at SHELL on 26 Aug").amountMinor)
            .isEqualTo(8990)
        assertThat(parse("PBEBANK", "Purchase of RM75.50 at AEON on card 9876").amountMinor)
            .isEqualTo(7550)
    }

    @Test fun a_malaysian_otp_or_promo_is_still_ignored() {
        // Widening which AMOUNTS are readable must not widen which MESSAGES are captured.
        assertThat(parse("MAYBANK", "123456 is your TAC. Do not share it with anyone.").result)
            .isEqualTo(SmsParser.Result.IGNORED)
        assertThat(parse("CIMB", "Get a pre-approved personal loan up to RM50,000 today!").result)
            .isEqualTo(SmsParser.Result.IGNORED)
        assertThat(parse("MAYBANK", "Your transaction of RM250.00 was declined.").result)
            .isEqualTo(SmsParser.Result.IGNORED)
    }

    @Test fun a_malaysian_bank_is_a_known_sender_and_an_unlisted_one_is_not() {
        assertThat(SenderAllowlist.lookup("MAYBANK")?.name).isEqualTo("Maybank")
        assertThat(SenderAllowlist.lookup("CIMB")?.name).isEqualTo("CIMB Bank")
        assertThat(SenderAllowlist.lookup("TNGDIGITAL")?.name).isEqualTo("Touch 'n Go eWallet")
        assertThat(SenderAllowlist.lookup("RANDOMSHOP")).isNull()
    }
}
