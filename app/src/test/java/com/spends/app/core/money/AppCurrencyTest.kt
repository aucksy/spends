package com.spends.app.core.money

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/**
 * Multi-currency formatting and parsing.
 *
 * [Money.displayCurrency] is process-wide mutable state, so every test here restores the default in
 * [restoreDefault] — without it a failure mid-test would leak a ringgit ledger into `MoneyTest`, which
 * asserts rupee output, and the resulting failure would point at the wrong file entirely.
 */
class AppCurrencyTest {

    @After fun restoreDefault() {
        Money.displayCurrency = AppCurrency.DEFAULT
    }

    // ---- grouping ----

    @Test fun rupees_group_the_indian_way_and_everything_else_groups_by_thousands() {
        assertThat(Money.group(1234567, Grouping.INDIAN)).isEqualTo("12,34,567")
        assertThat(Money.group(1234567, Grouping.WESTERN)).isEqualTo("1,234,567")
        assertThat(Money.group(100000, Grouping.INDIAN)).isEqualTo("1,00,000")
        assertThat(Money.group(100000, Grouping.WESTERN)).isEqualTo("100,000")
    }

    @Test fun western_grouping_handles_the_boundaries() {
        assertThat(Money.groupWestern(0)).isEqualTo("0")
        assertThat(Money.groupWestern(999)).isEqualTo("999")
        assertThat(Money.groupWestern(1000)).isEqualTo("1,000")
        assertThat(Money.groupWestern(999999)).isEqualTo("999,999")
        assertThat(Money.groupWestern(1000000)).isEqualTo("1,000,000")
    }

    // ---- formatting ----

    @Test fun each_currency_formats_with_its_own_symbol_and_convention() {
        assertThat(Money.format(123456789, AppCurrency.INR)).isEqualTo("₹12,34,567.89")
        assertThat(Money.format(123456789, AppCurrency.MYR)).isEqualTo("RM1,234,567.89")
        assertThat(Money.format(123456789, AppCurrency.USD)).isEqualTo("$1,234,567.89")
    }

    @Test fun the_display_currency_drives_an_unqualified_format() {
        Money.displayCurrency = AppCurrency.MYR
        assertThat(Money.format(250000)).isEqualTo("RM2,500.00")
        Money.displayCurrency = AppCurrency.USD
        assertThat(Money.format(250000)).isEqualTo("$2,500.00")
    }

    @Test fun negatives_keep_the_sign_outside_the_symbol() {
        assertThat(Money.format(-120000, AppCurrency.MYR)).isEqualTo("-RM1,200.00")
        assertThat(Money.format(-120000, AppCurrency.USD)).isEqualTo("-$1,200.00")
    }

    @Test fun a_foreign_code_we_do_not_keep_books_in_still_formats_readably() {
        // The original side of a converted transaction: recognisable, never mislabelled as the base currency.
        assertThat(Money.formatCode(420000, "SGD")).isEqualTo("S$4,200.00")
        assertThat(Money.formatCode(420000, "GBP")).isEqualTo("£4,200.00")
        // An unknown code falls back to the bare code rather than inventing a symbol.
        assertThat(Money.formatCode(420000, "XYZ")).isEqualTo("XYZ 4,200.00")
    }

    @Test fun a_base_currency_code_formats_through_its_own_rules() {
        // formatCode must agree with format() for a currency we DO keep books in — Indian grouping included.
        assertThat(Money.formatCode(123456789, "INR")).isEqualTo(Money.format(123456789, AppCurrency.INR))
        assertThat(Money.formatCode(123456789, "MYR")).isEqualTo(Money.format(123456789, AppCurrency.MYR))
    }

    // ---- parsing ----

    @Test fun every_supported_currency_prefix_is_stripped_at_the_parse_boundary() {
        assertThat(Money.parseToMinor("RM250.00")).isEqualTo(25000)
        assertThat(Money.parseToMinor("MYR 1,250.50")).isEqualTo(125050)
        assertThat(Money.parseToMinor("$42.10")).isEqualTo(4210)
        assertThat(Money.parseToMinor("USD 42")).isEqualTo(4200)
        assertThat(Money.parseToMinor("US$42")).isEqualTo(4200)
        // ...and the rupee forms the app has always accepted still parse identically.
        assertThat(Money.parseToMinor("Rs.5,000.00")).isEqualTo(500000)
        assertThat(Money.parseToMinor("₹240")).isEqualTo(24000)
    }

    @Test fun parsing_is_unchanged_by_which_currency_is_on_display() {
        // Parsing must not depend on process-wide state: the same text yields the same minor units
        // whatever the books happen to be kept in.
        Money.displayCurrency = AppCurrency.USD
        assertThat(Money.parseToMinor("RM250.00")).isEqualTo(25000)
        Money.displayCurrency = AppCurrency.INR
        assertThat(Money.parseToMinor("RM250.00")).isEqualTo(25000)
    }

    @Test fun nonsense_still_parses_to_null_rather_than_a_number() {
        assertThat(Money.parseToMinor("")).isNull()
        assertThat(Money.parseToMinor("RM")).isNull()
        assertThat(Money.parseToMinor("abc")).isNull()
    }

    // ---- code resolution ----

    @Test fun codes_resolve_case_insensitively_and_unknown_ones_fall_back() {
        assertThat(AppCurrency.fromCode("myr")).isEqualTo(AppCurrency.MYR)
        assertThat(AppCurrency.fromCode(" USD ")).isEqualTo(AppCurrency.USD)
        assertThat(AppCurrency.fromCode("SGD")).isNull()
        assertThat(AppCurrency.fromCode(null)).isNull()
        // A stale or foreign persisted value must degrade to the default, never throw.
        assertThat(AppCurrency.fromCodeOrDefault("SGD")).isEqualTo(AppCurrency.DEFAULT)
        assertThat(AppCurrency.fromCodeOrDefault(null)).isEqualTo(AppCurrency.DEFAULT)
    }

    // ---- the null code: the ordinary domestic case ----

    @Test fun a_null_code_formats_in_the_ledgers_own_currency() {
        // v1.70.0's CaptureNotifier passed the capture's currencyCode straight to formatCode, and that code
        // is NULL for every ordinary rupee SMS. The generic branch treated an absent code as an unknown
        // currency whose symbol is "" and grouped it Western-style, so the app's highest-traffic surface
        // announced "Expense 1,25,000.00" instead of "Expense ₹12,34,567.89". No test covered it, so CI
        // stayed green. A null code now means "no foreign currency involved", not "a currency I don't know".
        assertThat(Money.formatCode(123_456_789, null)).isEqualTo(Money.format(123_456_789))
        assertThat(Money.formatCode(123_456_789, null)).isEqualTo("₹12,34,567.89")
        assertThat(Money.formatCode(45_000, null)).isEqualTo("₹450.00")
        assertThat(Money.formatCode(-45_000, null)).isEqualTo("-₹450.00")
    }

    @Test fun a_blank_code_is_treated_the_same_as_a_null_one() {
        // Same trap, one whitespace away: fromCode() already discards a blank, so the generic branch would
        // have caught these too.
        assertThat(Money.formatCode(45_000, "")).isEqualTo(Money.format(45_000))
        assertThat(Money.formatCode(45_000, "   ")).isEqualTo(Money.format(45_000))
    }

    @Test fun a_null_code_follows_the_ledger_currency_rather_than_assuming_rupees() {
        // The fallback must be the ledger's currency, not a hard-coded ₹ — otherwise a Malaysian user's
        // ordinary domestic alerts would be announced in rupees, which is the same bug pointing the other way.
        Money.displayCurrency = AppCurrency.MYR
        assertThat(Money.formatCode(123_456_789, null)).isEqualTo("RM1,234,567.89")
        assertThat(Money.formatCode(123_456_789, null)).isEqualTo(Money.format(123_456_789))
    }

    @Test fun a_known_foreign_code_is_still_labelled_and_grouped_as_that_currency() {
        // The guard on the fix: the null fallback must not have swallowed the real job of formatCode.
        assertThat(Money.formatCode(123_456_789, "MYR")).isEqualTo("RM1,234,567.89")
        assertThat(Money.formatCode(420_000, "SGD")).isEqualTo("S$4,200.00")
    }

    @Test fun currency_tokens_are_ordered_longest_first() {
        // The SMS parser relies on this ordering so "MYR" is never matched as a bare "RM"-something and
        // "US$" always beats "$". Assert the invariant rather than the list, which will grow.
        val lengths = AppCurrency.ALL_TOKENS.map { it.first.length }
        assertThat(lengths).isEqualTo(lengths.sortedDescending())
    }
}
