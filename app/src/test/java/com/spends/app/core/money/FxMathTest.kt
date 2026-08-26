package com.spends.app.core.money

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/**
 * The integer conversion arithmetic.
 *
 * This is the one place in the app where a number supplied by a language model is allowed to scale a
 * real amount of money, so the tests lean hard on the boundaries: rounding, overflow, and the band of
 * rates that are accepted at all.
 */
class FxMathTest {

    @After fun restoreDefault() {
        Money.displayCurrency = AppCurrency.DEFAULT
    }

    private val rate18_90 = 18_900_000L // 1 MYR = 18.90 INR

    // ---- conversion ----

    @Test fun converts_a_foreign_amount_into_base_minor_units() {
        // RM 100.00 = 10,000 sen -> 189,000 paise = ₹1,890.00
        assertThat(FxMath.convertMinor(10_000, rate18_90)).isEqualTo(189_000)
    }

    @Test fun a_zero_amount_converts_to_zero_at_any_rate() {
        assertThat(FxMath.convertMinor(0, rate18_90)).isEqualTo(0)
        assertThat(FxMath.convertMinor(0, 1)).isEqualTo(0)
    }

    @Test fun a_negative_amount_keeps_its_sign() {
        assertThat(FxMath.convertMinor(-10_000, rate18_90)).isEqualTo(-189_000)
    }

    @Test fun rounds_half_up_to_the_nearest_minor_unit() {
        // 1 sen at 18.9 = 18.9 paise -> 19. Rounding DOWN here would quietly shave every conversion.
        assertThat(FxMath.convertMinor(1, rate18_90)).isEqualTo(19)
        // Exactly .5 goes up, matching Money.parseToMinor's HALF_UP at the other parse boundary.
        assertThat(FxMath.convertMinor(1, 1_500_000)).isEqualTo(2)
        assertThat(FxMath.convertMinor(1, 1_400_000)).isEqualTo(1)
    }

    @Test fun a_large_amount_times_a_large_rate_does_not_overflow() {
        // The exact case a Long multiply gets wrong: this product exceeds Long.MAX_VALUE before the
        // divide, so a naive `foreignMinor * rateMicros / MICROS` would silently return a wrong number
        // rather than fail. 10 billion minor units at rate 1000.
        val result = FxMath.convertMinor(10_000_000_000L, 1_000_000_000L)
        assertThat(result).isEqualTo(10_000_000_000_000L)
    }

    // ---- rate sanity ----

    @Test fun rates_outside_the_plausible_band_are_refused() {
        assertThat(FxMath.isSaneRate(null)).isFalse()
        assertThat(FxMath.isSaneRate(0)).isFalse()
        assertThat(FxMath.isSaneRate(-1)).isFalse()
        assertThat(FxMath.isSaneRate(100_000_000_001L)).isFalse() // just past the ceiling
        assertThat(FxMath.isSaneRate(1)).isTrue()
        assertThat(FxMath.isSaneRate(rate18_90)).isTrue()
        assertThat(FxMath.isSaneRate(100_000_000_000L)).isTrue() // exactly the ceiling
    }

    @Test fun a_decimal_rate_becomes_micros() {
        assertThat(FxMath.rateMicrosFromDouble(18.9)).isEqualTo(18_900_000)
        assertThat(FxMath.rateMicrosFromDouble(1.0)).isEqualTo(1_000_000)
        assertThat(FxMath.rateMicrosFromDouble(0.000001)).isEqualTo(1)
    }

    @Test fun an_unusable_rate_becomes_null_rather_than_a_number() {
        // Every one of these has to mean "no conversion available", not "convert by something odd".
        assertThat(FxMath.rateMicrosFromDouble(null)).isNull()
        assertThat(FxMath.rateMicrosFromDouble(0.0)).isNull()
        assertThat(FxMath.rateMicrosFromDouble(-18.9)).isNull()
        assertThat(FxMath.rateMicrosFromDouble(Double.NaN)).isNull()
        assertThat(FxMath.rateMicrosFromDouble(Double.POSITIVE_INFINITY)).isNull()
        assertThat(FxMath.rateMicrosFromDouble(Double.MAX_VALUE)).isNull()
        // Below one micro rounds to zero, which is not a rate.
        assertThat(FxMath.rateMicrosFromDouble(0.0000001)).isNull()
    }

    // ---- display ----

    @Test fun a_rate_reads_like_a_rate() {
        assertThat(FxMath.formatRate(18_900_000)).isEqualTo("18.90")
        assertThat(FxMath.formatRate(1_000_000)).isEqualTo("1.00")
        // More precision than two places is kept when it is actually there.
        assertThat(FxMath.formatRate(18_912_500)).isEqualTo("18.9125")
    }

    @Test fun the_receipt_names_both_sides_and_the_rate() {
        val text = FxMath.describe(
            foreignMinor = 10_000,
            foreignCode = "MYR",
            baseMinor = 189_000,
            rateMicros = rate18_90,
            baseCurrency = AppCurrency.INR,
        )
        assertThat(text).contains("RM100.00")
        assertThat(text).contains("₹1,890.00")
        assertThat(text).contains("1 MYR = ₹18.90")
    }

    @Test fun the_receipt_follows_the_base_currency_it_is_given() {
        val text = FxMath.describe(10_000, "USD", 47_000, 4_700_000, AppCurrency.MYR)
        assertThat(text).contains("$100.00")
        assertThat(text).contains("RM470.00")
        assertThat(text).contains("1 USD = RM4.70")
    }

    @Test fun converting_and_describing_agree_on_the_same_figure() {
        // The receipt must never state a figure the arithmetic did not produce.
        val baseMinor = FxMath.convertMinor(25_000, rate18_90)
        assertThat(FxMath.describe(25_000, "MYR", baseMinor, rate18_90, AppCurrency.INR))
            .contains(Money.format(baseMinor, AppCurrency.INR))
    }
}
