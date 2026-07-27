package com.spends.app.data.ai.insights

import com.spends.app.data.ai.GroqClient
import com.spends.app.data.ai.GroqResult
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** One carousel card: a short heading and a sentence or two beneath it. */
data class InsightCard(val kind: InsightKind, val title: String, val body: String)

/** A card as the model returned it, before it is checked against the finding it claims to describe. */
internal data class NarratedCard(val kind: String?, val category: String?, val title: String, val body: String)

/**
 * Turns already-computed [InsightFinding]s into readable cards.
 *
 * ## What this deliberately is not
 * It does **not** decide what is interesting, and it does **not** do arithmetic. [InsightEngine] has already
 * settled both on the device. The model's whole job is wording. That is what makes a claim like "about 3×
 * your usual" safe to put in front of the user: it is a number from their own ledger, not one the model felt
 * would sound right.
 *
 * ## One call, not one per card
 * All findings go in a single request and come back as an array, so a carousel of five costs no more than
 * the single card it replaces.
 *
 * ## Privacy
 * The payload carries **category names, rupee figures and shares** — the same class of aggregate the existing
 * insights card already sends. No merchants, no individual transactions, no account or card numbers, no
 * balances, and **no transaction dates**: the only date-shaped values are the cycle's calendar month name and
 * how far into the cycle it is, both owner-approved and disclosed. Merchant names are used by the duplicate
 * detector on the device and stop there.
 *
 * ## Fail-closed, not fail-blank
 * Any failure — no key, offline, a non-2xx, malformed JSON, a short array — falls back to
 * [InsightFinding.fallbackTitle]/[InsightFinding.fallbackBody] for the affected cards. The finding *is* the
 * insight; losing the model should cost the prose, not the feature.
 */
@Singleton
class InsightNarrator @Inject constructor(
    private val groq: GroqClient,
) {

    suspend fun narrate(cycleLabel: String, findings: List<InsightFinding>): List<InsightCard> {
        if (findings.isEmpty()) return emptyList()
        val narrated = runCatching {
            val result = groq.chat(
                model = GroqClient.MODEL_INSIGHTS,
                system = SYSTEM,
                user = buildUserPayload(cycleLabel, findings),
                jsonObject = true,
                temperature = 0.4,
                maxTokens = 520,
            )
            (result as? GroqResult.Ok)?.content?.let { parseCards(it) }
            // `GroqClient` deliberately rethrows CancellationException so structured concurrency still works;
            // swallowing it here would undo that. Worse, the caller would then cache the template fallbacks
            // as if they were the real answer, so a quick flick between cycles would leave that cycle stuck
            // on plain wording until the user hit refresh.
        }.getOrElse { if (it is CancellationException) throw it else null }.orEmpty()

        return pair(findings, narrated)
    }

    companion object {

        const val SYSTEM = "You are a calm, encouraging personal-money assistant for an Indian user. You will " +
            "be given a JSON array of findings that have ALREADY been calculated from the user's own records. " +
            "For each finding, in the SAME order, write a short card: a heading of at most 5 words, and a body " +
            "of 1 to 2 short sentences in plain English with no jargon. " +
            "Use ONLY the numbers given to you — never calculate, estimate, round differently or invent a " +
            "figure, and never mention a number that is not in the finding. Write amounts with the Rupee sign " +
            "₹. Never shame the user; a finding about spending less is good news. Do not give financial " +
            "advice, warnings or predictions — describe what happened, nothing more. Never project a figure " +
            "forward or say what a total will reach; a finding about pace describes where things stand today. " +
            "Read the field names literally and do not restate a figure as something else: a field containing " +
            "\"ByThisPoint\" was measured to the same day of earlier cycles, NOT a whole cycle; a " +
            "\"PerCycle\" field is a per-cycle figure; \"typicalChargeAmount\" is one typical payment, not a " +
            "monthly total; and a share measured over earlier cycles is a habit, not this cycle's number. " +
            "Echo each finding's \"kind\" and its \"category\" (when it has one) back on its card so the " +
            "order can be checked. Respond with ONLY a JSON object of the form " +
            "{\"cards\":[{\"kind\":\"...\",\"category\":\"...\",\"title\":\"...\",\"body\":\"...\"}]}."

        private fun rupees(minor: Long): Double = minor / 100.0

        /**
         * Attach each reply card to the finding it claims to describe, falling back to that finding's own
         * template whenever it doesn't. Always returns exactly one card per finding, in order.
         *
         * Internal rather than private so it can be tested without a network client: this is the guard that
         * stops one category's heading appearing over another category's numbers, and it shipped once with
         * no coverage at all.
         */
        internal fun pair(findings: List<InsightFinding>, narrated: List<NarratedCard>): List<InsightCard> =
            findings.mapIndexed { index, finding ->
                // All-or-nothing, not field-by-field. Taking a model body under a template heading (or the
                // reverse) mixes two voices, and if the pairing were ever wrong it would put one finding's
                // heading over another's numbers. A card is either the model's or the template's — which is
                // why both checks collapse into ONE nullable value rather than a separate boolean: the
                // boolean form only type-checked because Kotlin 2.0's K2 propagates a null check through a
                // local `val`, so it would have become a hard compile error the day anyone pinned an older
                // language version.
                val usable = narrated.getOrNull(index)
                    ?.takeIf { it.matches(finding) }
                    ?.takeIf { it.title.isNotBlank() && it.body.isNotBlank() }
                InsightCard(
                    kind = finding.kind,
                    title = usable?.title ?: finding.fallbackTitle(),
                    body = usable?.body ?: finding.fallbackBody(),
                )
            }

        /**
         * Whether this reply card really describes [finding].
         *
         * ⭐`kind` alone is not an identifier. A normal carousel carries two to four `UNUSUAL_CATEGORY`
         * findings, so a kind-only check passes for **every** permutation of them — a reply that dropped or
         * reordered a card would silently attach one category's heading to another category's numbers, which
         * is precisely the failure the echo was added to prevent. The category disambiguates them; for the
         * kinds that carry no category (pace, year-on-year, the habit, concentration) at most one can ever
         * be in a carousel, so kind alone genuinely identifies those.
         */
        private fun NarratedCard.matches(finding: InsightFinding): Boolean {
            // No echo at all → positional pairing, as before. This is a guard against a scrambled reply, not
            // a demand that the model cooperate.
            if (kind == null && category == null) return true
            if (kind != null && !kind.equals(finding.kind.name, ignoreCase = true)) return false
            val expected = finding.category ?: return true
            // ⭐A PARTIAL echo must not pass. `kind` is trivial to echo — it is an enum literal sitting in
            // the input — so a model echoing only `kind` would sail through a kind-only check and put
            // Travel's heading on Fuel's numbers. Once a card echoes anything, a finding that HAS a category
            // demands the category too.
            return expected.equals(category, ignoreCase = true)
        }

        /**
         * What each figure on each card is **called**.
         *
         * ⭐This is a `when` over every kind, in one place, on purpose. Four review rounds each found another
         * field whose name over-claimed — `timesUsual` on a ratio of two percentages, `usualAmount` on a
         * single previous cycle, `amount` on a per-cycle average from months the user isn't looking at — and
         * each was fixed individually, and the next round found the next one. The generic `else` branch was
         * the bug: it silently applied "usual" to whatever arrived.
         *
         * The rule this encodes: **the model may only be handed a name that is true of the number.** It is
         * told to use only the figures given, so a name is not a label, it is an assertion the app is making
         * about the user's money. "usual" means a median over six cycles and nothing else; a figure measured
         * to the same day of earlier cycles says so; a per-cycle average says so; a typical *charge* is not a
         * typical *month*.
         */
        private fun putFigures(o: JSONObject, f: InsightFinding) {
            // ⭐Zero is a FIGURE, not a missing value. Suppressing it left a quiet win carrying only
            // `usualByThisPointAmount: 9000` — the sole number handed to a model told to use only the numbers
            // it is given, and from a different period — so it wrote "Travel is at ₹9,000 this cycle, nicely
            // down." The engine knew the answer was ₹0 and dropped it. A kind that uses these fields always
            // sends them; only counts, shares and ratios are omitted when absent.
            fun amount(key: String) { o.put(key, rupees(f.amountMinor)) }
            fun baseline(key: String) { o.put(key, rupees(f.baselineMinor)) }
            fun ratio(key: String) { if (f.multiple > 0.0) o.put(key, Math.round(f.multiple * 10) / 10.0) }
            fun count(key: String) { if (f.count > 0) o.put(key, f.count) }
            fun share(key: String) { if (f.sharePercent > 0) o.put(key, f.sharePercent) }

            when (f.kind) {
                // Both sides are measured to the SAME DAY of earlier cycles, never a whole cycle. Mid-cycle
                // that difference is the whole story: on day 5 the baseline is five days' worth.
                InsightKind.UNUSUAL_CATEGORY, InsightKind.QUIET_WIN -> {
                    amount("amountSoFarThisCycle")
                    baseline("usualByThisPointAmount")
                    ratio("timesUsualByThisPoint")
                }
                InsightKind.PACE -> {
                    amount("amountSoFarThisCycle")
                    baseline("usualByThisPointAmount")
                    ratio("timesUsualByThisPoint")
                    if (f.days > 0) o.put("dayOfCycle", f.days)
                }
                InsightKind.MOVER_UP, InsightKind.MOVER_DOWN -> {
                    amount("amountSoFarThisCycle")
                    baseline("lastCycleByThisPointAmount")
                    ratio("timesLastCycleByThisPoint")
                }
                // "usual" here is a typical single CHARGE, not a typical month — a distinction the old key
                // left entirely to the enum name.
                InsightKind.OUTLIER_CHARGE -> {
                    amount("chargeAmount")
                    baseline("typicalChargeAmount")
                    ratio("timesTypicalCharge")
                }
                // The amount is what EACH charge was, not their total.
                InsightKind.DUPLICATE_CHARGE -> {
                    amount("amountEachCharge")
                    count("chargesCounted")
                }
                // The amount is the top categories' subtotal, not the cycle's total spend.
                InsightKind.CONCENTRATION -> {
                    amount("topCategoriesSubtotal")
                    count("categoriesCounted")
                    share("topCategoriesSharePercent")
                }
                InsightKind.YEAR_ON_YEAR -> {
                    amount("amountThisStretch")
                    baseline("sameStretchLastYearAmount")
                    ratio("timesLastYear")
                    if (f.days > 0) o.put("dayOfCycle", f.days)
                    // Without this the model cannot tell a cycle still running from one browsed back to, and
                    // writes "so far this July" about a July that ended weeks ago.
                    o.put("cycleStillRunning", f.days > 0)
                    f.periodLabel?.let { o.put("month", it) }
                }
                // Neither figure belongs to the viewed cycle: both are per-cycle medians over COMPLETED
                // earlier cycles. Sent as plain `amount` it read as "Dining is at ₹6,300 now" on a screen
                // whose donut showed Dining at ₹0.
                InsightKind.CATEGORY_TREND -> {
                    amount("recentPerCycleAmount")
                    baseline("earlierPerCycleAmount")
                    ratio("timesEarlier")
                    if (f.spanCycles > 0) o.put("cyclesCompared", f.spanCycles)
                }
                // A habit measured over earlier COMPLETE cycles — deliberately no ratio, because dividing a
                // money share by a day share yields a number that is not a multiple of anything spent.
                InsightKind.HABIT_PAYDAY -> {
                    share("paydayWeekShareOverEarlierCyclesPercent")
                    if (f.dayShare > 0) o.put("shareOfCycleDaysPercent", f.dayShare)
                    count("cyclesMeasured")
                }
                // Never emitted by the engine; the summary card's text comes from AiInsights.
                InsightKind.CYCLE_SUMMARY -> Unit
            }
        }

        /** Aggregates only. A test asserts this carries no merchant, transaction date or row-level field. */
        internal fun buildUserPayload(cycleLabel: String, findings: List<InsightFinding>): String {
            val arr = JSONArray()
            findings.forEach { f ->
                val o = JSONObject().put("kind", f.kind.name)
                f.category?.let { o.put("category", it) }
                putFigures(o, f)
                arr.put(o)
            }
            return JSONObject().put("cycleLabel", cycleLabel).put("findings", arr).toString()
        }

        /**
         * Title/body pairs in order; empty on anything malformed (fail-closed to the templates).
         *
         * Note the explicit return type: it is what makes the `return emptyList()` inside the lambda legal.
         * Dropping it in the name of brevity turns this into a compile error.
         */
        internal fun parseCards(content: String): List<NarratedCard> = runCatching {
            val root = JSONObject(content)
            val arr = root.optJSONArray("cards") ?: root.optJSONArray("insights") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    NarratedCard(
                        kind = it.optString("kind").trim().takeIf { k -> k.isNotEmpty() },
                        category = it.optString("category").trim().takeIf { c -> c.isNotEmpty() },
                        title = it.optString("title").trim(),
                        body = it.optString("body").trim(),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
