package com.spends.app.data.ai

import com.spends.app.domain.model.TxnKind
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** One merchant to classify. NOTE: no amount, no date, no SMS body — only the merchant string leaves the phone. */
data class AiCatItem(val id: Long, val merchant: String, val kind: TxnKind)

/** The model's suggestion for one item: a category NAME from the caller's list + an optional cleaned display name. */
data class AiCatSuggestion(val categoryName: String, val cleanName: String?)

/**
 * Feature #1 — smart category *suggestions* (docs/AI-RESEARCH.md §2.3). One BATCHED Groq call classifies a set of
 * merchant strings against the user's own category-name list. It returns category NAMES only (the ViewModel maps
 * them to real category ids); anything off-list or unparseable is dropped. Money-safe by construction: it never
 * sees or emits an amount/date, and its output only ever pre-fills a *suggestion* on a review row the user still
 * confirms. Fail-closed: any failure → an empty map → the ✨ chip simply doesn't appear.
 */
@Singleton
class AiCategorizer @Inject constructor(
    private val groq: GroqClient,
) {
    /** Batch-classify [items] against [categoryNames]. Returns id → suggestion for confidently-placed rows only. */
    suspend fun suggest(items: List<AiCatItem>, categoryNames: List<String>): Map<Long, AiCatSuggestion> {
        if (items.isEmpty() || categoryNames.isEmpty()) return emptyMap()
        val user = buildUserPayload(items, categoryNames)
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
        const val SYSTEM = "You label Indian personal-finance transactions. You are given a fixed list of " +
            "category names and some merchant strings. For each item, choose the SINGLE best category NAME " +
            "from the provided list only. If none clearly fits, use null. Never invent a category name that " +
            "is not in the list. Optionally add a short human-readable cleanName for the merchant (e.g. " +
            "\"RAZ*FURLENCO BLR\" -> \"Furlenco\"), or null. Respond with ONLY a JSON object of the form " +
            "{\"results\":[{\"id\":<number>,\"category\":<name-or-null>,\"cleanName\":<string-or-null>}]}."

        /** The aggregate/merchant-only payload sent to Groq. Pure + testable — asserts nothing sensitive leaks. */
        internal fun buildUserPayload(items: List<AiCatItem>, categoryNames: List<String>): String {
            val cats = JSONArray()
            categoryNames.forEach { cats.put(it) }
            val arr = JSONArray()
            items.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("merchant", it.merchant)
                        .put("kind", it.kind.name),
                )
            }
            return JSONObject().put("categories", cats).put("items", arr).toString()
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
                out[id] = AiCatSuggestion(categoryName = name, cleanName = clean)
            }
            out
        }.getOrDefault(emptyMap())
    }
}
