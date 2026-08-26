package com.spends.app.ui.categorytxns

import com.spends.app.core.money.Money
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The one sentence the category screen leads with, plus the two bar widths beneath it.
 *
 * The screen used to show the cycle total and the monthly average as two headline numbers of identical
 * weight, covering different stretches of time, with nothing stating how they related — so answering
 * "is this normal for me?" meant doing the subtraction yourself. This turns that into words.
 *
 * Pure, so every sentence it can produce is directly testable.
 *
 * **The honesty rule:** a cycle that is still running has not finished spending. "You spent ₹4,000 less
 * than usual" is a claim about a month that is only half over, and it would read as praise for a cycle
 * that may still overshoot. Under-spending therefore says **"so far"**, and only over-spending is stated
 * flatly — once you are already above a usual month, no amount of remaining time makes that untrue.
 */
data class CycleComparison(
    val sentence: String,
    /** Bar width 0f..1f for the cycle total; the larger of the two figures is always 1f. */
    val cycleFraction: Float,
    /** Bar width 0f..1f for the usual month. */
    val usualFraction: Float,
) {
    companion object {

        /** Below this much difference, the two are "about the same" — a 2% wobble is not a finding. */
        private const val NEAR_ENOUGH_PERCENT = 8

        /**
         * Build the comparison, or null when there is nothing honest to say — which is the case whenever
         * there is no baseline to compare against (a category with no history divides by nothing).
         *
         * [reference] names what is being compared against, and is the only part that differs between the
         * two modes: "your usual month" in Monthly, "your monthly average in 2025" in Yearly. Parametrised
         * rather than duplicated so the two screens can never drift into saying it differently.
         *
         * [stillRunning] must be true when the selected period has not ended — the current cycle, or the
         * current calendar year.
         */
        fun of(
            totalMinor: Long,
            usualMonthMinor: Long,
            stillRunning: Boolean,
            reference: String = "your usual month",
            emptyText: String = "Nothing in this cycle yet.",
        ): CycleComparison? {
            if (usualMonthMinor <= 0L) return null
            if (totalMinor <= 0L) {
                return CycleComparison(emptyText, cycleFraction = 0f, usualFraction = 1f)
            }
            val diff = totalMinor - usualMonthMinor
            val percent = abs(diff) * 100.0 / usualMonthMinor
            // Deliberately no figure for the reference in the sentence. It is rounded for readability, while
            // the bar directly beneath shows it to the paise — printing both put two different numbers for
            // the same quantity a centimetre apart, the exact confusion this screen is fixing.
            val sentence = when {
                percent < NEAR_ENOUGH_PERCENT -> "About the same as $reference."
                diff > 0 -> "About ${money(diff)} more than $reference."
                stillRunning -> "About ${money(-diff)} under $reference so far."
                else -> "About ${money(-diff)} less than $reference."
            }
            val max = maxOf(totalMinor, usualMonthMinor).toFloat()
            return CycleComparison(
                sentence = sentence,
                cycleFraction = (totalMinor / max).coerceIn(0f, 1f),
                usualFraction = (usualMonthMinor / max).coerceIn(0f, 1f),
            )
        }

        /**
         * Money rounded to something a person would actually say out loud: to the nearest ₹100 once the
         * figure runs to four digits, to the nearest ₹10 below that. Paise on a comparison are noise —
         * "about ₹1,524.79 more" reads as a precision the word "about" has already disclaimed.
         */
        private fun money(minor: Long): String {
            val step = if (abs(minor) >= 100_000L) 10_000L else 1_000L
            val rounded = (minor.toDouble() / step).roundToLong() * step
            // Never round a real, non-zero amount away to "₹0" — for a tiny figure, show it as it is.
            val shown = if (rounded == 0L && minor != 0L) minor else rounded
            return Money.format(shown, alwaysTwoDecimals = false)
        }
    }
}
