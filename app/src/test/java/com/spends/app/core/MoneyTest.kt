package com.spends.app.core

import com.google.common.truth.Truth.assertThat
import com.spends.app.core.money.Money
import org.junit.Test

class MoneyTest {

    @Test fun groups_indian_separators() {
        assertThat(Money.groupIndian(0)).isEqualTo("0")
        assertThat(Money.groupIndian(999)).isEqualTo("999")
        assertThat(Money.groupIndian(1000)).isEqualTo("1,000")
        assertThat(Money.groupIndian(100000)).isEqualTo("1,00,000")
        assertThat(Money.groupIndian(1234567)).isEqualTo("12,34,567")
        assertThat(Money.groupIndian(120000000)).isEqualTo("12,00,00,000")
    }

    @Test fun formats_with_symbol_and_decimals() {
        assertThat(Money.formatRupees(500000)).isEqualTo("₹5,000.00")
        assertThat(Money.formatRupees(125050)).isEqualTo("₹1,250.50")
        assertThat(Money.formatRupees(0)).isEqualTo("₹0.00")
        assertThat(Money.formatRupees(-120000)).isEqualTo("-₹1,200.00")
        assertThat(Money.formatRupees(500000, withSymbol = false)).isEqualTo("5,000.00")
    }

    @Test fun parses_indian_formats_to_paise() {
        assertThat(Money.parseRupeesToMinor("Rs.5,000.00")).isEqualTo(500000)
        assertThat(Money.parseRupeesToMinor("Rs. 5000")).isEqualTo(500000)
        assertThat(Money.parseRupeesToMinor("Rs5000")).isEqualTo(500000)
        assertThat(Money.parseRupeesToMinor("INR 1,250.50")).isEqualTo(125050)
        assertThat(Money.parseRupeesToMinor("INR1250")).isEqualTo(125000)
        assertThat(Money.parseRupeesToMinor("₹240")).isEqualTo(24000)
        assertThat(Money.parseRupeesToMinor("18,500.00/-")).isEqualTo(1850000)
        assertThat(Money.parseRupeesToMinor("2499")).isEqualTo(249900)
    }

    @Test fun parse_rounds_to_paise_half_up() {
        assertThat(Money.parseRupeesToMinor("100.005")).isEqualTo(10001)
        assertThat(Money.parseRupeesToMinor("100.004")).isEqualTo(10000)
    }

    @Test fun parse_rejects_garbage() {
        assertThat(Money.parseRupeesToMinor("")).isNull()
        assertThat(Money.parseRupeesToMinor("abc")).isNull()
        assertThat(Money.parseRupeesToMinor("Rs.")).isNull()
    }

    @Test fun edit_string_round_trips() {
        assertThat(Money.toEditString(500000)).isEqualTo("5000.00")
        assertThat(Money.parseRupeesToMinor(Money.toEditString(123456))).isEqualTo(123456)
    }
}
