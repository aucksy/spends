package com.spends.app.data.ai.insights

import com.spends.app.core.money.Money

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
            InsightKind.QUIET_WIN ->
                "${category.orEmpty()} is ${rs(amountMinor)} so far this cycle, against ${rs(baselineMinor)} by this point in a usual one — ${rs(baselineMinor - amountMinor)} less."
            InsightKind.OUTLIER_CHARGE ->
                "A single ${category.orEmpty()} charge of ${rs(amountMinor)} — about ${times()} your usual ${rs(baselineMinor)}."
            InsightKind.DUPLICATE_CHARGE ->
                "$count charges of ${rs(amountMinor)} on ${category.orEmpty()} landed on the same day. Worth a look in case one was billed twice."
            InsightKind.MOVER_UP ->
                "${category.orEmpty()} is ${rs(amountMinor - baselineMinor)} ahead of where last cycle stood at this point."
            InsightKind.MOVER_DOWN ->
                "${category.orEmpty()} is ${rs(baselineMinor - amountMinor)} behind where last cycle stood at this point."
            InsightKind.CONCENTRATION ->
                "$count categories account for $sharePercent% of everything you spent this cycle — ${rs(amountMinor)} of it."
        }
    }

    /** "2.4×", or "3×" when it lands close enough to whole that a decimal reads like false precision. */
    private fun times(): String {
        val rounded = Math.round(multiple * 10) / 10.0
        return if (rounded % 1.0 == 0.0) "${rounded.toInt()}×" else "$rounded×"
    }
}
