package com.spends.app.data.ai.insights

import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.MerchantKeys
import com.spends.app.data.db.entity.CategorySliceRow
import com.spends.app.data.repo.ExpenseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles the insights carousel: load history → [InsightEngine] → [InsightNarrator] → cards.
 *
 * ## Two queries, not thirteen
 * The naive shape is one read per historical window. Instead this pulls **one** span covering every prior
 * window and buckets it in memory, so the whole carousel costs two one-shot reads however many windows the
 * baseline spans.
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
    private val narrator: InsightNarrator,
) {

    /** How many prior equal-length windows form the "usual" baseline. */
    private val historyWindows = 6

    // Keyed by content fingerprint, so re-opening Analytics doesn't re-call or re-spend tokens.
    private val cache = ConcurrentHashMap<String, List<InsightCard>>()

    suspend fun cards(
        fingerprint: String,
        cycleLabel: String,
        windowStartMillis: Long,
        windowEndExclusiveMillis: Long,
        currentByCategory: Map<String, Long>,
        expenseMinor: Long,
        forceRefresh: Boolean = false,
    ): List<InsightCard> {
        if (!forceRefresh) cache[fingerprint]?.let { return it }

        val span = windowEndExclusiveMillis - windowStartMillis
        if (span <= 0L) return emptyList()

        val now = DateUtils.nowMillis()
        // A cycle that hasn't started yet (reachable: card spends rolled forward make the next cycle
        // navigable). There is no "so far" to measure, and every baseline would collapse to a sliver,
        // reporting 30x multiples against figures nobody spent.
        if (now < windowStartMillis) return emptyList()
        val elapsedMillis = (now - windowStartMillis).coerceAtMost(span)

        // Row mapping, merchant normalisation and the detectors all run off the main thread — this can span
        // several years of allocation rows for a heavy user on a long range.
        val findings = withContext(Dispatchers.Default) {
            val currentRows = expenseRepository.categorySlicesOnce(windowStartMillis, windowEndExclusiveMillis)
            val historyRows = expenseRepository.categorySlicesOnce(windowStartMillis - span * historyWindows, windowStartMillis)

            val buckets = List(historyWindows) { mutableMapOf<String, Long>() }
            historyRows.forEach { row ->
                val index = InsightWindows.bucketIndex(windowStartMillis, span, elapsedMillis, row.occurredAt, historyWindows)
                if (index != null) buckets[index][row.name] = (buckets[index][row.name] ?: 0L) + row.amountMinor
            }

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
            cache[fingerprint] = cards
        }
        return cards
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

    private companion object {
        const val MAX_CACHE_ENTRIES = 32
    }
}
