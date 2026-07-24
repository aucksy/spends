package com.spends.app.data.ai

import com.spends.app.domain.model.TxnKind
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** One merchant to classify. NOTE: no amount, no date, no SMS body — only the merchant string leaves the phone. */
data class AiCatItem(val id: Long, val merchant: String, val kind: TxnKind)

/** A merchant the user has ALREADY categorized (name + category), sent as reference so AI can recognise a
 *  spelling variant of it and REPRODUCE that category (never overriding — the app's own matcher runs first). */
data class LearnedMerchant(val merchant: String, val category: String)

/** The model's suggestion for one item: a category NAME from the caller's list + an optional cleaned display
 *  name. [fromKnown] = the model recognised this as a variant of one of the user's already-categorized merchants
 *  (so the chip can read "Same as before" rather than a fresh "Suggested"). */
data class AiCatSuggestion(val categoryName: String, val cleanName: String?, val fromKnown: Boolean = false)

/**
 * Feature #1 — smart category *suggestions* (docs/AI-RESEARCH.md §2.3). One BATCHED Groq call classifies a set of
 * merchant strings against the user's own category-name list, AND — given the user's learned merchant→category
 * shortcuts — recognises spelling variants of merchants they've tagged before and reproduces that category
 * (enhancing the learned memory, never overriding it: the deterministic learned matcher already runs first, so
 * AI only ever sees merchants it couldn't place). It returns category NAMES only (the ViewModel maps them to real
 * ids); anything off-list or unparseable is dropped. Money-safe: it never sees/emits an amount or date, and its
 * output only pre-fills a *suggestion* on a review row the user still confirms. Fail-closed: any failure → empty map.
 */
@Singleton
class AiCategorizer @Inject constructor(
    private val groq: GroqClient,
) {
    /** Batch-classify [items] against [categoryNames], using [learned] merchant shortcuts to reproduce prior
     *  choices for spelling variants. Returns id → suggestion for confidently-placed rows only. */
    suspend fun suggest(
        items: List<AiCatItem>,
        categoryNames: List<String>,
        learned: List<LearnedMerchant> = emptyList(),
    ): Map<Long, AiCatSuggestion> {
        if (items.isEmpty() || categoryNames.isEmpty()) return emptyMap()
        val user = buildUserPayload(items, categoryNames, learned)
        val result = groq.chat(
            model = GroqClient.MODEL_CATEGORIZE,
            system = SYSTEM,
            user = user,
            jsonObject = true,
            temperature = 0.0,
        )
        val content = (result as? GroqResult.Ok)?.content ?: return emptyMap()
        return parseResponse(content, items, categoryNames)
    }

    companion object {
        const val SYSTEM = "You label Indian personal-finance transactions. You are given: (1) categories = the " +
            "fixed list of allowed category names; (2) known = merchants the user has ALREADY categorized, each " +
            "as {merchant, category}; (3) items = new merchant strings to label. For EACH item, FIRST decide if " +
            "it is the SAME merchant as one in known — allow for spelling differences, payment-gateway prefixes " +
            "(RAZ*, PAYU*, UPI/), branch or city names, order numbers, and extra or abbreviated words. If it " +
            "matches a known merchant, use THAT merchant's category and set fromKnown to true. Otherwise choose " +
            "the single best category from categories and set fromKnown to false. If none clearly fits, use null. " +
            "Never invent a category name that is not in categories. Optionally add a short human-readable " +
            "cleanName for the merchant (e.g. \"RAZ*FURLENCO BLR\" -> \"Furlenco\"), or null. Respond with ONLY a " +
            "JSON object of the form {\"results\":[{\"id\":<number>,\"category\":<name-or-null>,\"cleanName\":" +
            "<string-or-null>,\"fromKnown\":<true|false>}]}."

        /** The aggregate/merchant-only payload sent to Groq. Pure + testable — asserts nothing sensitive leaks. */
        internal fun buildUserPayload(
            items: List<AiCatItem>,
            categoryNames: List<String>,
            learned: List<LearnedMerchant> = emptyList(),
        ): String {
            val cats = JSONArray()
            categoryNames.forEach { cats.put(it) }
            val known = JSONArray()
            learned.forEach { known.put(JSONObject().put("merchant", it.merchant).put("category", it.category)) }
            val arr = JSONArray()
            items.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("merchant", it.merchant)
                        .put("kind", it.kind.name),
                )
            }
            val root = JSONObject().put("categories", cats).put("items", arr)
            if (learned.isNotEmpty()) root.put("known", known)
            return root.toString()
        }

        /**
         * Parse the model's JSON. Pure + testable + defensive: accepts only ids that were actually sent, maps the
         * returned category to the CANONICAL spelling from [categoryNames] (case-insensitive), and drops anything
         * off-list / null / malformed. A parse exception → empty map (fail-closed).
         */
        internal fun parseResponse(
            content: String,
            items: List<AiCatItem>,
            categoryNames: List<String>,
        ): Map<Long, AiCatSuggestion> = runCatching {
            val sentIds = items.mapTo(HashSet()) { it.id }
            val canonical = categoryNames.associateBy { it.trim().lowercase() }
            val root = JSONObject(content)
            // JSON mode returns an object; be liberal about where the array is.
            val results = root.optJSONArray("results")
                ?: root.optJSONArray("items")
                ?: root.optJSONArray("data")
                ?: JSONArray()
            val out = LinkedHashMap<Long, AiCatSuggestion>()
            for (i in 0 until results.length()) {
                val o = results.optJSONObject(i) ?: continue
                val id = when {
                    o.has("id") && !o.isNull("id") -> o.optLong("id", Long.MIN_VALUE)
                        .takeIf { it != Long.MIN_VALUE } ?: o.optString("id").toLongOrNull()
                    else -> null
                } ?: continue
                if (id !in sentIds) continue // never trust a hallucinated id
                val rawCat = o.optString("category").takeIf { it.isNotBlank() && !it.equals("null", true) } ?: continue
                val name = canonical[rawCat.trim().lowercase()] ?: continue // off-list → drop
                val clean = o.optString("cleanName")
                    .takeIf { it.isNotBlank() && !it.equals("null", true) }
                val fromKnown = o.optBoolean("fromKnown", false)
                out[id] = AiCatSuggestion(categoryName = name, cleanName = clean, fromKnown = fromKnown)
            }
            out
        }.getOrDefault(emptyMap())
    }
}
