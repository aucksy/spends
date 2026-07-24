package com.spends.app.ui.review

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the money-safety-adjacent rule for AI category suggestions (#1, guardrail G1): the AI is offered ONLY
 * for rows the deterministic rules couldn't place ("Other"/"Other Income"/none). A row that a keyword rule or
 * a learned mapping placed on a REAL category must never be considered — so AI can never override a confident
 * or learned pick. (The learned-mapping exclusion itself is enforced separately via hasLearnedCategory.)
 */
class ReviewEligibilityTest {

    @Test fun `fallback categories are eligible`() {
        assertTrue(isFallbackCategory("Other"))
        assertTrue(isFallbackCategory("other")) // case-insensitive
        assertTrue(isFallbackCategory("Other Income"))
        assertTrue(isFallbackCategory(null))
    }

    @Test fun `a real category is never eligible for an AI suggestion`() {
        assertFalse(isFallbackCategory("Food"))
        assertFalse(isFallbackCategory("Groceries"))
        assertFalse(isFallbackCategory("Shopping"))
        assertFalse(isFallbackCategory("Rent"))
    }
}
