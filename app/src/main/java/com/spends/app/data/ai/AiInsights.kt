package com.spends.app.data.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature #2 — plain-English cycle insights (docs/AI-RESEARCH.md §2.3). The payload is **aggregates only**:
 * category totals + income/expense totals for this cycle and (optionally) the previous one. NO individual rows,
 * NO dates, NO merchant names, NO balances-over-time, NO account/card numbers. Read-only: there is no write path,
 * so it structurally can't touch money. Fail-closed: any failure → null → the card simply doesn't render.
 */
data class InsightPayload(
    val cycleLabel: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val byCategory: List<CategoryTotal>,
    val lastCycleExpenseMinor: Long? = null,
    val lastCycleByCategory: List<CategoryTotal>? = null,
) {
    data class CategoryTotal(val name: String, val amountMinor: Long)

    /** A stable content fingerprint so the cache re-summarises only when the numbers actually change. */
    val fingerprint: String
        get() = buildString {
            append(cycleLabel).append('|').append(incomeMinor).append('|').append(expenseMinor)
            byCategory.sortedByDescending { it.amountMinor }.forEach { append('|').append(it.name).append(':').append(it.amountMinor) }
            append("|last:").append(lastCycleExpenseMinor ?: -1)
            lastCycleByCategory?.sortedByDescending { it.amountMinor }?.forEach { append('|').append(it.name).append(':').append(it.amountMinor) }
        }
}

@Singleton
class AiInsights @Inject constructor(
    private val groq: GroqClient,
) {
    // Keyed by payload fingerprint → opening Analytics repeatedly doesn't re-call or re-spend tokens.
    private val cache = ConcurrentHashMap<String, String>()

    /** Summarise [payload] into 2–4 plain-English sentences, or null (fail-closed). [forceRefresh] bypasses cache. */
    suspend fun summarize(payload: InsightPayload, forceRefresh: Boolean = false): String? {
        // Nothing meaningful to say for an empty cycle.
        if (payload.expenseMinor == 0L && payload.incomeMinor == 0L) return null
        val key = payload.fingerprint
        if (!forceRefresh) cache[key]?.let { return it }
        val result = groq.chat(
            model = GroqClient.MODEL_INSIGHTS,
            system = SYSTEM,
            user = buildUserPayload(payload),
            jsonObject = true,
            temperature = 0.4,
            maxTokens = 220,
        )
        val content = (result as? GroqResult.Ok)?.content ?: return null
        val summary = parseSummary(content) ?: return null
        if (cache.size >= MAX_CACHE_ENTRIES) cache.keys.firstOrNull()?.let { cache.remove(it) }
        cache[key] = summary
        return summary
    }

    companion object {
        const val SYSTEM = "You are a calm, encouraging personal-money assistant for an Indian user. In 2 to 4 " +
            "short sentences of plain English (no jargon), describe this spending cycle: the overall amount, the " +
            "biggest categories, and any notable change versus last cycle if provided. Use the Rupee sign ₹ for " +
            "amounts. Never shame the user. Never give financial advice, warnings, or predictions. Respond with " +
            "ONLY a JSON object of the form {\"summary\":\"...\"}."

        private fun rupees(minor: Long): Double = minor / 100.0

        private fun categoriesJson(list: List<InsightPayload.CategoryTotal>): JSONArray {
            val arr = JSONArray()
            list.sortedByDescending { it.amountMinor }.take(TOP_CATEGORIES).forEach {
                arr.put(JSONObject().put("name", it.name).put("total", rupees(it.amountMinor)))
            }
            return arr
        }

        /** The aggregates-only user payload. Pure + testable — a test asserts it carries no sensitive field. */
        internal fun buildUserPayload(p: InsightPayload): String {
            val obj = JSONObject()
                .put("cycleLabel", p.cycleLabel)
                .put("income", rupees(p.incomeMinor))
                .put("expense", rupees(p.expenseMinor))
                .put("byCategory", categoriesJson(p.byCategory))
            if (p.lastCycleExpenseMinor != null) {
                val last = JSONObject().put("expense", rupees(p.lastCycleExpenseMinor))
                p.lastCycleByCategory?.let { last.put("byCategory", categoriesJson(it)) }
                obj.put("lastCycle", last)
            }
            return obj.toString()
        }

        /** Pull the summary text out of the model's JSON; null on anything malformed (fail-closed). */
        internal fun parseSummary(content: String): String? = runCatching {
            val root = JSONObject(content)
            (root.optString("summary").takeIf { it.isNotBlank() }
                ?: root.optString("text").takeIf { it.isNotBlank() }
                ?: root.optString("insight").takeIf { it.isNotBlank() })
                ?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }

        private const val TOP_CATEGORIES = 8

        // A generous per-process bound so the fingerprint cache can't grow without limit over a very long
        // session (cleared entirely on process death anyway). A changed current cycle re-summarises regardless.
        private const val MAX_CACHE_ENTRIES = 64
    }
}
