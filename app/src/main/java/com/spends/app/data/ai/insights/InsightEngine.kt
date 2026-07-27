package com.spends.app.data.ai.insights

import kotlin.math.abs
import kotlin.math.roundToLong

/** One charge's slice of a category, as loaded from the ledger. Merchant is used only for duplicate
 *  detection, on the device — it is never carried into an [InsightFinding] and never leaves the phone. */
data class ChargeSlice(
    val expenseId: Long,
    val category: String,
    val amountMinor: Long,
    val dayEpoch: Long,
    val merchantKey: String?,
)

/**
 * Everything the detectors need, already loaded. Windows are equal-length and consecutive, most recent first.
 *
 * ⚠ [history] and [previous] must already be **day-aligned** to the same point in their cycle that the
 * current one has reached — see [InsightWindows]. The engine compares them at face value and does no
 * scaling of its own, precisely so no figure it reports back is one the app invented.
 */
data class InsightInput(
    val expenseMinor: Long,
    val current: Map<String, Long>,
    val previous: Map<String, Long>?,
    val history: List<Map<String, Long>>,
    val currentSlices: List<ChargeSlice>,
    val historySlices: List<ChargeSlice>,
)

/**
 * Turns a cycle's numbers into the handful of things actually worth saying about it.
 *
 * **Pure and deterministic** — no Room, no clock, no network — so every rule here is unit-testable against
 * fixtures. This is the half of the insights feature that must be *correct*; [InsightNarrator] only makes it
 * readable.
 *
 * ## Two rules that shape everything here
 *
 * **1. Never report a figure the app invented.** Every number a card shows is one the user actually spent.
 * Comparing a part-finished cycle against complete ones is handled by narrowing the *question* — the caller
 * supplies baselines measured to the same point in their own cycle ([InsightWindows]) — never by scaling an
 * old total into a number that never happened.
 *
 * **2. Say nothing rather than something trivial.** Every detector carries an absolute rupee floor as well as
 * a ratio. Without it, a category that went from ₹40 to ₹160 is "4× your usual" — technically true, worth
 * nobody's attention, and precisely the noise that made the old single card feel pointless. A card has to
 * earn its place.
 */
object InsightEngine {

    /** Below this, a change isn't worth a card no matter how dramatic the ratio looks. */
    private const val MIN_MATERIAL_MINOR = 1_500_00L
    private const val MIN_OUTLIER_MINOR = 2_000_00L
    private const val MIN_DUPLICATE_MINOR = 200_00L

    private const val UNUSUAL_MULTIPLE = 2.0
    private const val QUIET_WIN_MULTIPLE = 0.6
    private const val OUTLIER_MULTIPLE = 3.0
    /**
     * ⭐These two are load-bearing together. The top 3 of 4 categories are ≥75% of spend by arithmetic alone,
     * and the top 3 of 5 are ≥60% — so a 55% bar meant the card ALWAYS fired for anyone with a handful of
     * categories, which is padding, not insight. Requiring at least 6 categories puts the arithmetic floor at
     * 50%, so a 70% bar actually says something.
     */
    private const val CONCENTRATION_MIN_PERCENT = 70
    private const val CONCENTRATION_TOP_N = 3
    private const val CONCENTRATION_MIN_CATEGORIES = 6

    /**
     * A category needs history in at least this many prior windows before "usual" means anything.
     *
     * Four, not three, and it pairs with the `mapNotNull` in [unusualAndQuiet]: a category you buy in half
     * the months isn't a habit, and treating the silent months as ₹0 would halve its median and make an
     * entirely ordinary month read as "2× your usual".
     */
    private const val MIN_HISTORY_WINDOWS = 4

    /** How many cards the carousel shows alongside the cycle summary. */
    const val MAX_FINDINGS = 4

    /** Detect, rank by real-money impact, and keep the best [MAX_FINDINGS]. */
    fun detect(input: InsightInput): List<InsightFinding> {
        if (input.expenseMinor <= 0L) return emptyList()
        val findings = buildList {
            addAll(unusualAndQuiet(input))
            outlierCharge(input)?.let { add(it) }
            duplicateCharges(input).forEach { add(it) }
            addAll(movers(input))
            concentration(input)?.let { add(it) }
        }
        val ranked = findings
            .sortedWith(compareByDescending<InsightFinding> { priority(it.kind) }.thenByDescending { it.materialityMinor })
        // One category, one card. A category that trips "unusual" almost always trips "biggest mover" too —
        // the mover compares against the previous cycle, which is also the first window of the baseline — so
        // without this the carousel says the same thing twice in slightly different words.
        val judged = ranked.filter { it.kind == InsightKind.UNUSUAL_CATEGORY || it.kind == InsightKind.QUIET_WIN }
            .mapNotNull { it.category }
            .toSet()
        return ranked
            .filterNot { (it.kind == InsightKind.MOVER_UP || it.kind == InsightKind.MOVER_DOWN) && it.category in judged }
            .distinctBy { it.kind to it.category }
            .take(MAX_FINDINGS)
    }

    /** Anomalies lead; context cards fill the rest. Within a tier, the biggest rupee impact wins. */
    private fun priority(kind: InsightKind): Int = when (kind) {
        // Never emitted by this engine — the summary is prepended by the ViewModel as page 1.
        InsightKind.CYCLE_SUMMARY -> 6
        InsightKind.DUPLICATE_CHARGE -> 5
        InsightKind.UNUSUAL_CATEGORY -> 4
        InsightKind.OUTLIER_CHARGE -> 3
        InsightKind.QUIET_WIN -> 2
        InsightKind.MOVER_UP, InsightKind.MOVER_DOWN -> 1
        InsightKind.CONCENTRATION -> 0
    }

    // ---- detectors ----

    /** A category well above — or well below — its own typical spend over the prior windows. */
    private fun unusualAndQuiet(input: InsightInput): List<InsightFinding> {
        if (input.history.isEmpty()) return emptyList()
        val out = mutableListOf<InsightFinding>()
        val categories = (input.current.keys + input.history.flatMap { it.keys }).toSet()

        for (category in categories) {
            val seen = input.history.count { it.containsKey(category) }
            if (seen < MIN_HISTORY_WINDOWS) continue
            // Median over the windows where the category actually appears. Substituting ₹0 for the months it
            // doesn't would drag the median down and fire "unusual" on a perfectly normal month — the
            // confidently-wrong card this whole design exists to avoid. The gate above is what earns this.
            val baseline = median(input.history.mapNotNull { it[category] })
            if (baseline <= 0L) continue
            val now = input.current[category] ?: 0L
            val delta = now - baseline
            if (abs(delta) < MIN_MATERIAL_MINOR) continue

            if (now >= baseline * UNUSUAL_MULTIPLE) {
                out += InsightFinding(
                    kind = InsightKind.UNUSUAL_CATEGORY,
                    category = category,
                    amountMinor = now,
                    baselineMinor = baseline,
                    multiple = now.toDouble() / baseline,
                    materialityMinor = delta,
                )
            } else if (now <= baseline * QUIET_WIN_MULTIPLE) {
                out += InsightFinding(
                    kind = InsightKind.QUIET_WIN,
                    category = category,
                    amountMinor = now,
                    baselineMinor = baseline,
                    multiple = if (baseline > 0) now.toDouble() / baseline else 0.0,
                    materialityMinor = -delta,
                )
            }
        }
        return out
    }

    /** The single largest charge this cycle, when it dwarfs what that category normally costs. */
    private fun outlierCharge(input: InsightInput): InsightFinding? {
        val biggest = input.currentSlices.filter { it.amountMinor >= MIN_OUTLIER_MINOR }.maxByOrNull { it.amountMinor }
            ?: return null
        val historic = input.historySlices.filter { it.category == biggest.category }.map { it.amountMinor }
        if (historic.size < 3) return null
        val typical = median(historic)
        if (typical <= 0L || biggest.amountMinor < typical * OUTLIER_MULTIPLE) return null
        return InsightFinding(
            kind = InsightKind.OUTLIER_CHARGE,
            category = biggest.category,
            amountMinor = biggest.amountMinor,
            baselineMinor = typical,
            multiple = biggest.amountMinor.toDouble() / typical,
            materialityMinor = biggest.amountMinor - typical,
        )
    }

    /**
     * Identical charges to the same merchant on the same day.
     *
     * Merchant is required — same-amount, same-day charges to *different* places are an ordinary coincidence
     * (two ₹200 taxis), and flagging those as a possible double-billing would train the user to ignore the
     * card. Only the category name reaches the finding.
     */
    private fun duplicateCharges(input: InsightInput): List<InsightFinding> =
        input.currentSlices
            .filter { it.merchantKey != null && it.amountMinor >= MIN_DUPLICATE_MINOR }
            // One row per transaction first: a split expense arrives as several allocation rows, and an
            // evenly-split charge (₹2,000 → Food ₹1,000 + Groceries ₹1,000) would otherwise look like the
            // same ₹1,000 billed twice.
            .distinctBy { it.expenseId }
            .groupBy { Triple(it.dayEpoch, it.amountMinor, it.merchantKey) }
            .filterValues { it.size >= 2 }
            .map { (_, group) ->
                InsightFinding(
                    kind = InsightKind.DUPLICATE_CHARGE,
                    category = group.first().category,
                    amountMinor = group.first().amountMinor,
                    count = group.size,
                    materialityMinor = group.first().amountMinor * (group.size - 1),
                )
            }
            .sortedByDescending { it.materialityMinor }
            .take(1)

    /** The biggest rise and the biggest fall against the previous cycle. */
    private fun movers(input: InsightInput): List<InsightFinding> {
        val previous = input.previous ?: return emptyList()
        val out = mutableListOf<InsightFinding>()
        val categories = (input.current.keys + previous.keys).toSet()
        val deltas = categories.mapNotNull { category ->
            val before = previous[category] ?: 0L
            val now = input.current[category] ?: 0L
            val delta = now - before
            if (abs(delta) < MIN_MATERIAL_MINOR) null else Triple(category, now, before)
        }
        deltas.maxByOrNull { it.second - it.third }?.let { (category, now, before) ->
            if (now > before) {
                out += InsightFinding(InsightKind.MOVER_UP, category, now, before, materialityMinor = now - before)
            }
        }
        deltas.minByOrNull { it.second - it.third }?.let { (category, now, before) ->
            if (before > now) {
                out += InsightFinding(InsightKind.MOVER_DOWN, category, now, before, materialityMinor = before - now)
            }
        }
        return out
    }

    /** How top-heavy the cycle is — only worth saying when it genuinely is. */
    private fun concentration(input: InsightInput): InsightFinding? {
        if (input.current.size < CONCENTRATION_MIN_CATEGORIES) return null
        val total = input.current.values.sum()
        if (total <= 0L) return null
        val top = input.current.values.sortedDescending().take(CONCENTRATION_TOP_N).sum()
        val percent = ((top.toDouble() / total) * 100).roundToLong().toInt()
        if (percent < CONCENTRATION_MIN_PERCENT) return null
        return InsightFinding(
            kind = InsightKind.CONCENTRATION,
            amountMinor = top,
            sharePercent = percent,
            count = CONCENTRATION_TOP_N,
            // Ranked last anyway; kept small so it never displaces a real anomaly.
            materialityMinor = 0,
        )
    }

    // ---- helpers ----

    /** Median, not mean: one holiday or one insurance renewal should not redefine "usual". */
    internal fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
