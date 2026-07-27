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
 * The user's standing monthly recurring load.
 *
 * ⭐**Monthly rules only, summed at face value.** Weekly, daily and yearly rules are excluded by the
 * caller rather than normalised, because normalising them invents money: a ₹12,000 annual premium folded
 * in as "₹1,000 a cycle" is a figure the user has never paid, and this engine's first rule is that it never
 * reports one. The card is honest about what it counts — the monthly rules that were set up — and never
 * claims to have found every fixed cost.
 */
data class CommitmentTotals(val monthlyMinor: Long, val ruleCount: Int)

/** Income and expense over one window, both measured to the same point in their own cycle. */
data class SavingsWindow(val incomeMinor: Long, val expenseMinor: Long)

/**
 * Day-aligned income and expense for this cycle and each prior one.
 *
 * ⭐[prior] is deliberately **its own list** rather than something the engine pairs against
 * `InsightInput.history` by index. `history` is filtered for empty windows before it arrives, so pairing
 * positionally against an unfiltered income list would silently line a cycle's income up against a
 * different cycle's spending — the exact class of off-by-one the Phase B round kept surfacing.
 *
 * ℹ [prior] and [InsightInput.priorFullCycleIncomeMinor] are index-aligned in practice — the provider
 * builds both in one indexed pass — but **nothing reads them together any more.** A commitments gate briefly
 * did; it was deleted along with the part-month denominator. Recorded so the next reader does not assume the
 * pairing is load-bearing, and so anyone who wants to rely on it again knows it is a provider convention
 * rather than something these types enforce.
 */
data class SavingsWindows(val current: SavingsWindow, val prior: List<SavingsWindow>)

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
    // ---- judgement calls (Phase C). All default to "no data", so a caller that cannot supply them gets no
    // ---- card rather than a wrong one — the same contract the Phase B fields carry.
    /**
     * The standing monthly recurring load, or null when no rule qualifies.
     *
     * ⚠ The commitments card additionally requires [priorFullCycleIncomeMinor], which is its denominator.
     * It does NOT use [savings] or this cycle's income at all. Supplying commitments alone yields no card.
     */
    val commitments: CommitmentTotals? = null,
    /** Day-aligned income vs expense, this cycle and the prior ones. */
    val savings: SavingsWindows? = null,
    /**
     * Income over each prior **complete** cycle — the commitments card's denominator.
     *
     * Deliberately whole-cycle rather than day-aligned, unlike [savings], and since 2026-07-27 it is the
     * figure the card divides BY rather than a yardstick for judging this cycle's income. That is what lets
     * the card carry no timing gates at all; see `InsightEngine.commitments`.
     */
    val priorFullCycleIncomeMinor: List<Long> = emptyList(),
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
    /** The payday week must take at least this much more than its share of days to be worth remarking on. */
    private const val HABIT_MIN_LIFT = 1.35

    // ---- judgement calls (Phase C) ----

    private const val COMMITMENTS_MIN_MINOR = 2_000_00L
    private const val COMMITMENTS_MIN_INCOME_MINOR = 5_000_00L

    /**
     * ⭐⭐How far the BEST prior full cycle may exceed the typical one before "your income" stops meaning
     * anything, as a percentage above the median.
     *
     * The denominator is the median of the user's completed cycles, which only means anything if they have
     * a typical month at all. A single cycle far above the median is proof that they do not — "what usually
     * comes in" would be a fiction whichever cycle you picked.
     *
     * (The history below describes an earlier design in which this gate protected a *part-month* denominator.
     * That denominator is gone; the sweeps are kept because they are why the bar is measured against the
     * median rather than against the worst cycle.)
     *
     * ⭐**Two earlier versions of this gate were wrong, both caught by executing a sweep rather than
     * reasoning.** A best-to-worst RATIO of 3× was too weak (exactly 3.00× passed, and a third of fired
     * cards for commission and gig income overstated by 10+ points, worst 99% quoted against a true 46%) and
     * simultaneously too expensive — because the ₹5,000 prior filter runs first, one month logged at ₹4,999
     * kept the card while ₹5,000 lost it, and an ordinary raise or a two-credit salary silenced a completely
     * typical salaried user. Gating on the two MIDDLE cycles fixed the cliffs but still left 6–7% of cards
     * misleading for freelance, seasonal and mid-window-raise income.
     *
     * Measuring the top against the median fixes both, because a low outlier cannot move it and a high one
     * always can. Verified over 12,000 trials per income pattern with a realistic lump-arrival model:
     * flat-salaried and one-odd-month users keep the card **100%** of the time once their salary lands,
     * while every erratic pattern drops below the 5% misleading bar.
     *
     * The honest cost: someone with an annual bonus above 1.5× their usual month loses this card for the six
     * cycles that bonus stays in the baseline. That is a missed card, which is the cheap direction.
     */
    private const val COMMITMENTS_MAX_INCOME_SPIKE_PERCENT = 50

    private const val SAVINGS_MIN_INCOME_MINOR = 5_000_00L
    private const val SAVINGS_MIN_WINDOWS = 3
    /**
     * Below this the two rates are the same rate with rounding noise on top.
     *
     * Reused as the ambiguity bar on the baseline's two middle values, deliberately: if swapping which
     * middle `medianInt` picks would move the baseline by more than the amount that makes two rates
     * *different*, then the pick — not the data — is deciding what the card says.
     *
     * ⭐**The safety of that reuse rests on an invariant nobody wrote down until round 13:
     * `differenceBar >= ambiguityBar / 2`.** An ambiguity bar of `P` bounds the gap between the textbook
     * centre and the lower middle at `P/2`, so a difference bar of `P` clears it with 100% margin — which is
     * why one constant serving both roles produces **zero** headline inversions (measured: 0 in 123,614
     * fired cards). Decouple them and it breaks: hold the difference bar at 8, raise the ambiguity bar to
     * 30, and a user with priors [1, 1, 1, 31, 31, 31] is told *"you've kept 9%, ahead of the 1% you'd
     * usually have kept"* when the true centre is 16% and they are behind it. If these ever become two
     * constants, that inequality must hold.
     */
    private const val SAVINGS_MIN_POINTS = 8

    /**
     * How many cards the carousel shows alongside the cycle summary.
     *
     * Raised 5 → 6 in Phase C. The two judgement cards reserve two slots between them; without an extra
     * page they would take those slots from the anomaly and over-time families, which is the same failure
     * the Phase B slot reservation exists to prevent.
     *
     * ⚠ Phase C planned four new kinds and shipped **two**. An advice card was dropped by the owner on
     * 2026-07-27; a per-category weekend-habit card was dropped after review round 11 (see the phase doc).
     * Six is still right for two: it is the slot RESERVATION that needs the room, not the count of new
     * kinds — without it a cycle full of anomalies would crowd both judgement cards off the carousel.
     */
    const val MAX_FINDINGS = 6

    /**
     * Slot reservations, and why they exist.
     *
     * Ranking purely by rupee impact sounds right and produces a carousel that says the same class of thing
     * every cycle: anomalies are measured in whole-category swings and always out-punch a habit or a trend, so
     * the over-time cards would be built and then never seen. Reserving slots is what makes the carousel
     * actually vary — which was the original complaint.
     */
    /**
     * ⚠ Same honest coverage note as [JUDGEMENT_SLOTS]: the value is **live** (4 and 5 behave differently)
     * but 0, 1, 2 and 3 are indistinguishable by every committed fixture, because anomalies occupy the top
     * four priority tiers and win the backfill whether or not a slot is reserved for them. The reservation
     * earns its keep only in states no fixture reaches.
     */
    private const val ANOMALY_SLOTS = 3
    private const val OVER_TIME_SLOTS = 2

    /**
     * Reserved for the Phase C judgement cards — commitments and the savings rate.
     *
     * 3 + 2 + 2 = 7 reserved against [MAX_FINDINGS] of 6, deliberately. The final `take` trims the
     * lowest-ranked, which is what lets a cycle with nothing to say in one family hand its slot to another
     * rather than shipping a blank page.
     *
     * Two slots for a family that now has only two members looks generous, and it is: the savings rate is
     * additionally capped against pace and year-on-year by [isWholeCycle], so in practice this family
     * usually delivers one card. The reservation exists so that the one it does deliver cannot be crowded
     * out by three anomalies, which by rupee impact would otherwise always win.
     *
     * ⚠ **Known coverage gap, recorded rather than papered over (review round 12).** No committed fixture
     * distinguishes 2 from 1. The value IS live and correct — sweeping the allocator over synthetic finding-sets found a
     * substantial minority of states where 2 and 1 differ (two independent runs agreed on the direction;
     * neither script is committed, so treat the direction as the claim and the count as illustrative) — but every fixture that reaches this family either
     * yields one judgement card anyway (because `busyCycle` triggers pace, which is whole-cycle and so
     * blocks the savings rate) or has fewer than [MAX_FINDINGS] findings, where the backfill would admit the
     * second card regardless of the reservation.
     *
     * What would pin it: three anomalies, exactly ONE over-time finding that is **not** pace or
     * year-on-year, both judgement cards, and two movers whose materiality exceeds the commitments total.
     * At 2 slots both judgement cards ship; at 1, a mover takes the last seat. That fixture was not written
     * because building it correctly needs the anomaly multiples and the pace ratio tuned against each other,
     * and a subtly wrong test is worse than an absent one — but it is the shape to build.
     */
    private const val JUDGEMENT_SLOTS = 2

    /** Detect, rank by real-money impact, and keep the best [MAX_FINDINGS] with a guaranteed mix. */
    fun detect(input: InsightInput): List<InsightFinding> {
        // ⚠ This also silences COMMITMENTS, which uses no expense figure at all — reachable for someone
        // paid on day 1 who has not spent yet. Left as is: the carousel is a spending surface, and a lone
        // card on a page that says nothing about spending is worse than waiting a day. Silence, so the cheap
        // direction — but it is a gate the commitments card's own gate list does not mention.
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
            commitments(input)?.let { add(it) }
            savingsRate(input)?.let { add(it) }
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
    private val RESTATES_A_JUDGED_CATEGORY = setOf(
        InsightKind.MOVER_UP,
        InsightKind.MOVER_DOWN,
        InsightKind.CATEGORY_TREND,
    )

    private val byRank =
        compareByDescending<InsightFinding> { priority(it.kind) }.thenByDescending { it.materialityMinor }

    /** Fill the reserved slots first, then backfill in plain rank order. */
    private fun allocate(ranked: List<InsightFinding>): List<InsightFinding> {
        val picked = mutableListOf<InsightFinding>()
        ranked.filter { family(it.kind) == Family.ANOMALY }.take(ANOMALY_SLOTS).forEach { picked += it }
        picked += slate(ranked, Family.OVER_TIME, OVER_TIME_SLOTS, picked)
        picked += slate(ranked, Family.JUDGEMENT, JUDGEMENT_SLOTS, picked)
        // ⭐The caps have to hold HERE too, not only inside the slates. Backfilling in plain rank order
        // re-admits the very card a slate skipped, so a quiet cycle would show pace saying "₹5,100 ahead"
        // and year-on-year saying "₹19,470 less" about the same total, on consecutive pages.
        ranked.forEach {
            if (picked.size < MAX_FINDINGS && it !in picked && !clashes(it, picked)) picked += it
        }
        return picked.sortedWith(byRank).take(MAX_FINDINGS)
    }

    /**
     * Up to [slots] cards from one family, skipping any that would repeat something already chosen.
     *
     * Takes what has already been picked because the caps span families: the savings rate is a JUDGEMENT
     * card but it is measured on the whole cycle, exactly like pace, so it has to be checked against an
     * OVER_TIME choice made a moment earlier rather than only against its own family.
     */
    private fun slate(
        ranked: List<InsightFinding>,
        target: Family,
        slots: Int,
        alreadyPicked: List<InsightFinding>,
    ): List<InsightFinding> {
        val out = mutableListOf<InsightFinding>()
        // `target`, not `family` — naming the parameter after the function is legal today only because
        // Family has no `invoke` operator, and would silently rebind the call if anyone ever added one.
        for (finding in ranked.filter { family(it.kind) == target }) {
            if (out.size >= slots) break
            if (finding in alreadyPicked) continue
            if (clashes(finding, alreadyPicked + out)) continue
            out += finding
        }
        return out
    }

    /** Whether [finding] would repeat what an already-chosen card is fundamentally about. */
    private fun clashes(finding: InsightFinding, chosen: List<InsightFinding>): Boolean =
        isWholeCycle(finding.kind) && chosen.any { isWholeCycle(it.kind) }

    /**
     * Cards whose subject is the cycle's whole total, of which the carousel shows at most one.
     *
     * The savings rate joins pace and year-on-year in Phase C: all three answer "how does this cycle's total
     * compare", and by materiality they would otherwise take every over-time and judgement slot between them.
     */
    private fun isWholeCycle(kind: InsightKind): Boolean =
        kind == InsightKind.PACE || kind == InsightKind.YEAR_ON_YEAR || kind == InsightKind.SAVINGS_RATE

    /** What a card is *about*, which is what the slot reservations are drawn against. */
    private enum class Family { ANOMALY, OVER_TIME, JUDGEMENT, CONTEXT }

    private fun family(kind: InsightKind): Family = when (kind) {
        InsightKind.DUPLICATE_CHARGE -> Family.ANOMALY
        InsightKind.UNUSUAL_CATEGORY -> Family.ANOMALY
        InsightKind.OUTLIER_CHARGE -> Family.ANOMALY
        InsightKind.QUIET_WIN -> Family.ANOMALY
        InsightKind.PACE -> Family.OVER_TIME
        InsightKind.YEAR_ON_YEAR -> Family.OVER_TIME
        InsightKind.CATEGORY_TREND -> Family.OVER_TIME
        InsightKind.HABIT_PAYDAY -> Family.OVER_TIME
        InsightKind.COMMITMENTS -> Family.JUDGEMENT
        InsightKind.SAVINGS_RATE -> Family.JUDGEMENT
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
        // The payday habit and the savings rate share a tier: both are "here is a pattern in how you
        // spend", and which of them leads should come down to size, not to a hard-coded pecking order.
        InsightKind.HABIT_PAYDAY -> 2
        InsightKind.SAVINGS_RATE -> 2
        InsightKind.COMMITMENTS -> 1
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
        // An assertion, not a gate: `InsightCalendar.habitBuckets` counts real days over real cycle
        // boundaries, and `InsightsProvider` returns before building any of this when there are fewer than
        // two boundaries. Labelled so the next audit does not miscount it as protection.
        if (habits.totalDays <= 0 || habits.paydayWeekDays <= 0) return null
        val moneyShare = (habits.paydayWeekMinor.toDouble() / habits.totalMinor) * 100.0
        val dayShare = (habits.paydayWeekDays.toDouble() / habits.totalDays) * 100.0
        // An assertion, not a gate — the line above already guarantees both counts are >= 1, so this share
        // is > 0. Labelled because an unlabelled impossible check gets miscounted as protection by the
        // next person to audit these detectors.
        if (dayShare <= 0.0) return null
        // ⭐An absolute 25% floor stood here until review round 5 proved it was dead code. `habitBuckets`
        // builds
        // `paydayDays = sum(min(7, days))` against `totalDays = sum(days)`, so over any run of cycles
        // `CycleUtils` can produce (28–31 days, 4–6 windows) the lift bar bottoms out at **30.48%** and
        // always subsumed the 25%. It could only bite at cycles of 38 days or more, which cannot occur.
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

    // ---- judgement calls (Phase C) ----

    /**
     * The standing monthly recurring load, against what a cycle **usually** brings in.
     *
     * ⭐⭐⭐**The denominator is the user's usual full-cycle income, NOT income so far, and that is the
     * whole design.** Owner decision, 2026-07-27, after seven review rounds.
     *
     * The card began as "commitments as a share of the income that has come in so far". That divides a full
     * month's commitments by a part month's income, and **no gate built from history can make it honest**,
     * because nothing in the past bounds what THIS cycle will finish at. Each round closed one hole and the
     * next round found another:
     *
     * - **R3** — no day gate at all: a ₹10,000 side payment on day 3 made commitments "80% of income".
     * - **R4** — a day floor plus "80% of the day-aligned usual" was circular for anyone paid late in the
     *   month, whose usual income by day 12 is near zero.
     * - **R4** — a whole-cycle yardstick, gated on a best-to-worst ratio: too weak (a third of commission
     *   and gig cards out by 10+ points, worst 99% quoted against a true 46%) and simultaneously too
     *   expensive (one month logged at ₹4,999 kept the card, ₹5,000 lost it).
     * - **R5** — gating on the two middle cycles fixed the cliffs, still left 6–7% misleading.
     * - **R5** — raising the arrived bar 80 → 95 cut the worst case from +15 to +3 **only when the priors
     *   resemble this cycle**; it bounds `income / prior median`, never `income / this cycle's final`.
     * - **R6** — requiring the user's own usual arrival share to reach 95% took the measured rates to zero
     *   on rescaled income — and **R7 defeated it in two lines**: a tutor whose exam-season classes bill on
     *   day 27, or a salaried user with a quarterly incentive, has ordinary priors that all read 100%
     *   arrived, so the gate is silent while this cycle's extra has not landed. +17 and +18 points. The same
     *   gate also removed up to **77%** of cards from anyone paid in instalments, and 94–100% of those cards
     *   had been honest.
     *
     * Four sweeps were run across those rounds and **every one was unsound in a different way** — wrong
     * yardstick, lump arrival, narrow uniform ranges, and finally an arrival model in which a cycle's
     * arrival share cannot differ from the user's usual one. That is the tell: when the measurement keeps
     * having to be rebuilt to see the next defect, the quantity being measured is the problem.
     *
     * So the question was narrowed instead of gated — the same move Phase A made when it stopped scaling
     * baselines. **Both figures are now stable and real**: the sum of the user's own monthly rules that have
     * already started, and the median of their own completed cycles' income. Neither depends on where in the
     * cycle they are, so the card needs no day floor, no arrived bar, no arrival
     * gate and no `wholeCycleComparable` — four gates went with the old denominator, and with them an
     * entire class of defect. What remains is a standing fact about their money: a third of what usually
     * comes in is already spoken for.
     *
     * `cycleComplete` came BACK in round 8, and it is worth being precise about why, because it looks like a
     * regression and is not: it is an **epoch** gate, not a timing one. Nothing here moves with the day
     * *within* a cycle — that property is intact and is tested. But the numerator is today's rule set while
     * the denominator is the cycles before whichever cycle is on screen, so browsing back would assert
     * today's commitments against somebody else's history, in the present tense.
     *
     * The honest cost: this is no longer news about *this* cycle. It reads the same until their rules or
     * their income change. That is the trade the owner chose, and it is the right one — a card that says a
     * true thing quietly beats a card that says an interesting thing wrongly.
     *
     * ⚠ **Accepted residual: a pay CUT lags by up to three cycles** (counting the cycle the cut lands in — the same convention as the rise below). A median of six takes three cycles to
     * move, and the spike gate measures the *best* cycle against the median, so it cannot see a fall.
     * Measured: one or two cycles after a ₹90,000 → ₹45,000 cut the card still quotes "32% of ₹90,000"
     * when the truth is 63% of ₹45,000 — about 31 points of UNDERSTATEMENT, the direction that makes
     * commitments look more affordable than they are. The third cycle still quotes it; the spike gate fires from
     * the fourth, and the card stays quiet until the new level becomes the median. A recency gate was tried and rejected: it kills the
     * "one odd low month must not cost the card" case bought in round 4.
     *
     * ⚠ **A pay RISE lags longer, by more, and the earlier claim that it was "the safe direction" was
     * doing more work than it had earned.** After a ₹90,000 → ₹1,35,000 rise (+50%, exactly at the spike
     * bar, so the gate never sees it) the card quotes 32% against a true 21% for **four** cycles — and where
     * commitments equal the old usual income, 100% against a true 67%, an overstatement of **33 points**.
     * Longer and larger than the cut's three cycles at −31. It is accepted because the direction is
     * conservative (commitments look *less* affordable than they are), not because it is small.
     */
    private fun commitments(input: InsightInput): InsightFinding? {
        val commitments = input.commitments ?: return null
        // An assertion, not a gate: `InsightsProvider.monthlyCommitments` returns null rather than a zero
        // count, so nothing can reach here with one. Kept because the type permits it.
        if (commitments.ruleCount <= 0) return null
        if (commitments.monthlyMinor < COMMITMENTS_MIN_MINOR) return null
        // ⭐Not a timing gate — an EPOCH gate, and the distinction matters because the whole point of this
        // card's rewrite was to remove timing gates. The numerator is **today's** rule set (the app cannot
        // reconstruct a historical one), while the denominator is the six cycles before whichever cycle is
        // on screen. Stepping back with the prev arrow keeps the range on CURRENT, so a cycle from three
        // months ago really does reach here — and the card would then assert today's commitments against
        // income from cycles four to nine back, in the present tense, with nothing on the card saying so.
        // Within a live cycle nothing here moves with the day; that property is unaffected and is tested.
        if (input.cycleComplete) return null
        // The usual income of a COMPLETE cycle — a figure some cycle of theirs genuinely reached, and the
        // reason this card carries no timing gates at all. See the KDoc above.
        //
        // ⭐⭐**The trim drops trailing ZEROS — not sub-floor cycles — and it TRIMS rather than FILTERS.**
        // Both halves of that sentence were learned the hard way, one round apart. An
        // earlier version filtered every sub-floor cycle out and took the median of what was left — the
        // median of the user's *good* cycles, which is the self-selecting baseline this project already had
        // to fix once on the savings rate. It told someone who lost their job three cycles ago, whose last
        // three cycles logged ₹0, that their ₹28,400 of commitments was "32% of the ₹90,000 that usually
        // comes in each cycle". A seasonal earner whose lean months run ₹4,000 got the same sentence, while
        // in half of their cycles those commitments are 7x that cycle's entire income.
        //
        // The list is newest-first, so **trailing ZERO** buckets are cycles from before the app was
        // installed — absence of data, correctly dropped. Anything else stays, because a real lean or empty
        // cycle is precisely what makes "usually" untrue. The median then has to clear the floor on its own.
        //
        // ⭐⭐**`== 0L`, not `< COMMITMENTS_MIN_INCOME_MINOR`, and the difference is measurable.** Trimming
        // everything sub-floor asserts that a lean stretch 4–6 cycles ago is pre-install data — which is not
        // derivable from the numbers, because the two are byte-identical. It also shortened the list enough
        // to move the lower-middle median onto a good cycle, so whether a seasonal earner got a false card
        // came down to a **phase offset**: `LLLGGG` was silenced but `LGGLLL` — the same four lean cycles in a
        // different order — still announced "32% of ₹90,000". (Strictly those two are not permutations:
        // `LLLGGG` has three lean cycles and `LGGLLL` four. The equal-count pair that shows it is `LLLGGG`,
        // silent, against `GGGLLL`, which fires — the same three lean cycles, reordered.) Enumerating all 64
        // lean/good orderings:
        // trimming sub-floor fires on **34 of 64** (27 of them carrying two or more lean cycles), worst case
        // **4 lean cycles of 6**; trimming only zeros fires on **22 of 64** (15 with two or more), worst
        // **2 of 6** — exactly the "one or two odd months must not cost the card" intent.
        //
        // ⚠ An earlier version of this comment quoted 27 and 15 as if they were counts out of 64; they were
        // the ≥2-lean subsets. Third time this file has carried a number nobody could reproduce, so: both
        // figures above are of 64, and the worst-case counts (4 and 2) were correct throughout.
        val priorIncome = input.priorFullCycleIncomeMinor
            .dropLastWhile { it == 0L }
            // `SAVINGS_MIN_WINDOWS` is deliberately shared with the savings rate: both cards are asking
            // "is there enough income history for a median to mean anything", so they should agree. Retuning
            // it moves a gate on both — which is the intent, but worth knowing before touching it.
            .takeIf { it.size >= SAVINGS_MIN_WINDOWS }
            ?: return null
        val usualFullCycleIncome = median(priorIncome)
        if (usualFullCycleIncome < COMMITMENTS_MIN_INCOME_MINOR) return null
        // Is there a "usual" at all? Judged by how far the BEST cycle sits above the typical one, not by
        // best-against-worst: a low outlier (a month someone forgot to confirm, a mid-cycle join, a salary
        // split across two credits) says nothing about whether this user has a typical month, while a single
        // cycle far above the median means the median is not one. See the constant for the two weaker
        // versions of this gate that sweeps proved wrong.
        if (priorIncome.max() > usualFullCycleIncome * (100 + COMMITMENTS_MAX_INCOME_SPIKE_PERCENT) / 100) {
            return null
        }
        // Commitments above a whole cycle's income is a real situation, but not one this card should
        // announce as a share — "108% of what usually comes in" is a sentence that helps nobody.
        if (commitments.monthlyMinor > usualFullCycleIncome) return null
        return InsightFinding(
            kind = InsightKind.COMMITMENTS,
            amountMinor = commitments.monthlyMinor,
            baselineMinor = usualFullCycleIncome,
            sharePercent = ((commitments.monthlyMinor.toDouble() / usualFullCycleIncome) * 100).roundToInt(),
            count = commitments.ruleCount,
            materialityMinor = commitments.monthlyMinor,
        )
    }

    /**
     * How much of what came in has been kept **by this point**, against the same point in earlier cycles.
     *
     * ⭐Day-aligned for the same reason everything else here is. Salary lands on day 1 and spending
     * accumulates after it, so a flat "you've kept 91% of your income this cycle" is true on day 3, decays
     * all cycle, and flatters the user for three weeks before quietly going away. Comparing like with like —
     * this cycle's share at day N against the median share at day N of earlier cycles — is the only version
     * that says something.
     */
    private fun savingsRate(input: InsightInput): InsightFinding? {
        val savings = input.savings ?: return null
        if (!input.wholeCycleComparable) return null
        // Same floor as pace and year-on-year: in the opening days one rent charge moves the share by tens
        // of points, on both sides of the comparison.
        if (input.daysElapsed < PACE_MIN_DAYS) return null
        // Every figure here says "so far" and "by this point". A cycle browsed back to is reachable with the
        // range still on CURRENT, and describing it in the present tense would be false — see commitments.
        if (input.cycleComplete) return null
        val income = savings.current.incomeMinor
        if (income < SAVINGS_MIN_INCOME_MINOR) return null
        // ⭐⭐The two sides of this subtraction must be measured on the SAME basis, and by default they are
        // not. `incomeMinor` is the screen's headline income — every income row. The expense side is the
        // CATEGORISED total, because that is the only basis the prior windows can be built from (they come
        // from the allocations join). Pace can live with that gap: it compares expense against expense, so
        // the bias cancels. This card cannot, because it does not merely compare — it STATES `kept` as a
        // rupee amount and sends it to the model as `keptSoFarAmount`.
        //
        // With ₹10,000 of uncategorised spend the card reads *"You've kept ₹60,000 of what came in so far
        // — 67%"* when the true figure is ₹50,000 and 56%. That is a number the app invented, about money
        // the user does not have, which is the one thing this engine exists not to do.
        //
        // So it refuses to speak when the two bases disagree at all. The share would still have been
        // defensible (both sides carry the same bias); the amount would not, and the amount is what the
        // sentence leads with.
        if (input.expenseMinor != savings.current.expenseMinor) return null
        // ⚠ In a healthy database this can never fire, and round 5 proved it: every write path allocates
        // 100% of an expense's amount (create, update, reassign, all three capture paths, import, restore),
        // categories are archived rather than hard-deleted, and split entry is gated on a zero remainder. So
        // this is an ASSERTION that the invariant still holds, not a gate that routinely rejects — it must
        // not be counted as protection against a defect that exists today. It is kept because the two totals
        // come from two different Room flows, and a future change to either could break the invariant
        // silently; failing closed then costs a card rather than telling someone they have money they do not.
        //
        // ⚠ **A second, accepted residual on the income side, found in round 10.** `savings.current` is
        // built from the WHOLE-window on-screen totals while `savings.prior` is truncated to the elapsed
        // stretch, and the date pickers place no upper bound on `occurredAt`. So a user who logs an invoice
        // dated later this cycle inflates `kept` immediately: on day 12 with ₹90,000 in and ₹30,000 spent,
        // a ₹40,000 entry dated the 25th makes the card read *"you've kept ₹1,00,000 — 77%"* when the
        // honest figures are ₹60,000 and 67%. The word "so far" is then false.
        //
        // Inherited rather than introduced — `pace` has the same whole-window `current` — but this is the
        // first card to turn it into a RUPEE assertion about money in hand. Not fixed here because the
        // honest fix is a `selectableDates` bound on the entry screens, which is a change to the whole app's
        // input rules and belongs in its own round rather than smuggled into an insights release.
        //
        // ⚠ Known asymmetry, accepted: this equality is checked on the CURRENT window only. The prior
        // windows are built from the categorised basis and simply assumed to match, because there is no
        // headline total to compare them against — the provider never queries one per historical window.
        // Under today's write paths every expense carries allocations summing to its full amount, so they do
        // match; if that ever stopped being true, a prior cycle carrying uncategorised spend would overstate
        // its kept-share and this cycle would read as "behind usual" when it is not.
        val kept = income - savings.current.expenseMinor
        // Spending more than came in is a real thing to be told, but not by this card: its whole frame is a
        // share of income, and "you've kept -₹4,000 — -12%" is a sentence nobody should read. Pace and the
        // movers already cover an expensive cycle.
        if (kept <= 0L) return null
        val share = ((kept.toDouble() / income) * 100).roundToInt()
        // Windows where nothing came in are dropped, not counted as 0% kept: a cycle with no recorded
        // income says the salary wasn't logged, not that everything was spent.
        //
        // ⭐Cycles where the user OVERSPENT stay in. Filtering them out was a real defect: it made "the
        // share you'd usually have kept" the median of only the good cycles. For real shares
        // [50, 45, 40, -20, -30, -10] the true median is -10%, but dropping the negatives gave 45% — so a
        // cycle at +35%, far better than this user's usual, was reported as *behind* usual. A name that
        // asserts "usually" has to be measured over all of them.
        // ⭐⭐⭐`> 0`, NOT `>= SAVINGS_MIN_INCOME_MINOR`, and round 12 is why. The comment above justifies
        // dropping a window because "a cycle with no recorded income says the salary wasn't logged" — that
        // reasoning covers an EMPTY window and nothing else. Filtering at ₹5,000 silently deleted windows
        // carrying ₹1,000–₹4,999 of genuinely logged income, which is the self-selecting baseline this
        // engine already had to fix on the commitments card in round 8, sitting here the whole time.
        //
        // A commission earner with day-aligned shares [50, 40, 40, −733, −1000, −500] had the three lean
        // windows dropped and was told *"you've kept 20%, behind the 40% you'd usually have kept"* — when
        // their honest median is −500% and the card should have said nothing at all. The
        // `baselineShare <= 0` gate below cannot save it, because the baseline is doctored before that gate
        // ever sees it. Measured over 32,768 configurations, 47% of fired cards were speaking against a
        // baseline drawn only from the user's good windows.
        val priorShares = savings.prior
            .filter { it.incomeMinor > 0L }
            .map { (((it.incomeMinor - it.expenseMinor).toDouble() / it.incomeMinor) * 100).roundToInt() }
        if (priorShares.size < SAVINGS_MIN_WINDOWS) return null
        val baselineShare = medianInt(priorShares)
        // A user who usually overspends by this point gets no card rather than "behind the -10% you'd
        // usually have kept", which is both confusing and a miserable thing to read. The baseline is now the
        // honest median; this only decides whether it can be phrased as a share kept.
        //
        // ⭐This check runs BEFORE the ambiguity gate below, and the order is load-bearing. A set like
        // [50, 45, 40, -20, -30, -10] has wide-apart middles, so an ambiguity gate placed first would return
        // here and the round-1 regression guard for the negative baseline would stop executing its own
        // branch — a guard that guards nothing, which is the exact trap Phase B kept falling into.
        if (baselineShare <= 0) return null
        // ⭐⭐The gate that replaced a min-to-max spread bar of 40 points, which review round 3 showed did
        // not do its job in either direction.
        //
        // What can actually mislead is not a wide range, it is an AMBIGUOUS MIDDLE. `medianInt` takes the
        // lower of the two middle values so the baseline stays a share some cycle genuinely reached; when
        // those two middles are far apart, that choice — not the data — decides the headline word. On
        // [15, 17, 19, 40, 42, 44] the baseline is 19%, the true centre is ~30%, and a cycle at 30% reads
        // "ahead of usual" when it is behind. That set spans 29 points and sailed under a 40-point bar.
        //
        // Meanwhile the old bar was too expensive for lumpy but consistent savers: [20, 35, 38, 41, 45, 70]
        // spans 50 points and was silenced, though its middles are three points apart and the median is a
        // perfectly honest 38%. Gating on the two middles fixes both: it fires exactly when the lower-middle
        // choice is arbitrary, and an odd-length list has one true middle so it can never fire there.
        val sorted = priorShares.sorted()
        if (sorted[sorted.size / 2] - sorted[(sorted.size - 1) / 2] > SAVINGS_MIN_POINTS) return null
        if (abs(share - baselineShare) < SAVINGS_MIN_POINTS) return null
        return InsightFinding(
            kind = InsightKind.SAVINGS_RATE,
            amountMinor = kept,
            sharePercent = share,
            baselineSharePercent = baselineShare,
            days = input.daysElapsed,
            // Ranking only — never shown, never sent. The rupee gap between what was kept and what usually
            // would have been by now.
            materialityMinor = abs(kept - income * baselineShare / 100),
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

    /**
     * [median] for percentages, with the same lower-middle rule and for the same reason.
     *
     * A separate function rather than a conversion through [median] so the rule is stated once per type and
     * cannot drift: "the share you'd usually have kept by this point" must be a share some cycle genuinely
     * reached, not the average of two that straddle it.
     */
    internal fun medianInt(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 2]
    }
}
