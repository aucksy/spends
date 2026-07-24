package com.spends.app.data.ai

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
}
