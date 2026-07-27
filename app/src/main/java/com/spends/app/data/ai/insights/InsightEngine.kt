package com.spends.app.data.ai.insights

import kotlin.math.abs
import kotlin.math.roundToInt
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
    // ---- comparisons over time (Phase B). All default to "no data", so a caller that cannot supply them
    // ---- simply gets no card rather than a wrong one.
    /** Day of the cycle we have reached, counting the start day as day 1. */
    val daysElapsed: Int = 0,
    /** Length of the whole cycle in days — what tells a part-finished cycle from a completed one. */
    val cycleDays: Int = 0,
    /**
     * **Complete** prior cycles, most recent first — deliberately not day-aligned, unlike [history].
     *
     * A six-cycle trend is a question about whole cycles; truncating each of them to the current cycle's
     * elapsed days would read the truncation itself as the trend.
     */
    val fullHistory: List<Map<String, Long>> = emptyList(),
    /** The same elapsed stretch of the cycle a year ago. Null unless there is genuinely data back there. */
    val yearAgo: YearAgoWindow? = null,
    /** Payday-week vs whole-cycle money and days, over complete prior cycles. */
    val habits: HabitBuckets? = null,
    /**
     * Whether the viewed cycle has finished. Kept separate from `daysElapsed >= cycleDays` because the caller
     * clamps `daysElapsed` to the cycle length, which makes the last day of a live cycle indistinguishable
     * from a cycle browsed back to months later.
     */
    val cycleComplete: Boolean = false,
    /**
     * Whether the cycle's on-screen total can honestly be compared against raw date-window history.
     *
     * False for the card-billing-aware Smart Cycle, where the figures on screen bucket a card purchase into
     * the cycle its statement *bills* while the history queries read raw transaction dates. For a per-category
     * card needing a 2× swing that difference is an approximation; for [InsightKind.PACE] and
     * [InsightKind.YEAR_ON_YEAR], which compare whole totals at a 1.25× and 1.15× bar, it is enough to invent
     * the entire finding — so those two stay silent rather than quote a number built from two different
     * definitions of the same cycle.
     */
    val wholeCycleComparable: Boolean = true,
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

    // ---- comparisons over time ----

    /** Below day 5 one rent charge swamps the cycle, and every user is "miles ahead of usual". */
    private const val PACE_MIN_DAYS = 5
    private const val PACE_MIN_WINDOWS = 3
    private const val PACE_MIN_MINOR = 2_000_00L
    private const val PACE_HIGH = 1.25
    private const val PACE_LOW = 0.75

    /** A year-ago window thinner than this is missing history, not a frugal month. */
    private const val YOY_MIN_BASELINE_MINOR = 5_000_00L
    private const val YOY_MIN_MINOR = 3_000_00L
    private const val YOY_HIGH = 1.15
    private const val YOY_LOW = 0.85

    private const val TREND_MIN_WINDOWS = 6
    /** Present in 5 of 6 cycles. A category you buy in half the months has no trend, only gaps. */
    private const val TREND_MIN_PRESENT = 5
    private const val TREND_MIN_MINOR = 1_500_00L
    private const val TREND_MIN_FRACTION = 0.20

    private const val HABIT_MIN_WINDOWS = 4
    private const val HABIT_MIN_TOTAL_MINOR = 30_000_00L
    private const val HABIT_MIN_SHARE_PERCENT = 25.0
    /** The payday week must take at least this much more than its share of days to be worth remarking on. */
    private const val HABIT_MIN_LIFT = 1.35

    /** How many cards the carousel shows alongside the cycle summary. */
    const val MAX_FINDINGS = 5

    /**
     * Slot reservations, and why they exist.
     *
     * Ranking purely by rupee impact sounds right and produces a carousel that says the same class of thing
     * every cycle: anomalies are measured in whole-category swings and always out-punch a habit or a trend, so
     * the over-time cards would be built and then never seen. Reserving slots is what makes the carousel
     * actually vary — which was the original complaint.
     */
    private const val ANOMALY_SLOTS = 3
    private const val OVER_TIME_SLOTS = 2

    /** Detect, rank by real-money impact, and keep the best [MAX_FINDINGS] with a guaranteed mix. */
    fun detect(input: InsightInput): List<InsightFinding> {
        if (input.expenseMinor <= 0L) return emptyList()
        val findings = buildList {
            addAll(unusualAndQuiet(input))
            outlierCharge(input)?.let { add(it) }
            duplicateCharges(input).forEach { add(it) }
            addAll(movers(input))
            concentration(input)?.let { add(it) }
            pace(input)?.let { add(it) }
            yearOnYear(input)?.let { add(it) }
            categoryTrend(input)?.let { add(it) }
            paydayHabit(input)?.let { add(it) }
        }
        val ranked = findings.sortedWith(byRank)
        // One category, one card. A category that trips "unusual" almost always trips "biggest mover" too —
        // the mover compares against the previous cycle, which is also the first window of the baseline — and
        // it will usually be trending too, since one big cycle drags the recent half of the series up. Without
        // this the carousel says the same thing three times in slightly different words.
        val judged = ranked.filter { it.kind == InsightKind.UNUSUAL_CATEGORY || it.kind == InsightKind.QUIET_WIN }
            .mapNotNull { it.category }
            .toSet()
        val eligible = ranked
            .filterNot { it.kind in RESTATES_A_JUDGED_CATEGORY && it.category in judged }
            .distinctBy { it.kind to it.category }
        return allocate(eligible)
    }

    /** Kinds that would repeat a category already covered by an "unusual"/"quiet win" card. */
    private val RESTATES_A_JUDGED_CATEGORY =
        setOf(InsightKind.MOVER_UP, InsightKind.MOVER_DOWN, InsightKind.CATEGORY_TREND)

    private val byRank =
        compareByDescending<InsightFinding> { priority(it.kind) }.thenByDescending { it.materialityMinor }

    /** Fill the reserved slots first, then backfill in plain rank order. */
    private fun allocate(ranked: List<InsightFinding>): List<InsightFinding> {
        val picked = LinkedHashSet<InsightFinding>()
        ranked.filter { family(it.kind) == Family.ANOMALY }.take(ANOMALY_SLOTS).forEach { picked += it }
        overTimeSlate(ranked).forEach { picked += it }
        // ⭐The one-whole-cycle rule has to hold HERE too, not only in overTimeSlate. Backfilling in plain
        // rank order re-admits the very card the slate skipped, so a quiet cycle would show pace saying
        // "₹5,100 ahead" and year-on-year saying "₹19,470 less" about the same total, on consecutive pages.
        ranked.forEach {
            val clashes = isWholeCycle(it.kind) && picked.any { chosen -> isWholeCycle(chosen.kind) }
            if (picked.size < MAX_FINDINGS && !clashes) picked += it
        }
        return picked.sortedWith(byRank).take(MAX_FINDINGS)
    }

    /** Cards whose subject is the cycle's whole total, of which the carousel shows at most one. */
    private fun isWholeCycle(kind: InsightKind): Boolean =
        kind == InsightKind.PACE || kind == InsightKind.YEAR_ON_YEAR

    /**
     * At most [OVER_TIME_SLOTS] over-time cards, and **at most one of them measured on the whole cycle**.
     *
     * Pace and year-on-year both answer "how does this cycle's total compare", so by materiality they always
     * take both slots and the category trend and the payday habit never appear. Capping the total-level pair
     * at one keeps the second slot for something that says a different kind of thing.
     */
    private fun overTimeSlate(ranked: List<InsightFinding>): List<InsightFinding> {
        val out = mutableListOf<InsightFinding>()
        var wholeCycleUsed = false
        for (finding in ranked.filter { family(it.kind) == Family.OVER_TIME }) {
            if (out.size >= OVER_TIME_SLOTS) break
            if (isWholeCycle(finding.kind)) {
                if (wholeCycleUsed) continue
                wholeCycleUsed = true
            }
            out += finding
        }
        return out
    }

    /** What a card is *about*, which is what the slot reservations are drawn against. */
    private enum class Family { ANOMALY, OVER_TIME, CONTEXT }

    private fun family(kind: InsightKind): Family = when (kind) {
        InsightKind.DUPLICATE_CHARGE -> Family.ANOMALY
        InsightKind.UNUSUAL_CATEGORY -> Family.ANOMALY
        InsightKind.OUTLIER_CHARGE -> Family.ANOMALY
        InsightKind.QUIET_WIN -> Family.ANOMALY
        InsightKind.PACE -> Family.OVER_TIME
        InsightKind.YEAR_ON_YEAR -> Family.OVER_TIME
        InsightKind.CATEGORY_TREND -> Family.OVER_TIME
        InsightKind.HABIT_PAYDAY -> Family.OVER_TIME
        InsightKind.MOVER_UP -> Family.CONTEXT
        InsightKind.MOVER_DOWN -> Family.CONTEXT
        InsightKind.CONCENTRATION -> Family.CONTEXT
        // Never emitted by this engine; the ViewModel prepends it as page 1.
        InsightKind.CYCLE_SUMMARY -> Family.CONTEXT
    }

    /** Anomalies lead; context cards fill the rest. Within a tier, the biggest rupee impact wins. */
    private fun priority(kind: InsightKind): Int = when (kind) {
        // Never emitted by this engine — the summary is prepended by the ViewModel as page 1.
        InsightKind.CYCLE_SUMMARY -> 99
        // The two charge-level cards lead. They are the ones that can surface an actual billing mistake, and
        // with only three anomaly slots, ranking "a category is up" above "one ₹12,500 charge is 6× normal"
        // pushed the more actionable card off the carousel entirely whenever two categories ran hot.
        InsightKind.DUPLICATE_CHARGE -> 9
        InsightKind.OUTLIER_CHARGE -> 8
        InsightKind.UNUSUAL_CATEGORY -> 7
        InsightKind.QUIET_WIN -> 6
        InsightKind.PACE -> 5
        InsightKind.YEAR_ON_YEAR -> 4
        InsightKind.CATEGORY_TREND -> 3
        InsightKind.HABIT_PAYDAY -> 2
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

    // ---- comparisons over time ----

    /**
     * Where this cycle stands *at this point in it*, against where earlier cycles stood at the same point.
     *
     * Both sides are real: the baseline is the median of what was actually spent in the first N days of each
     * earlier cycle ([InsightWindows]), so there is no pro-rating and no invented figure. This is descriptive
     * only — it reports where you are, never where you are heading.
     */
    private fun pace(input: InsightInput): InsightFinding? {
        if (input.daysElapsed < PACE_MIN_DAYS) return null
        // A completed cycle has no "so far". Saying "day 30 of the cycle and…" about a cycle the user has
        // navigated back to would be describing the past in the present tense. This is an explicit flag
        // rather than `daysElapsed >= cycleDays`, which cannot tell the LAST day of a live cycle from a
        // finished one once daysElapsed is clamped — and would silently lose the card on day 30 of 30.
        if (input.cycleComplete || input.cycleDays <= 0) return null
        if (!input.wholeCycleComparable) return null
        val totals = input.history.filter { it.isNotEmpty() }.map { it.values.sum() }
        if (totals.size < PACE_MIN_WINDOWS) return null
        val baseline = median(totals)
        if (baseline <= 0L) return null
        // The current figure is summed from the SAME categorised basis the history buckets are built from,
        // not from the screen's headline expense total. Comparing a total that includes uncategorised spend
        // against a baseline that cannot would put a permanent thumb on the scale.
        val now = input.current.values.sum()
        // Symmetry with yearOnYear. Nothing categorised, but a non-zero expense total, would otherwise slip
        // through the ratio gate (0.0 is not > PACE_LOW) and render "₹0 spent" under a donut showing ₹50,000.
        if (now <= 0L) return null
        val delta = now - baseline
        if (abs(delta) < PACE_MIN_MINOR) return null
        val multiple = now.toDouble() / baseline
        if (multiple < PACE_HIGH && multiple > PACE_LOW) return null
        return InsightFinding(
            kind = InsightKind.PACE,
            amountMinor = now,
            baselineMinor = baseline,
            multiple = multiple,
            days = input.daysElapsed,
            materialityMinor = abs(delta),
        )
    }

    /** The same stretch of the cycle, a year earlier. The caller guarantees the window has real data in it. */
    private fun yearOnYear(input: InsightInput): InsightFinding? {
        val yearAgo = input.yearAgo ?: return null
        if (!input.wholeCycleComparable) return null
        // Same reason pace waits: on day 2 this compares a two-day stretch against a two-day stretch, and one
        // rent payment on either side clears every floor below. "₹40,000 this August against ₹6,000 last
        // August" is technically true of those two days and a false impression of the year.
        if (!input.cycleComplete && input.daysElapsed < PACE_MIN_DAYS) return null
        // A near-empty year-ago window means the app wasn't in use then — "you spent ₹24,000 more than last
        // July" would be flatly false rather than merely unflattering.
        if (yearAgo.expenseMinor < YOY_MIN_BASELINE_MINOR) return null
        val now = input.current.values.sum()
        if (now <= 0L) return null
        val delta = now - yearAgo.expenseMinor
        if (abs(delta) < YOY_MIN_MINOR) return null
        val multiple = now.toDouble() / yearAgo.expenseMinor
        if (multiple < YOY_HIGH && multiple > YOY_LOW) return null
        return InsightFinding(
            kind = InsightKind.YEAR_ON_YEAR,
            amountMinor = now,
            baselineMinor = yearAgo.expenseMinor,
            multiple = multiple,
            // Zero once the cycle is done, which is what switches the wording out of "so far" — a completed
            // cycle browsed back to is still worth comparing, it just isn't happening any more.
            days = if (input.cycleComplete) 0 else input.daysElapsed,
            periodLabel = yearAgo.monthLabel,
            materialityMinor = abs(delta),
        )
    }

    /**
     * One category drifting steadily across six complete cycles.
     *
     * **Reported as two figures the user actually spent**, not as a fitted line. A regression's endpoints are
     * amounts nobody ever paid, and putting one on a card would be the same mistake as pro-rating a baseline.
     * So the card compares the median of the three older cycles against the median of the three recent ones —
     * both real — and the least-squares slope is used *only as a gate*, to reject a V-shape whose halves
     * differ without anything actually trending. The slope itself never reaches the user or the model.
     */
    private fun categoryTrend(input: InsightInput): InsightFinding? {
        val windows = input.fullHistory
        if (windows.size < TREND_MIN_WINDOWS) return null
        val half = windows.size / 2
        return windows.flatMap { it.keys }.toSet().mapNotNull { category ->
            // Most recent first, so `take(half)` is the recent half and `drop(half)` the older one.
            val series = windows.map { it[category] }
            if (series.count { it != null } < TREND_MIN_PRESENT) return@mapNotNull null
            val recent = series.take(half).filterNotNull()
            val older = series.drop(half).filterNotNull()
            if (recent.size < 2 || older.size < 2) return@mapNotNull null
            val newer = median(recent)
            val before = median(older)
            if (before <= 0L || newer <= 0L) return@mapNotNull null
            val delta = newer - before
            if (abs(delta) < TREND_MIN_MINOR) return@mapNotNull null
            if (abs(delta).toDouble() / before < TREND_MIN_FRACTION) return@mapNotNull null
            val direction = slope(series) ?: return@mapNotNull null
            if ((direction > 0) != (delta > 0)) return@mapNotNull null
            InsightFinding(
                kind = InsightKind.CATEGORY_TREND,
                category = category,
                amountMinor = newer,
                baselineMinor = before,
                multiple = newer.toDouble() / before,
                // Cycles the comparison actually rests on, not the span examined: TREND_MIN_PRESENT allows
                // one gap, and claiming "over the last 6 cycles" on five cycles' data overstates it.
                spanCycles = series.count { it != null },
                materialityMinor = abs(delta),
            )
        }.maxByOrNull { it.materialityMinor }
    }

    /**
     * A disproportionate share of spending landing in the week after payday.
     *
     * Stated as **two shares** — of money and of days — rather than as a per-day average. An average daily
     * spend is a figure nobody spent; "38% of your money in 23% of the days" is two facts, and the reader can
     * see the gap without being told what to conclude.
     */
    private fun paydayHabit(input: InsightInput): InsightFinding? {
        val habits = input.habits ?: return null
        if (habits.windowsWithData < HABIT_MIN_WINDOWS) return null
        if (habits.totalMinor < HABIT_MIN_TOTAL_MINOR) return null
        if (habits.totalDays <= 0 || habits.paydayWeekDays <= 0) return null
        val moneyShare = (habits.paydayWeekMinor.toDouble() / habits.totalMinor) * 100.0
        val dayShare = (habits.paydayWeekDays.toDouble() / habits.totalDays) * 100.0
        if (dayShare <= 0.0) return null
        if (moneyShare < HABIT_MIN_SHARE_PERCENT) return null
        if (moneyShare < dayShare * HABIT_MIN_LIFT) return null
        // Ranking only — never shown, never sent. See InsightFinding.materialityMinor. Multiply before
        // dividing: the other order truncates the per-day figure first and loses rupees to rounding.
        val evenSpread = habits.totalMinor * habits.paydayWeekDays / habits.totalDays
        return InsightFinding(
            kind = InsightKind.HABIT_PAYDAY,
            sharePercent = moneyShare.roundToInt(),
            dayShare = dayShare.roundToInt(),
            multiple = moneyShare / dayShare,
            count = habits.windowsWithData,
            materialityMinor = habits.paydayWeekMinor - evenSpread,
        )
    }

    // ---- helpers ----

    /**
     * Least-squares slope over a series given most-recent-first, with gaps skipped at their real position.
     *
     * A **gate, not a figure**: only its sign is ever read. Returns null when there are too few points for a
     * line to mean anything.
     */
    private fun slope(seriesMostRecentFirst: List<Long?>): Double? {
        val size = seriesMostRecentFirst.size
        // Flip the index so x increases with time; a positive slope then means "going up".
        val points = seriesMostRecentFirst.mapIndexedNotNull { index, value ->
            value?.let { (size - 1 - index).toDouble() to it.toDouble() }
        }
        if (points.size < 3) return null
        val meanX = points.sumOf { it.first } / points.size
        val meanY = points.sumOf { it.second } / points.size
        var covariance = 0.0
        var variance = 0.0
        points.forEach { (x, y) ->
            covariance += (x - meanX) * (y - meanY)
            variance += (x - meanX) * (x - meanX)
        }
        return if (variance == 0.0) null else covariance / variance
    }

    /**
     * Median, not mean: one holiday or one insurance renewal should not redefine "usual".
     *
     * ⭐**Always an actual observation, even for an even-length list.** The textbook median averages the two
     * middle values, which for six cycles of history — the ordinary case — produces a baseline that is not
     * any cycle's real total. The cards then assert *"your recent cycles **were at** ₹19,000 by this point"*
     * about a figure nobody ever spent, which is precisely the guarantee this whole design exists to make.
     * So an even-length list takes the lower of the two middles.
     *
     * Be honest about the side effect rather than dressing it up: a baseline at or slightly below the true
     * median makes the *rise* tests marginally easier to trip (unusual at 2×, outlier at 3×, pace at 1.25×)
     * and the *fall* tests marginally harder (quiet win at 0.6×, pace at 0.75×). The shift is bounded by the
     * gap between the two middle values and is zero whenever they are equal, which is the common case; the
     * absolute rupee floors every detector also carries are what stop it mattering.
     */
    internal fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 2]
    }
}
