package com.spends.app.data.ai.insights

import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.MerchantKeys
import com.spends.app.data.db.entity.CategorySliceRow
import com.spends.app.data.db.entity.RecurringRuleEntity
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.RecurringRepository
import com.spends.app.domain.model.RecurrenceFreq
import com.spends.app.domain.model.TxnKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles the insights carousel: load history → [InsightEngine] → [InsightNarrator] → cards.
 *
 * ## A fixed handful of queries, not one per window
 * The naive shape is one read per historical window. Instead this pulls **one** span covering every prior
 * window and buckets it in memory, so the whole carousel costs a fixed number of one-shot reads however many
 * windows the baseline spans: current slices, history slices, history income (Phase C), the recurring rules,
 * and — only when its gates pass — the year-ago window.
 *
 * None of them run with the AI helper's master switch off, because nothing in this class is reachable from
 * outside that gate. That is what keeps G2 ("AI off = today's app, byte for byte") true at the query level
 * and not merely at the UI level.
 *
 * ## Comparing a part-finished cycle honestly
 * History is day-aligned by [InsightWindows]: the first N days of this cycle are compared against the first
 * N days of each previous one. See that class for why scaling old totals instead would put invented figures
 * in front of the user.
 *
 * ## Where the current cycle's figures come from
 * The caller passes `currentByCategory` from the already-reconciled on-screen state, so the cards always
 * agree with the charts above them. History is read from raw date windows. For the card-billing-aware Smart
 * Cycle those two are computed slightly differently (the on-screen one buckets a card purchase into the
 * cycle its statement bills), so a historical comparison there is a close approximation. Current-cycle
 * figures are always exact. Single-Card mode is excluded entirely by the caller — there the two datasets
 * genuinely disagree rather than approximate, because these queries carry no instrument filter.
 */
@Singleton
class InsightsProvider @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val recurringRepository: RecurringRepository,
    private val narrator: InsightNarrator,
) {

    // Keyed by content fingerprint, so re-opening Analytics doesn't re-call or re-spend tokens.
    private val cache = ConcurrentHashMap<String, List<InsightCard>>()

    /**
     * @param cycleBoundaries the **real** starts of this cycle and the [HISTORY_CYCLES] before it, descending,
     *   newest first — `cycleBoundaries[0]` must equal `windowStartMillis`. Produced by the same
     *   `CycleUtils`/`PeriodResolver` machinery that drew the window on screen; see [InsightWindows] for why
     *   subtracting a fixed span instead silently corrupts every baseline.
     * @param paydayAligned true only when this cycle genuinely *begins* on the user's salary day, so its
     *   first seven days really are the week after being paid. False suppresses the habit card entirely
     *   rather than describing the opening week of an arbitrary window as a payday habit.
     * @param wholeCycleComparable false for the card-billing-aware Smart Cycle, where the on-screen totals
     *   and the history queries bucket a card purchase differently — see `InsightInput`.
     * @param incomeMinor this cycle's income from the same on-screen state as `expenseMinor`, used by the
     *   savings-rate card (Phase C). The commitments card deliberately does NOT use it — its denominator is
     *   the median of prior COMPLETE cycles, which is what frees it from every timing gate.
     */
    suspend fun cards(
        fingerprint: String,
        cycleLabel: String,
        windowStartMillis: Long,
        windowEndExclusiveMillis: Long,
        cycleBoundaries: List<Long>,
        currentByCategory: Map<String, Long>,
        expenseMinor: Long,
        incomeMinor: Long,
        paydayAligned: Boolean,
        wholeCycleComparable: Boolean,
        forceRefresh: Boolean = false,
    ): List<InsightCard> {
        val span = windowEndExclusiveMillis - windowStartMillis
        if (span <= 0L) return emptyList()

        // Fail closed on boundaries that don't describe this cycle. Every baseline in here is built from
        // them, so a caller whose window came from a path this doesn't understand must get no cards rather
        // than cards measured against the wrong cycles.
        if (cycleBoundaries.size < 2 || cycleBoundaries.first() != windowStartMillis) return emptyList()
        if (cycleBoundaries.zipWithNext().any { (newer, older) -> older >= newer }) return emptyList()

        val now = DateUtils.nowMillis()
        // A cycle that hasn't started yet (reachable: card spends rolled forward make the next cycle
        // navigable). There is no "so far" to measure, and every baseline would collapse to a sliver,
        // reporting 30x multiples against figures nobody spent.
        if (now < windowStartMillis) return emptyList()
        val elapsedMillis = (now - windowStartMillis).coerceAtMost(span)

        val zone = DateUtils.ZONE
        val cycleDays = InsightCalendar.cycleDays(windowStartMillis, windowEndExclusiveMillis, zone)
        val cycleComplete = now >= windowEndExclusiveMillis
        // Clamped, because a *completed* cycle is reachable by stepping back and `now` is then far past its
        // end — uncapped this would read "day 412 of the cycle". The clamp alone can't distinguish the last
        // day of a live cycle from a finished one, which is what `cycleComplete` above is for.
        val daysElapsed = InsightCalendar.daysElapsed(windowStartMillis, now, zone).coerceAtMost(cycleDays)
        val oldestBoundary = cycleBoundaries.last()

        // ⭐Read the rules BEFORE the cache lookup, and fold them into the key — but AFTER the cheap guards
        // above, so a window that produces nothing never pays for the query.
        //
        // The caller's fingerprint is built from on-screen totals, which do not move when a recurring rule
        // is added, edited or switched off — so a cache keyed on it alone would keep showing yesterday's
        // commitments figure after the user changed exactly the thing the card is about. The cost is one
        // read of a table holding a handful of rows, inside the AI gate like everything else here, so G2 is
        // unaffected.
        val commitments = loadCommitments(now)
        val key = "$fingerprint|${commitments?.monthlyMinor ?: 0}|${commitments?.ruleCount ?: 0}"
        if (!forceRefresh) cache[key]?.let { return it }

        // Row mapping, merchant normalisation and the detectors all run off the main thread — this can span
        // several years of allocation rows for a heavy user on a long range.
        val findings = withContext(Dispatchers.Default) {
            val currentRows = expenseRepository.categorySlicesOnce(windowStartMillis, windowEndExclusiveMillis)
            val historyRows = expenseRepository.categorySlicesOnce(oldestBoundary, windowStartMillis)
            // ⚠⭐**Unconditional, and it must stay that way.** This was briefly skipped when
            // `!wholeCycleComparable`, on the reasoning that both cards it feeds bail out on that flag. That
            // reasoning expired the moment `commitments()` stopped comparing against on-screen state and
            // dropped the flag — and the skip then silently emptied `priorFullCycleIncomeMinor`, so **every
            // Smart Cycle user lost the commitments card entirely**, with the engine tests still green
            // because none of them runs through this class. An optimisation justified by a gate in *another
            // file* is one edit away from being a silent feature deletion. The query is cheap; it runs.
            val incomeRows = expenseRepository.incomeChargesOnce(oldestBoundary, windowStartMillis)

            val windows = cycleBoundaries.size - 1
            val buckets = List(windows) { mutableMapOf<String, Long>() }
            // Separate pass, deliberately: these are COMPLETE cycles, where the day-aligned buckets above are
            // truncated to the point this cycle has reached. A trend measured on truncated cycles would be
            // measuring the truncation.
            val fullBuckets = List(windows) { mutableMapOf<String, Long>() }
            historyRows.forEach { row ->
                val index = InsightWindows.bucketIndexIn(cycleBoundaries, row.occurredAt, elapsedMillis)
                if (index != null) buckets[index][row.name] = (buckets[index][row.name] ?: 0L) + row.amountMinor
                val full = InsightWindows.fullBucketIndexIn(cycleBoundaries, row.occurredAt)
                if (full != null) fullBuckets[full][row.name] = (fullBuckets[full][row.name] ?: 0L) + row.amountMinor
            }

            // Income, day-aligned exactly like the expense buckets above, so each prior cycle's kept-share
            // is measured over the same stretch this one has reached.
            val incomeBuckets = LongArray(windows)
            // Whole-cycle income too, in the same pass. The commitments card needs "what a full cycle
            // usually brings in" rather than "what had arrived by this point", because the day-aligned
            // figure is near zero for anyone paid late in the month — which is precisely the user whose
            // commitments share would otherwise be computed against a partial denominator.
            val fullIncomeBuckets = LongArray(windows)
            incomeRows.forEach { row ->
                val index = InsightWindows.bucketIndexIn(cycleBoundaries, row.occurredAt, elapsedMillis)
                if (index != null) incomeBuckets[index] += row.amountMinor
                val full = InsightWindows.fullBucketIndexIn(cycleBoundaries, row.occurredAt)
                if (full != null) fullIncomeBuckets[full] += row.amountMinor
            }
            // ⭐Built from the UNFILTERED buckets, and paired inside one type rather than by index against
            // `history` below — which IS filtered for empties. Pairing an unfiltered income list against a
            // filtered expense list would line one cycle's income up against another cycle's spending.
            //
            // Both sides use the CATEGORISED total, not the headline expense figure: the prior windows can
            // only be built from categorised rows, so comparing them against a current total that also
            // included uncategorised spend would put a permanent thumb on the scale. Same reasoning as pace.
            val savings = SavingsWindows(
                current = SavingsWindow(
                    incomeMinor = incomeMinor,
                    expenseMinor = currentByCategory.values.sum(),
                ),
                prior = List(windows) { index ->
                    SavingsWindow(
                        incomeMinor = incomeBuckets[index],
                        expenseMinor = buckets[index].values.sum(),
                    )
                },
            )

            InsightEngine.detect(
                InsightInput(
                    expenseMinor = expenseMinor,
                    current = currentByCategory,
                    previous = buckets.firstOrNull()?.takeIf { it.isNotEmpty() },
                    history = buckets.filter { it.isNotEmpty() },
                    currentSlices = currentRows.map { it.toSlice() },
                    // Only the largest current charge's category is ever compared against history, so
                    // normalising every historical row's merchant would be wasted work.
                    historySlices = historyRows.map { it.toSliceWithoutMerchant() },
                    daysElapsed = daysElapsed,
                    cycleDays = cycleDays,
                    // NOT filtered for empties, unlike `history`: the trend detector reads this positionally,
                    // so a cycle with no spending has to stay in place as a gap rather than close it up.
                    fullHistory = fullBuckets,
                    // Skipped entirely when the card it feeds cannot fire — otherwise every Smart Cycle build
                    // pays a MIN() plus a full year-ago range scan for a value the engine throws away.
                    yearAgo = if (wholeCycleComparable) loadYearAgo(windowStartMillis, elapsedMillis, zone) else null,
                    habits = if (paydayAligned) {
                        InsightCalendar.habitBuckets(
                            boundaries = cycleBoundaries,
                            charges = historyRows.map { DatedCharge(it.occurredAt, it.amountMinor) },
                            zone = zone,
                        )
                    } else {
                        null
                    },
                    cycleComplete = cycleComplete,
                    wholeCycleComparable = wholeCycleComparable,
                    commitments = commitments,
                    savings = savings,
                    priorFullCycleIncomeMinor = fullIncomeBuckets.toList(),
                ),
            )
        }
        if (findings.isEmpty()) return emptyList()

        val cards = narrator.narrate(cycleLabel, findings)
        // Only cache a genuinely narrated set. If every card came back as its own template, the model call
        // failed (offline, a bad key, a rate limit) — caching that would freeze the plain wording in place
        // for this cycle even once the network is back.
        val narrated = cards.zip(findings).any { (card, finding) -> card.body != finding.fallbackBody() }
        if (narrated) {
            if (cache.size >= MAX_CACHE_ENTRIES) cache.keys.firstOrNull()?.let { cache.remove(it) }
            cache[key] = cards
        }
        return cards
    }

    /**
     * The user's standing monthly recurring load, or null when nothing qualifies.
     *
     * ⭐**Monthly, `intervalCount == 1`, active, EXPENSE — and nothing else is normalised in.** A weekly
     * rule is not multiplied by 4.33 and a yearly premium is not divided by 12, because both produce a
     * figure the user has never paid, and this engine's first rule is that it never reports one.
     *
     * The honest cost of that choice: someone whose insurance is a yearly rule sees it excluded. That is an
     * understatement of a clearly-named thing, which is survivable; an invented monthly figure would not be.
     *
     * The card says "the monthly recurring payments **already running**" — not "you've set up" — because
     * rules that begin later are excluded below, and that is exactly what this sums.
     */
    private suspend fun loadCommitments(now: Long): CommitmentTotals? =
        monthlyCommitments(recurringRepository.allOnce(), now)

    /**
     * The same elapsed stretch of the cycle, one year earlier — or null, which is the common answer.
     *
     * Three gates, because "the year-ago window is empty" has two very different causes. If the app simply
     * wasn't in use then, a card reading *"₹24,100 more than last July"* is not unflattering, it is false.
     *
     * 1. **Spending** must have been recorded before that window
     *    ([ExpenseRepository.earliestExpenseOccurredAtOnce]) — earliest *expense*, not earliest row, since an
     *    old income entry or opening balance says nothing about whether purchases were being logged.
     * 2. The window must contain charges on at least [MIN_YEAR_AGO_DAYS] separate days. One stray old
     *    transaction otherwise defeats gate 1 permanently, and the comparison silently becomes
     *    "this month's spending against last year's *logging*".
     * 3. The engine applies its own rupee floor to whatever survives.
     *
     * Cost: the `MIN(occurredAt)` aggregate IS gate 1, so it runs on every carousel build that reaches it — Smart Cycle skips `loadYearAgo` entirely, so neither query runs there; the year-ago
     * window is only read when it passes. So a user in their first year pays one cheap indexed aggregate and
     * never the range scan. With the AI helper off neither runs — nothing in this class is reachable from
     * outside the gate.
     */
    private suspend fun loadYearAgo(
        windowStartMillis: Long,
        elapsedMillis: Long,
        zone: ZoneId,
    ): YearAgoWindow? {
        val yearAgoStart = InsightCalendar.oneYearEarlier(windowStartMillis, zone)
        // BOTH ends shift by a calendar year. Adding `elapsedMillis` to the shifted start instead would leave
        // the end a fixed span while the start moved by the calendar, so across a leap year the "same stretch"
        // silently gained a day — and for a salary-day-1 user that extra day is the 1st, when rent lands.
        // Exactly the drift `minusYears(1)` was introduced to prevent, one line further down.
        val yearAgoEnd = InsightCalendar.oneYearEarlier(windowStartMillis + elapsedMillis, zone)
        val earliest = expenseRepository.earliestExpenseOccurredAtOnce() ?: return null
        if (earliest > yearAgoStart) return null
        val rows = expenseRepository.categorySlicesOnce(yearAgoStart, yearAgoEnd)
        val daysWithSpend = rows.map { DateUtils.dedupeEpochDay(it.occurredAt) }.distinct().size
        if (daysWithSpend < MIN_YEAR_AGO_DAYS) return null
        return YearAgoWindow(
            expenseMinor = rows.sumOf { it.amountMinor },
            monthLabel = InsightCalendar.cycleMonthLabel(windowStartMillis, elapsedMillis, zone),
        )
    }

    private fun CategorySliceRow.toSlice() = ChargeSlice(
        expenseId = expenseId,
        category = name,
        amountMinor = amountMinor,
        dayEpoch = DateUtils.dedupeEpochDay(occurredAt),
        // Normalised so "SWIGGY" and "Swiggy*Order" count as the same merchant for duplicate detection.
        // Stays on the device: the finding carries only the category.
        merchantKey = MerchantKeys.normalize(merchantRaw),
    )

    /** History is only used for per-category charge sizes, never for duplicate detection. */
    private fun CategorySliceRow.toSliceWithoutMerchant() = ChargeSlice(
        expenseId = expenseId,
        category = name,
        amountMinor = amountMinor,
        dayEpoch = DateUtils.dedupeEpochDay(occurredAt),
        merchantKey = null,
    )

    companion object {
        /**
         * How many prior cycles form the "usual" baseline. Public because the caller builds the matching
         * cycle boundaries — one shared constant so the two can never disagree about how far back to look.
         */
        const val HISTORY_CYCLES = 6

        private const val MAX_CACHE_ENTRIES = 32

        /**
         * Separate days a year-ago window must carry charges on before it counts as "the app was in use".
         * Five, so that a handful of stray back-dated entries cannot stand in for a month of real records.
         */
        private const val MIN_YEAR_AGO_DAYS = 5

        /**
         * Which recurring rules count towards the commitments card, and — more importantly — which do not.
         *
         * ⭐**Nothing is normalised.** A weekly rule is not multiplied by 4.33; a yearly premium is not
         * divided by 12. Both would produce a figure the user has never paid, and the engine's first rule is
         * that it never reports one. Only active, EXPENSE, plain-monthly rules are summed, at face value.
         *
         * The honest cost: someone whose insurance is a yearly rule sees it excluded, so the total
         * understates their real fixed load. That is survivable because the card names exactly what it
         * counts — "the monthly recurring payments **already running**" — whereas an invented monthly figure
         * would be the app asserting something false about their money. The wording matters twice: rules
         * that have not started yet are excluded here too, so "you've set up" would over-claim.
         *
         * Internal and pure so that exclusion is a unit test rather than a comment; it is the one rule here
         * that a future "improvement" would most plausibly undo.
         */
        internal fun monthlyCommitments(rules: List<RecurringRuleEntity>, now: Long): CommitmentTotals? {
            val monthly = rules.filter {
                it.active &&
                    it.kind == TxnKind.EXPENSE &&
                    it.frequency == RecurrenceFreq.MONTHLY &&
                    it.intervalCount == 1 &&
                    // ⭐A rule the user set up today to start next month is not money that is spoken for
                    // this cycle. Without this, adding a future rule immediately inflates the card's total
                    // and the share it quotes — a commitment the user has not begun paying.
                    //
                    // ⭐Compared as calendar DAYS, not instants. The date picker anchors a chosen day at
                    // local NOON (`DateUtils.epochMillisFor(date, 12, 0)`), so a rule created at 09:30 to
                    // start "today" has a startDate three hours in the future and an instant comparison
                    // would drop it — the card would omit a commitment the user set up an hour earlier and
                    // then include it after lunch, from the same data.
                    !DateUtils.toLocalDate(it.startDate).isAfter(DateUtils.toLocalDate(now))
            }
            if (monthly.isEmpty()) return null
            return CommitmentTotals(monthlyMinor = monthly.sumOf { it.amountMinor }, ruleCount = monthly.size)
        }
    }
}
