package com.spends.app.data.ai.insights

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What leaves the phone, and what happens when the model doesn't play along.
 *
 * The payload test is a privacy guard, not a formatting check: it asserts the *absence* of merchant names,
 * dates and transaction-level detail. The narrator sees findings that were computed from rows containing all
 * three, so "we didn't include them" has to be enforced rather than assumed.
 */
class InsightNarratorTest {

    private val findings = listOf(
        InsightFinding(
            kind = InsightKind.UNUSUAL_CATEGORY,
            category = "Business Expenses",
            amountMinor = 38_050_00L,
            baselineMinor = 7_400_00L,
            multiple = 5.14,
            materialityMinor = 30_650_00L,
        ),
        InsightFinding(
            kind = InsightKind.DUPLICATE_CHARGE,
            category = "Entertainment",
            amountMinor = 1_240_00L,
            count = 2,
            materialityMinor = 1_240_00L,
        ),
    )

    @Test
    fun `the payload carries aggregates only`() {
        val json = InsightNarrator.buildUserPayload("Current Salary Cycle", findings)
        val lower = json.lowercase()
        // Distinctive tokens only — a substring like "id" would false-positive on an ordinary category name.
        listOf("merchant", "bookmyshow", "occurredat", "dayepoch", "last4", "dedupe", "rawbody", "sender", "balance")
            .forEach { assertFalse("payload leaked '$it': $json", lower.contains(it)) }
        val root = JSONObject(json)
        assertEquals("Current Salary Cycle", root.getString("cycleLabel"))
        val first = root.getJSONArray("findings").getJSONObject(0)
        // Rupees, not paise — the model is told to quote these verbatim, so they must already be right.
        assertEquals(38050.0, first.getDouble("amount"), 0.001)
        assertEquals(7400.0, first.getDouble("usualAmount"), 0.001)
        assertEquals("Business Expenses", first.getString("category"))
    }

    @Test
    fun `zero-valued fields are omitted rather than sent as noise`() {
        val json = JSONObject(InsightNarrator.buildUserPayload("This month", findings))
        val duplicate = json.getJSONArray("findings").getJSONObject(1)
        assertFalse(duplicate.has("usualAmount"))
        assertFalse(duplicate.has("sharePercent"))
        assertEquals(2, duplicate.getInt("count"))
    }

    @Test
    fun `well-formed cards are parsed in order`() {
        val cards = InsightNarrator.parseCards(
            """{"cards":[{"kind":"UNUSUAL_CATEGORY","title":"Business is up","body":"You spent a lot."},
               {"kind":"DUPLICATE_CHARGE","title":"Charged twice?","body":"Two the same."}]}""",
        )
        assertEquals(2, cards.size)
        assertEquals("Business is up", cards[0].title)
        assertEquals("UNUSUAL_CATEGORY", cards[0].kind)
        assertEquals("Two the same.", cards[1].body)
    }

    @Test
    fun `a card with no kind is still usable`() {
        // Validation is a guard against reordering, not a demand. If the model omits the echo entirely we
        // fall back to positional pairing rather than throwing away a perfectly good reply.
        val cards = InsightNarrator.parseCards("""{"cards":[{"title":"T","body":"B"}]}""")
        assertEquals(1, cards.size)
        assertNull(cards[0].kind)
    }

    @Test
    fun `malformed replies fall back to nothing rather than garbage`() {
        assertTrue(InsightNarrator.parseCards("not json at all").isEmpty())
        assertTrue(InsightNarrator.parseCards("""{"unexpected":true}""").isEmpty())
        assertTrue(InsightNarrator.parseCards("").isEmpty())
    }

    @Test
    fun `every finding kind has usable wording without the AI`() {
        // A failed call must cost the prose, not the card. Each fallback has to be a real sentence.
        InsightKind.entries.filter { it != InsightKind.CYCLE_SUMMARY }.forEach { kind ->
            val finding = InsightFinding(
                kind = kind,
                category = "Food",
                amountMinor = 5_000_00L,
                baselineMinor = 2_000_00L,
                multiple = 2.5,
                sharePercent = 68,
                count = 2,
            )
            assertTrue("$kind has no fallback title", finding.fallbackTitle().isNotBlank())
            val body = finding.fallbackBody()
            assertTrue("$kind has no fallback body", body.length > 20)
            assertTrue("$kind fallback omits the rupee figure", body.contains("₹"))
        }
    }

    @Test
    fun `a whole multiple reads as 3x not 3_0x`() {
        val body = InsightFinding(
            kind = InsightKind.OUTLIER_CHARGE,
            category = "Fuel",
            amountMinor = 6_000_00L,
            baselineMinor = 2_000_00L,
            multiple = 3.0,
        ).fallbackBody()
        assertTrue("expected a clean '3×' in: $body", body.contains("3×"))
        assertFalse(body.contains("3.0×"))
    }
}
