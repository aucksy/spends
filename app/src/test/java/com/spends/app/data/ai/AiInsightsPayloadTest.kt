package com.spends.app.data.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the privacy + fail-closed behaviour of the AI insights boundary (feature #2): the payload is aggregates
 * only, and a malformed model reply yields no card. A regression here would leak transaction-level data.
 */
class AiInsightsPayloadTest {

    private fun payload(withLast: Boolean) = InsightPayload(
        cycleLabel = "Current Salary Cycle",
        incomeMinor = 500_000L, // ₹5000.00
        expenseMinor = 321_050L, // ₹3210.50
        byCategory = listOf(
            InsightPayload.CategoryTotal("Food", 120_000L),
            InsightPayload.CategoryTotal("Rent", 200_000L),
        ),
        lastCycleExpenseMinor = if (withLast) 280_000L else null,
        lastCycleByCategory = if (withLast) listOf(InsightPayload.CategoryTotal("Food", 100_000L)) else null,
    )

    @Test fun `payload is aggregates only — no rows, dates, merchants, balances, notes or last4`() {
        val json = AiInsights.buildUserPayload(payload(withLast = true))
        val obj = JSONObject(json)
        assertEquals(
            setOf("cycleLabel", "income", "expense", "byCategory", "lastCycle"),
            obj.keys().asSequence().toSet(),
        )
        // ⭐Review round 17. Five public surfaces state that the PREVIOUS cycle's income never leaves the
        // phone — an absolute introduced in round 16 with nothing asserting it. `InsightPayload` has no
        // last-cycle income field today, so a `last.put("income", …)` would keep every other test green
        // and make five documents false, including a Play submission form.
        assertEquals(
            "the previous cycle sends spending only — five disclosure surfaces say so",
            setOf("expense", "byCategory"),
            obj.getJSONObject("lastCycle").keys().asSequence().toSet(),
        )
        // ⭐Review round 21. The root and `lastCycle` key sets were pinned; the objects INSIDE
        // `byCategory` were not. A `largestCharge`, `count` or `firstSeen` field added there is a new CLASS
        // of data leaving the phone from the payload whose KDoc says "aggregates only — no individual
        // rows", and nothing in the suite would have failed.
        assertEquals(
            setOf("name", "total"),
            obj.getJSONArray("byCategory").getJSONObject(0).keys().asSequence().toSet(),
        )
        // Asserted separately rather than trusted: both arrays happen to be built by the same
        // `categoriesJson` today, so the line above covers this one only by coincidence of implementation.
        // Fork that call and the last-cycle level would go unpinned in silence.
        assertEquals(
            setOf("name", "total"),
            obj.getJSONObject("lastCycle").getJSONArray("byCategory").getJSONObject(0)
                .keys().asSequence().toSet(),
        )
        listOf("merchant", "date", "occurredAt", "balance", "last4", "account", "note", "sms").forEach {
            assertFalse("payload must not contain '$it'", json.contains(it, ignoreCase = true))
        }
    }

    @Test fun `amounts are sent in rupees, not paise, biggest category first`() {
        val obj = JSONObject(AiInsights.buildUserPayload(payload(withLast = false)))
        assertEquals(5000.0, obj.getDouble("income"), 0.001)
        assertEquals(3210.5, obj.getDouble("expense"), 0.001)
        val top = obj.getJSONArray("byCategory").getJSONObject(0)
        assertEquals("Rent", top.getString("name")) // sorted desc by amount
        assertEquals(2000.0, top.getDouble("total"), 0.001)
    }

    @Test fun `no lastCycle key when there is no previous-cycle data`() {
        assertFalse(JSONObject(AiInsights.buildUserPayload(payload(withLast = false))).has("lastCycle"))
    }

    @Test fun `parseSummary reads the summary field`() {
        assertEquals("You spent less this week.", AiInsights.parseSummary("""{"summary":"You spent less this week."}"""))
    }

    @Test fun `parseSummary falls back to the text key`() {
        assertEquals("hi", AiInsights.parseSummary("""{"text":"hi"}"""))
    }

    @Test fun `parseSummary fails closed on malformed, empty or blank`() {
        assertNull(AiInsights.parseSummary("not json"))
        assertNull(AiInsights.parseSummary("{}"))
        assertNull(AiInsights.parseSummary("""{"summary":""}"""))
        assertNull(AiInsights.parseSummary("""{"summary":"   "}"""))
    }

    @Test fun `the summary prompt carries every prohibition its disclosure claims`() {
        // ⭐Page 1 is the card every user sees, and until review round 16 nothing asserted anything about
        // its prompt. README.md and play/PERMISSIONS_DECLARATION.md both make an absolute claim about
        // EVERY card; this pins the half of it that had no guard. Mirrors
        // `the narrator prompt still forbids advice outright, with no known carve-out or dropped symbol left in it`
        // in InsightNarratorTest.
        val system = AiInsights.SYSTEM.lowercase()
        // ⭐Review round 18. The two prompts word this differently ("Never give" here, "Do not give" in
        // InsightNarrator), so the verb is an alternation — but the NEGATION is matched, not dropped.
        // Round 17 asserted the bare topic "give financial advice", which a prompt saying "You may give
        // financial advice" satisfies just as well. A prohibition test that passes on the permission is
        // worse than no test.
        assertTrue(
            "the advice prohibition must survive, and must still be a prohibition",
            Regex("(never|do not|don['\u2019]t) give financial advice").containsMatchIn(system),
        )
        listOf("never suggest spending less", "never propose an action")
            .forEach { assertTrue("the summary prompt no longer says \"$it\"", system.contains(it)) }
        // A TRIPWIRE for known carve-out phrasings, not a proof that none exists — "other than",
        // "apart from" and "you may … when" would all still pass. `unless the` is scoped deliberately:
        // a bare `unless` would redden CI on a STRENGTHENING like "never mention a number unless it was
        // given to you", while still missing nothing this form catches.
        listOf("only exception", "one exception", "except for", "except when", "unless the")
            .forEach { assertFalse("a carve-out appeared in the summary prompt: $it", system.contains(it)) }
    }

    @Test fun `fingerprint changes when a total changes`() {
        val a = payload(withLast = false).fingerprint
        val b = payload(withLast = false).copy(expenseMinor = 999_900L).fingerprint
        assertTrue(a != b)
    }
}
