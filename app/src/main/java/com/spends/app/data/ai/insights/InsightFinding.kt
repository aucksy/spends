package com.spends.app.data.ai.insights

import com.spends.app.core.money.Money
import kotlin.math.abs

/** What a finding is about. Drives both the fallback wording and how the card is ranked. */
enum class InsightKind {
    /** The plain-English cycle summary — the card that shipped in v1.56.0, now page 1 of the carousel.
     *  Produced by `AiInsights`, never by [InsightEngine]. */
    CYCLE_SUMMARY,

    /** A category running well above its own recent norm — the headline "something unusual" card. */
    UNUSUAL_CATEGORY,

    /** The mirror image: a category the user has genuinely eased off on. Always framed positively. */
    QUIET_WIN,

    /** One charge far larger than that category's typical charge. */
    OUTLIER_CHARGE,

    /** Two or more identical charges on the same day — possibly billed twice. */
    DUPLICATE_CHARGE,

    /** Biggest rise vs the previous cycle. */
    MOVER_UP,

    /** Biggest fall vs the previous cycle. */
    MOVER_DOWN,

    /** How much of the cycle's spending sits in a handful of categories. */
    CONCENTRATION,

    /** Where this cycle stands at this point in it, against where earlier cycles stood at the same point. */
    PACE,

    /** The same stretch of the cycle, a year ago. Silent until there is genuinely a year of history. */
    YEAR_ON_YEAR,

    /** A category drifting steadily up or down across six complete cycles. */
    CATEGORY_TREND,

    /** A disproportionate share of spending landing in the days just after payday. */
    HABIT_PAYDAY,
}

/**
 * One thing worth saying about a cycle, **computed on the device** by [InsightEngine].
 *
 * Every number here is already correct before the AI ever sees it. The model is asked to phrase these, not
 * to work them out — which is what makes "3× your usual" trustworthy rather than a plausible-sounding
 * invention. See [InsightNarrator].
 *
 * Deliberately one flat type with a [kind] discriminator rather than a sealed hierarchy: the fields map
 * straight onto the JSON payload and onto assertions in tests, and the set of numbers a card can carry is
 * small and shared.
 */
data class InsightFinding(
    val kind: InsightKind,
    /** Category name, where the finding is about one. Never a merchant — merchants do not leave the device. */
    val category: String? = null,
    /** The figure the card is about (this cycle's total, or the size of the charge). */
    val amountMinor: Long = 0,
    /** What that figure is being compared against (the usual total, or the typical charge). */
    val baselineMinor: Long = 0,
    /** [amountMinor] ÷ [baselineMinor], where a ratio is the point of the card. */
    val multiple: Double = 0.0,
    /** Share of cycle spend, 0..100 — used by [InsightKind.CONCENTRATION]. */
    val sharePercent: Int = 0,
    /** How many items the finding covers (duplicate charges, categories in a concentration). */
    val count: Int = 0,
    /**
     * Rupees of real-money impact, used to rank cards so the biggest story leads. Not shown to the user and
     * not sent to the model.
     */
    val materialityMinor: Long = 0,
    /** Days into the cycle, counting the start day as day 1 — [InsightKind.PACE]. */
    val days: Int = 0,
    /** How many complete cycles a trend was measured across — [InsightKind.CATEGORY_TREND]. */
    val spanCycles: Int = 0,
    /** Share of the cycle's *days* the finding covers, 0..100 — the denominator for [InsightKind.HABIT_PAYDAY]. */
    val dayShare: Int = 0,
    /** The calendar month both sides of a [InsightKind.YEAR_ON_YEAR] comparison fall in, e.g. "July". */
    val periodLabel: String? = null,
) {

    /**
     * The wording used when the AI is unavailable — no key, offline, a failed call, or a short reply.
     *
     * This exists because the *finding* is the insight; the model only makes it read nicely. Falling back to
     * a plain sentence is strictly better than an empty carousel, and it means a flaky network degrades the
     * prose rather than the feature.
     */
    fun fallbackTitle(): String = when (kind) {
        InsightKind.CYCLE_SUMMARY -> "This cycle"
        InsightKind.UNUSUAL_CATEGORY -> "Unusual: ${category.orEmpty()}"
        InsightKind.QUIET_WIN -> "Spending less on ${category.orEmpty()}"
        InsightKind.OUTLIER_CHARGE -> "A large ${category.orEmpty()} charge"
        InsightKind.DUPLICATE_CHARGE -> "Charged twice?"
        InsightKind.MOVER_UP -> "${category.orEmpty()} is up"
        InsightKind.MOVER_DOWN -> "${category.orEmpty()} is down"
        InsightKind.CONCENTRATION -> "Where your money went"
        InsightKind.PACE -> if (amountMinor >= baselineMinor) "Running ahead this cycle" else "Running light this cycle"
        // The month is always set by the detector; the null branch keeps a missing one from rendering as
        // "This  vs last " rather than trusting that it cannot happen.
        InsightKind.YEAR_ON_YEAR -> periodLabel?.let { "This $it vs last $it" } ?: "This month vs last year"
        InsightKind.CATEGORY_TREND ->
            if (amountMinor >= baselineMinor) "${category.orEmpty()} is creeping up" else "${category.orEmpty()} is easing off"
        InsightKind.HABIT_PAYDAY -> "The week after payday"
    }

    fun fallbackBody(): String {
        fun rs(v: Long) = Money.formatRupees(v, alwaysTwoDecimals = false)
        return when (kind) {
            // Never rendered: the summary card's text comes from AiInsights, and the card is dropped when
            // that call fails rather than shown with placeholder prose.
            InsightKind.CYCLE_SUMMARY -> ""
            // "by this point" is load-bearing, not padding: mid-cycle the baseline is measured to the same
            // point in previous cycles, so claiming it as a whole-cycle figure would be false.
            InsightKind.UNUSUAL_CATEGORY ->
                "${category.orEmpty()} is ${rs(amountMinor)} so far this cycle, against ${rs(baselineMinor)} by this point in a usual one — about ${times()} as much."
            // abs, so an inverted finding could never render "— -₹3,000 less." Be clear about the trade-off:
            // this is NOT free — it turns a visibly broken sentence into a plausible false one. The detectors
            // are what actually guarantee the sign (a quiet win only fires when the baseline is larger), and
            // `InsightEngineTest` pins that invariant so a future inversion fails CI rather than reading fine.
            InsightKind.QUIET_WIN ->
                "${category.orEmpty()} is ${rs(amountMinor)} so far this cycle, against ${rs(baselineMinor)} by this point in a usual one — ${rs(abs(baselineMinor - amountMinor))} less."
            InsightKind.OUTLIER_CHARGE ->
                "A single ${category.orEmpty()} charge of ${rs(amountMinor)} — about ${times()} your usual ${rs(baselineMinor)}."
            InsightKind.DUPLICATE_CHARGE ->
                "$count charges of ${rs(amountMinor)} on ${category.orEmpty()} landed on the same day. Worth a look in case one was billed twice."
            InsightKind.MOVER_UP ->
                "${category.orEmpty()} is ${rs(abs(amountMinor - baselineMinor))} ahead of where last cycle stood at this point."
            InsightKind.MOVER_DOWN ->
                "${category.orEmpty()} is ${rs(abs(baselineMinor - amountMinor))} behind where last cycle stood at this point."
            InsightKind.CONCENTRATION ->
                "$count categories account for $sharePercent% of everything you spent this cycle — ${rs(amountMinor)} of it."
            // "by this point" again: the baseline is measured to the same day of earlier cycles, so calling
            // it a whole-cycle figure would overstate it for the whole of every cycle.
            InsightKind.PACE -> if (amountMinor >= baselineMinor) {
                "Day $days of the cycle and ${rs(amountMinor)} spent. Your recent cycles were at ${rs(baselineMinor)} by this point — ${rs(amountMinor - baselineMinor)} ahead."
            } else {
                "Day $days of the cycle and ${rs(amountMinor)} spent. Your recent cycles were at ${rs(baselineMinor)} by this point — ${rs(baselineMinor - amountMinor)} behind."
            }
            InsightKind.YEAR_ON_YEAR -> {
                val now = periodLabel?.let { "this $it" } ?: "this month"
                val then = periodLabel?.let { "last $it" } ?: "the same month last year"
                val gap = if (amountMinor >= baselineMinor) {
                    "${rs(amountMinor - baselineMinor)} more"
                } else {
                    "${rs(baselineMinor - amountMinor)} less"
                }
                // days == 0 means the cycle has finished — a cycle browsed back to is still worth comparing,
                // but "so far" would describe something that stopped happening weeks ago.
                if (days > 0) {
                    "${rs(amountMinor)} so far $now, against ${rs(baselineMinor)} over the same stretch $then — $gap."
                } else {
                    "${rs(amountMinor)} $now, against ${rs(baselineMinor)} $then — $gap."
                }
            }
            // Two real figures, never a fitted line: a regression's endpoints are amounts nobody spent.
            InsightKind.CATEGORY_TREND -> {
                // "across N cycles", not "the last N": N counts the cycles that actually carried this
                // category, and one of the last six may have been a gap.
                val span = if (spanCycles > 0) " across $spanCycles cycles" else ""
                "${category.orEmpty()} ran about ${rs(baselineMinor)} a cycle earlier$span, and about ${rs(amountMinor)} a cycle lately."
            }
            InsightKind.HABIT_PAYDAY ->
                "About $sharePercent% of your spending lands in the seven days after payday, which is only $dayShare% of the cycle."
        }
    }

    /** "2.4×", or "3×" when it lands close enough to whole that a decimal reads like false precision. */
    private fun times(): String {
        val rounded = Math.round(multiple * 10) / 10.0
        return if (rounded % 1.0 == 0.0) "${rounded.toInt()}×" else "$rounded×"
    }
}
