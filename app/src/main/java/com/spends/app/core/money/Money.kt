package com.spends.app.core.money

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * All money in Spends is stored and computed as integer **minor units (paise)** in a [Long].
 * No Float/Double ever touches an amount. This object is the single place that converts between
 * paise and human-facing rupee strings, using exact decimal arithmetic at the parse boundary only.
 *
 * Display uses the Indian digit grouping convention (e.g. 12,34,567.00), not Western thousands.
 */
object Money {

    const val RUPEE = "₹"
    private const val PAISE_PER_RUPEE = 100L

    /**
     * Format paise as a rupee string. [withSymbol] prepends ₹; [alwaysTwoDecimals] always shows
     * the paise part (recommended for ledgers). Negatives are rendered as "-₹1,200.00".
     */
    fun formatRupees(minor: Long, withSymbol: Boolean = true, alwaysTwoDecimals: Boolean = true): String {
        val negative = minor < 0
        val absMinor = abs(minor)
        val rupees = absMinor / PAISE_PER_RUPEE
        val paise = (absMinor % PAISE_PER_RUPEE).toInt()
        val sb = StringBuilder()
        if (negative) sb.append('-')
        if (withSymbol) sb.append(RUPEE)
        sb.append(groupIndian(rupees))
        if (alwaysTwoDecimals || paise != 0) {
            sb.append('.').append(paise.toString().padStart(2, '0'))
        }
        return sb.toString()
    }

    /** Group an integer rupee value with Indian separators: 1,00,000 / 12,34,567. */
    fun groupIndian(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val last3 = digits.substring(digits.length - 3)
        var rest = digits.substring(0, digits.length - 3)
        val sb = StringBuilder()
        while (rest.length > 2) {
            sb.insert(0, "," + rest.substring(rest.length - 2))
            rest = rest.substring(0, rest.length - 2)
        }
        sb.insert(0, rest)
        sb.append(',').append(last3)
        return sb.toString()
    }

    /**
     * Parse a user-entered or cleaned rupee amount into paise. Tolerates ₹/Rs/Rs./INR prefixes,
     * grouping commas, surrounding whitespace, and a trailing "/-". Rounds to paise (HALF_UP).
     * Returns null when there is no parseable number.
     */
    fun parseRupeesToMinor(input: String): Long? {
        val cleaned = input.trim()
            .replace(Regex("(?i)(inr|rs\\.?|₹|/-)"), "")
            .replace(",", "")
            .replace(" ", "")
            .trim()
        if (cleaned.isEmpty()) return null
        return try {
            BigDecimal(cleaned)
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
        } catch (e: NumberFormatException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    /** Convert paise to a plain editable rupee string (no symbol, no grouping): 500000 -> "5000.00". */
    fun toEditString(minor: Long): String {
        val negative = minor < 0
        val absMinor = abs(minor)
        val s = "${absMinor / PAISE_PER_RUPEE}.${(absMinor % PAISE_PER_RUPEE).toInt().toString().padStart(2, '0')}"
        return if (negative) "-$s" else s
    }
}
