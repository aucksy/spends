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
internal data class NarratedCard(val kind: String?, val title: String, val body: String)

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
 * The payload carries **category names and rupee figures only** — the same class of aggregate the existing
 * insights card already sends. No merchants, no dates, no individual transactions, no account or card
 * numbers, no balances. Merchant names are used by the duplicate detector on the device and stop there.
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

        return findings.mapIndexed { index, finding ->
            // Pairing by position alone would let a reordered or merged reply attach one finding's heading to
            // another's numbers — a wrong figure against the wrong category, silently, which is exactly what
            // computing on the device was meant to rule out. The model echoes each card's `kind`; a mismatch
            // falls back to that finding's own template, which is already correct.
            val card = narrated.getOrNull(index)
                ?.takeIf { it.kind == null || it.kind.equals(finding.kind.name, ignoreCase = true) }
            InsightCard(
                kind = finding.kind,
                title = card?.title?.takeIf { it.isNotBlank() } ?: finding.fallbackTitle(),
                body = card?.body?.takeIf { it.isNotBlank() } ?: finding.fallbackBody(),
            )
        }
    }

    companion object {

        const val SYSTEM = "You are a calm, encouraging personal-money assistant for an Indian user. You will " +
            "be given a JSON array of findings that have ALREADY been calculated from the user's own records. " +
            "For each finding, in the SAME order, write a short card: a heading of at most 5 words, and a body " +
            "of 1 to 2 short sentences in plain English with no jargon. " +
            "Use ONLY the numbers given to you — never calculate, estimate, round differently or invent a " +
            "figure, and never mention a number that is not in the finding. Write amounts with the Rupee sign " +
            "₹. Never shame the user; a finding about spending less is good news. Do not give financial " +
            "advice, warnings or predictions — describe what happened, nothing more. " +
            "Echo each finding's \"kind\" back on its card so the order can be checked. " +
            "Respond with ONLY a JSON object of the form " +
            "{\"cards\":[{\"kind\":\"...\",\"title\":\"...\",\"body\":\"...\"}]}."

        private fun rupees(minor: Long): Double = minor / 100.0

        /** Aggregates only. A test asserts this carries no merchant, date or transaction-level field. */
        internal fun buildUserPayload(cycleLabel: String, findings: List<InsightFinding>): String {
            val arr = JSONArray()
            findings.forEach { f ->
                val o = JSONObject().put("kind", f.kind.name)
                f.category?.let { o.put("category", it) }
                if (f.amountMinor != 0L) o.put("amount", rupees(f.amountMinor))
                if (f.baselineMinor != 0L) o.put("usualAmount", rupees(f.baselineMinor))
                if (f.multiple > 0.0) o.put("timesUsual", Math.round(f.multiple * 10) / 10.0)
                if (f.sharePercent > 0) o.put("sharePercent", f.sharePercent)
                if (f.count > 0) o.put("count", f.count)
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
                        title = it.optString("title").trim(),
                        body = it.optString("body").trim(),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
