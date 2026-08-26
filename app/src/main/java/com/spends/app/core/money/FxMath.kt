package com.spends.app.core.money

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Exact integer foreign-exchange conversion.
 *
 * A rate is stored as **micros** — `rate × 1_000_000`, in base-currency units per ONE foreign unit
 * (1 MYR = ₹18.90 → `18_900_000`). Six decimal places is far finer than any rate a bank quotes, and
 * keeping it integral means a rate never picks up a binary-floating-point artefact on its way into
 * the database. `Double` appears at exactly one place — [rateMicrosFromDouble], the boundary where a
 * rate arrives from parsed JSON — and never past it, mirroring how [Money.parseToMinor] confines
 * `BigDecimal` to the parse boundary.
 *
 * Every function here is pure and side-effect free so the whole conversion path is unit-testable
 * without a database, a network or a clock.
 */
object FxMath {

    const val MICROS = 1_000_000L

    /** The widest rate we will accept from any source. Beyond this something has been misunderstood. */
    private const val MAX_RATE_MICROS = 100_000_000_000L // rate 100,000 — covers IDR/VND per unit
    private const val MIN_RATE_MICROS = 1L // rate 0.000001

    /**
     * Convert [foreignMinor] minor units of a foreign currency into base-currency minor units at
     * [rateMicros]. Rounds HALF_UP to the nearest minor unit — the same rounding the amount parser uses.
     *
     * Computed through [BigDecimal] rather than `Long` arithmetic on purpose: a large amount times a
     * large rate (a few crore rupiah, say) overflows a `Long` multiply *silently*, producing a
     * plausible-looking wrong number rather than an error.
     */
    fun convertMinor(foreignMinor: Long, rateMicros: Long): Long =
        BigDecimal.valueOf(foreignMinor)
            .multiply(BigDecimal.valueOf(rateMicros))
            .divide(BigDecimal.valueOf(MICROS), 0, RoundingMode.HALF_UP)
            .longValueExact()

    /** True when [rateMicros] is inside the range any real quote falls in. Anything else is rejected. */
    fun isSaneRate(rateMicros: Long?): Boolean =
        rateMicros != null && rateMicros in MIN_RATE_MICROS..MAX_RATE_MICROS

    /**
     * Turn a rate that arrived as a decimal (parsed JSON) into micros, or null when it is not a usable
     * quote — zero, negative, NaN, infinite, or outside [isSaneRate]'s range. Callers treat null as
     * "no conversion available" and fall back to leaving the amount alone.
     */
    fun rateMicrosFromDouble(rate: Double?): Long? {
        if (rate == null || rate.isNaN() || rate.isInfinite() || rate <= 0.0) return null
        val micros = runCatching {
            BigDecimal(rate, MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(MICROS))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }.getOrNull()
        return micros?.takeIf { isSaneRate(it) }
    }

    /**
     * Render a rate for the user: "18.9" not "18.900000". Trailing zeros are dropped but at least two
     * decimals are kept, so a rate always *reads* like a rate.
     */
    fun formatRate(rateMicros: Long): String {
        val bd = BigDecimal.valueOf(rateMicros).divide(BigDecimal.valueOf(MICROS))
        val stripped = bd.stripTrailingZeros()
        val scale = stripped.scale().coerceAtLeast(2)
        return stripped.setScale(scale, RoundingMode.HALF_UP).toPlainString()
    }

    /**
     * The one-line explanation shown wherever a converted amount appears — the "information of what it
     * is converting" that makes an automatic conversion auditable instead of magic:
     *
     *     RM 100.00 → ₹1,890.00  ·  1 MYR = ₹18.90
     */
    fun describe(
        foreignMinor: Long,
        foreignCode: String,
        baseMinor: Long,
        rateMicros: Long,
        baseCurrency: AppCurrency = Money.displayCurrency,
    ): String {
        val from = Money.formatCode(foreignMinor, foreignCode)
        val to = Money.format(baseMinor, baseCurrency)
        val rate = formatRate(rateMicros)
        return "$from → $to  ·  1 $foreignCode = ${baseCurrency.symbol}$rate"
    }
}
