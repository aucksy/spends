package com.spends.app.data.ai

import com.spends.app.data.capture.SmsParser
import com.spends.app.domain.model.TxnKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the money-safety + privacy + fail-closed behaviour of the AI categorizer's pure JSON boundary
 * (feature #1). These are the two functions that decide what LEAVES the phone and what the model's answer
 * is allowed to become — a regression here is a privacy or a wrong-category bug.
 */
class AiCategorizerTest {

    private val cats = listOf("Food", "Groceries", "Shopping", "Other")

    @Test fun `payload carries only merchant, kind and category names — no amount, date or balance`() {
        val items = listOf(AiCatItem(7, "RAZ*FURLENCO BLR", TxnKind.EXPENSE))
        val json = AiCategorizer.buildUserPayload(items, cats)
        val obj = JSONObject(json)
        assertEquals(setOf("categories", "items"), obj.keys().asSequence().toSet())
        val item = obj.getJSONArray("items").getJSONObject(0)
        assertEquals(setOf("id", "merchant", "kind"), item.keys().asSequence().toSet())
        assertEquals("RAZ*FURLENCO BLR", item.getString("merchant"))
        assertEquals("EXPENSE", item.getString("kind"))
        // Nothing money- or time-related may ever appear in the categorize payload.
        listOf("amount", "date", "balance", "last4", "account", "occurredAt", "rupee").forEach {
            assertFalse("payload must not contain '$it'", json.contains(it, ignoreCase = true))
        }
    }

    /**
     * The number-masked message text rides along ONLY when supplied, and carries no figure. This is what
     * lets a fuel purchase whose merchant parsed as a phone number still be categorised as fuel.
     */
    @Test fun `context text is sent as 'text' and contains no digits`() {
        val ctx = SmsParser.aiContextFor(
            "Spent INR 4034.37 Axis Bank Card no. XX4094 25-07-26 14:34:34 IST Hello Fuels " +
                "Avl Limit: INR 316576.91 Not you? SMS BLOCK 4094 to 919951860002",
        )
        val items = listOf(AiCatItem(1, "919951860002", TxnKind.EXPENSE, context = ctx))
        val json = AiCategorizer.buildUserPayload(items, cats)
        val item = JSONObject(json).getJSONArray("items").getJSONObject(0)
        assertEquals(setOf("id", "merchant", "kind", "text"), item.keys().asSequence().toSet())
        val text = item.getString("text")
        assertTrue(text.contains("Hello Fuels"))
        assertFalse("no digit may reach the context text", text.any { it.isDigit() })
        // Scoped to `text` on purpose: `merchant` is sent verbatim as the bank wrote it, and in THIS
        // fixture the merchant IS the phone number (that's the bug being worked around). What must
        // never leak is the amount, card number and balance, none of which appear in the merchant.
        listOf("4034", "4094", "316576").forEach {
            assertFalse("payload must not contain '$it'", json.contains(it))
        }
        assertFalse("the message body's digits must not survive masking", text.contains("919951860002"))
    }

    /**
     * The other privacy-guard tests build items WITHOUT context, so they'd keep passing even if the real
     * shipping payload leaked. This asserts the guarantee that actually matters on the wire: whatever
     * words ride along, no FIGURE from the message does.
     */
    @Test fun `the real with-context payload still carries no figure`() {
        val ctx = SmsParser.aiContextFor(
            "Your A/c XX5678 is debited by Rs.12,345.67 on 21-06-26 to JOHN DOE. Avl Bal: Rs.98,765.43. Ref 998877",
        )
        val items = listOf(AiCatItem(7, "JOHN DOE", TxnKind.EXPENSE, context = ctx))
        val json = AiCategorizer.buildUserPayload(items, cats)
        val text = JSONObject(json).getJSONArray("items").getJSONObject(0).getString("text")
        assertFalse("no digit may reach the context text", text.any { it.isDigit() })
        listOf("5678", "12,345", "12345", "98,765", "98765", "998877", "21-06-26").forEach {
            assertFalse("payload must not contain '$it'", json.contains(it))
        }
    }

    @Test fun `no 'text' key when there is no context`() {
        val items = listOf(AiCatItem(1, "swiggy", TxnKind.EXPENSE))
        val item = JSONObject(AiCategorizer.buildUserPayload(items, cats)).getJSONArray("items").getJSONObject(0)
        assertEquals(setOf("id", "merchant", "kind"), item.keys().asSequence().toSet())
    }

    @Test fun `parseResponse canonicalises the returned category spelling`() {
        val items = listOf(AiCatItem(1, "swiggy", TxnKind.EXPENSE))
        val res = AiCategorizer.parseResponse(
            """{"results":[{"id":1,"category":"food","cleanName":"Swiggy"}]}""", items, cats,
        )
        assertEquals("Food", res[1]?.categoryName) // canonical spelling from the caller's list, not "food"
        assertEquals("Swiggy", res[1]?.cleanName)
    }

    @Test fun `an off-list category is dropped (fail-closed)`() {
        val items = listOf(AiCatItem(1, "x", TxnKind.EXPENSE))
        assertTrue(AiCategorizer.parseResponse("""{"results":[{"id":1,"category":"Crypto"}]}""", items, cats).isEmpty())
    }

    @Test fun `a null category is dropped`() {
        val items = listOf(AiCatItem(1, "x", TxnKind.EXPENSE))
        assertTrue(AiCategorizer.parseResponse("""{"results":[{"id":1,"category":null}]}""", items, cats).isEmpty())
    }

    @Test fun `a hallucinated id not in the batch is ignored`() {
        val items = listOf(AiCatItem(1, "x", TxnKind.EXPENSE))
        assertTrue(AiCategorizer.parseResponse("""{"results":[{"id":999,"category":"Food"}]}""", items, cats).isEmpty())
    }

    @Test fun `malformed or empty json fails closed to an empty map`() {
        val items = listOf(AiCatItem(1, "x", TxnKind.EXPENSE))
        assertTrue(AiCategorizer.parseResponse("not json at all", items, cats).isEmpty())
        assertTrue(AiCategorizer.parseResponse("", items, cats).isEmpty())
    }

    @Test fun `a literal string null cleanName is treated as absent`() {
        val items = listOf(AiCatItem(1, "x", TxnKind.EXPENSE))
        val res = AiCategorizer.parseResponse(
            """{"results":[{"id":1,"category":"Food","cleanName":"null"}]}""", items, cats,
        )
        assertEquals("Food", res[1]?.categoryName)
        assertNull(res[1]?.cleanName)
    }

    @Test fun `an id sent as a string still matches`() {
        val items = listOf(AiCatItem(42, "x", TxnKind.EXPENSE))
        val res = AiCategorizer.parseResponse("""{"results":[{"id":"42","category":"Food"}]}""", items, cats)
        assertEquals("Food", res[42]?.categoryName)
    }

    // ---- learned-merchant reference (enhance, don't override) ----

    @Test fun `learned shortcuts are included as 'known' but still carry no amount or date`() {
        val items = listOf(AiCatItem(1, "RAZ*FURLENCO", TxnKind.EXPENSE))
        val learned = listOf(LearnedMerchant("furlenco", "Food"), LearnedMerchant("swiggy", "Food"))
        val json = AiCategorizer.buildUserPayload(items, cats, learned)
        val obj = JSONObject(json)
        assertEquals(setOf("categories", "items", "known"), obj.keys().asSequence().toSet())
        val known0 = obj.getJSONArray("known").getJSONObject(0)
        assertEquals(setOf("merchant", "category"), known0.keys().asSequence().toSet())
        assertEquals("furlenco", known0.getString("merchant"))
        assertEquals("Food", known0.getString("category"))
        listOf("amount", "date", "balance", "last4", "occurredAt").forEach {
            assertFalse("payload must not contain '$it'", json.contains(it, ignoreCase = true))
        }
    }

    @Test fun `no 'known' key when there are no learned shortcuts`() {
        val items = listOf(AiCatItem(1, "x", TxnKind.EXPENSE))
        assertFalse(JSONObject(AiCategorizer.buildUserPayload(items, cats, emptyList())).has("known"))
    }

    @Test fun `parseResponse reads the fromKnown flag so the chip can say 'Same as before'`() {
        val items = listOf(AiCatItem(1, "RAZ*FURLENCO", TxnKind.EXPENSE))
        val matched = AiCategorizer.parseResponse(
            """{"results":[{"id":1,"category":"Food","fromKnown":true}]}""", items, cats,
        )
        assertEquals("Food", matched[1]?.categoryName)
        assertTrue(matched[1]!!.fromKnown)
        // Absent flag defaults to false (a fresh guess, not a recognised repeat merchant).
        val fresh = AiCategorizer.parseResponse("""{"results":[{"id":1,"category":"Food"}]}""", items, cats)
        assertFalse(fresh[1]!!.fromKnown)
    }
}
