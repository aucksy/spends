package com.spends.app.core.money

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * All money in Spends is stored and computed as integer **minor units** (paise / sen / cents) in a
 * [Long]. No Float/Double ever touches an amount. This object is the single place that converts between
 * minor units and human-facing strings, using exact decimal arithmetic at the parse boundary only.
 *
 * The ledger is kept in ONE currency — [displayCurrency], chosen by the user in Settings. That choice
 * is a **rendering** decision: it picks the symbol and the digit-grouping convention (Indian 12,34,567
 * for rupees, Western 1,234,567 for ringgit/dollars) and nothing else. No stored figure changes when it
 * changes, and no arithmetic in this file consults it.
 */
object Money {

    const val RUPEE = "₹"
    private const val MINOR_PER_UNIT = 100L

    /**
     * The currency every un-qualified format/parse call renders in.
     *
     * Deliberately a plain volatile field rather than an injected dependency: formatting happens in
     * RemoteViews widgets, notification builders and spreadsheet exporters that have no ViewModel and no
     * composition to read a CompositionLocal from, and threading a parameter through all of them would
     * leave exactly the kind of missed call site that renders "₹" next to a ringgit figure. It is set
     * once at process start and on every change by `MainViewModel`, and it is display-only — a stale
     * read can mislabel a symbol for one frame, it can never alter an amount.
     */
    @Volatile
    var displayCurrency: AppCurrency = AppCurrency.DEFAULT

    /**
     * Format minor units in [currency]. [withSymbol] prepends the currency symbol; [alwaysTwoDecimals]
     * always shows the fractional part (recommended for ledgers). Negatives render as "-₹1,200.00".
     */
    fun format(
        minor: Long,
        currency: AppCurrency = displayCurrency,
        withSymbol: Boolean = true,
        alwaysTwoDecimals: Boolean = true,
    ): String {
        val negative = minor < 0
        val absMinor = abs(minor)
        val units = absMinor / MINOR_PER_UNIT
        val fraction = (absMinor % MINOR_PER_UNIT).toInt()
        val sb = StringBuilder()
        if (negative) sb.append('-')
        if (withSymbol) sb.append(currency.symbol)
        sb.append(group(units, currency.grouping))
        if (alwaysTwoDecimals || fraction != 0) {
            sb.append('.').append(fraction.toString().padStart(2, '0'))
        }
        return sb.toString()
    }

    /**
     * Format minor units of an arbitrary currency CODE — including one Spends doesn't keep books in
     * (the original side of a converted transaction: "SGD 42.00"). Unknown codes group Western-style
     * and are labelled with the bare code.
     */
    fun formatCode(minor: Long, code: String?, withSymbol: Boolean = true): String {
        AppCurrency.fromCode(code)?.let { return format(minor, it, withSymbol) }
        val negative = minor < 0
        val absMinor = abs(minor)
        val symbol = AppCurrency.symbolFor(code)
        val sb = StringBuilder()
        if (negative) sb.append('-')
        if (withSymbol) {
            sb.append(symbol)
            // A real symbol sits flush against the digits ("S$4,200.00"), matching how every base currency
            // renders. A bare ISO code is a word, so it needs the space ("XYZ 4,200.00").
            if (symbol.equals(code?.trim(), ignoreCase = true)) sb.append(' ')
        }
        sb.append(group(absMinor / MINOR_PER_UNIT, Grouping.WESTERN))
        sb.append('.').append((absMinor % MINOR_PER_UNIT).toInt().toString().padStart(2, '0'))
        return sb.toString()
    }

    /** Group an integer value per [grouping]: Indian 1,00,000 / 12,34,567, Western 100,000 / 1,234,567. */
    fun group(value: Long, grouping: Grouping = displayCurrency.grouping): String =
        when (grouping) {
            Grouping.INDIAN -> groupIndian(value)
            Grouping.WESTERN -> groupWestern(value)
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

    /** Group an integer value with plain thousands separators: 1,000 / 1,234,567. */
    fun groupWestern(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val sb = StringBuilder()
        var count = 0
        for (i in digits.indices.reversed()) {
            sb.append(digits[i])
            count++
            if (count % 3 == 0 && i != 0) sb.append(',')
        }
        return sb.reverse().toString()
    }

    /**
     * Parse a user-entered or cleaned amount into minor units. Tolerates any known currency prefix
     * (₹/Rs/Rs./INR/RM/MYR/$/USD), grouping commas, surrounding whitespace, and a trailing "/-".
     * Rounds to minor units (HALF_UP). Returns null when there is no parseable number.
     */
    fun parseToMinor(input: String): Long? {
        val cleaned = input.trim()
            .replace(CURRENCY_PREFIXES, "")
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

    /** Convert minor units to a plain editable string (no symbol, no grouping): 500000 -> "5000.00". */
    fun toEditString(minor: Long): String {
        val negative = minor < 0
        val absMinor = abs(minor)
        val s = "${absMinor / MINOR_PER_UNIT}.${(absMinor % MINOR_PER_UNIT).toInt().toString().padStart(2, '0')}"
        return if (negative) "-$s" else s
    }

    // Every currency token the app understands, plus the legacy "/-" suffix. Alternation is ordered
    // longest-first so "MYR" is stripped whole instead of leaving a stray "R", and "US$" beats "$".
    private val CURRENCY_PREFIXES = Regex(
        "(?i)(" + listOf("INR", "MYR", "USD", "US\\$", "RS\\.?", "RM", "₹", "\\$", "/-").joinToString("|") + ")",
    )
}
