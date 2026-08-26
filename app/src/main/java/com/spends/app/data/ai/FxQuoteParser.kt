package com.spends.app.data.ai

import com.spends.app.core.money.FxMath
import org.json.JSONObject

/**
 * A usable exchange-rate quote: [rateMicros] base-currency units per ONE foreign unit, plus the model's
 * own one-line account of it ([note]) so the number is never presented as an unexplained fact.
 */
data class FxQuote(
    val fromCode: String,
    val toCode: String,
    val rateMicros: Long,
    val note: String,
)

/**
 * Turns the model's reply into an [FxQuote], or null.
 *
 * Kept pure and free of Android, network and clock so the whole trust boundary — the point where text
 * written by a language model becomes a number that scales somebody's money — is exhaustively unit
 * testable. Everything here is defensive on purpose: the reply is UNTRUSTED input, and the failure mode
 * we care about is not a crash but a plausible-looking wrong rate reaching the ledger.
 *
 * A quote is rejected unless it survives every check:
 *  - the payload is real JSON (bare, or inside a ```json fence, or embedded in prose),
 *  - `rate` is a positive, finite number inside [FxMath]'s sane band,
 *  - the currency pair the model answered for is the pair we ASKED about — a model that silently
 *    answers a different pair must not have its rate applied to this amount.
 */
object FxQuoteParser {

    /**
     * @param expectedFrom the foreign code we asked to convert FROM (e.g. "MYR")
     * @param expectedTo the base code we asked to convert TO (e.g. "INR")
     */
    fun parse(raw: String?, expectedFrom: String, expectedTo: String): FxQuote? {
        val json = extractJsonObject(raw) ?: return null

        // A model that answered about a different pair is answering a different question. Missing fields
        // are tolerated (older/terser replies just echo the rate); a PRESENT but MISMATCHED one is fatal.
        val from = json.optString("from").trim().uppercase()
        val to = json.optString("to").trim().uppercase()
        if (from.isNotEmpty() && from != expectedFrom.uppercase()) return null
        if (to.isNotEmpty() && to != expectedTo.uppercase()) return null

        val rateMicros = FxMath.rateMicrosFromDouble(readRate(json)) ?: return null

        val note = json.optString("note").trim()
            .takeIf { it.isNotEmpty() && it.length <= MAX_NOTE_CHARS }
            ?: "Converted at 1 ${expectedFrom.uppercase()} = ${FxMath.formatRate(rateMicros)} ${expectedTo.uppercase()}."

        return FxQuote(
            fromCode = expectedFrom.uppercase(),
            toCode = expectedTo.uppercase(),
            rateMicros = rateMicros,
            note = note,
        )
    }

    /**
     * `rate` as a number. Accepts a JSON string too ("18.90") — models routinely quote a number as a
     * string, and rejecting that would fail a perfectly good answer. Anything unparseable yields null,
     * which [parse] turns into "no conversion".
     */
    private fun readRate(json: JSONObject): Double? {
        if (!json.has("rate")) return null
        json.optDouble("rate").let { if (!it.isNaN()) return it }
        val raw = json.optString("rate").trim()
        // Commas are stripped ONLY when they are genuine thousands separators ("16,000.5" — a real rate for
        // IDR). A lone European decimal comma is REJECTED, not stripped: reading "18,9" as 189 would be a
        // ten-fold error on every converted transaction, and silently wrong is the one outcome that matters
        // here. Rejecting means "no conversion", which is safe.
        val cleaned = if (THOUSANDS_GROUPED.matches(raw)) raw.replace(",", "") else raw
        if (cleaned.contains(',')) return null
        return cleaned.toDoubleOrNull()
    }

    /** 1,234 / 16,000.5 — digits in three-groups, optionally with a decimal part. */
    private val THOUSANDS_GROUPED = Regex("^[0-9]{1,3}(,[0-9]{3})+(\\.[0-9]+)?$")

    /**
     * Find the JSON object in a reply. Handles a bare object, a ```json fenced block, and an object
     * embedded in explanatory prose, by scanning from the first `{` to its MATCHING `}` with a brace
     * depth counter that ignores braces inside string literals (a note like "costs {a lot}" would
     * otherwise truncate the object at the wrong place).
     */
    fun extractJsonObject(raw: String?): JSONObject? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val start = text.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching { JSONObject(text.substring(start, i + 1)) }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    private const val MAX_NOTE_CHARS = 200
}
