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

    @Test fun `fingerprint changes when a total changes`() {
        val a = payload(withLast = false).fingerprint
        val b = payload(withLast = false).copy(expenseMinor = 999_900L).fingerprint
        assertTrue(a != b)
    }
}
