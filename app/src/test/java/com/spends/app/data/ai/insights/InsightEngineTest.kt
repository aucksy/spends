package com.spends.app.data.ai.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules for what is worth putting on a card.
 *
 * The engine's job is judgement, not arithmetic: deciding that ₹40 → ₹160 is noise while ₹7,400 → ₹38,050
 * is a story. These tests pin that judgement, because the failure mode isn't a crash — it is a carousel full
 * of confident, useless cards, which is exactly the complaint this feature exists to fix.
 */
class InsightEngineTest {

    private fun input(
        current: Map<String, Long>,
        history: List<Map<String, Long>> = emptyList(),
        previous: Map<String, Long>? = null,
        currentSlices: List<ChargeSlice> = emptyList(),
        historySlices: List<ChargeSlice> = emptyList(),
    ) = InsightInput(
        expenseMinor = current.values.sum(),
        current = current,
        previous = previous,
        history = history,
        currentSlices = currentSlices,
        historySlices = historySlices,
    )

    private var nextId = 1L
    private fun slice(category: String, amountMinor: Long, dayEpoch: Long, merchant: String?, id: Long = nextId++) =
        ChargeSlice(id, category, amountMinor, dayEpoch, merchant)

    /** Six identical prior windows — a clean, unambiguous "usual". */
    private fun steadyHistory(category: String, amountMinor: Long) = List(6) { mapOf(category to amountMinor) }

    @Test
    fun `an empty cycle produces nothing`() {
        assertTrue(InsightEngine.detect(input(emptyMap())).isEmpty())
    }

    @Test
    fun `a category well above its own norm is flagged`() {
        val findings = InsightEngine.detect(
            input(current = mapOf("Business" to 38_050_00L), history = steadyHistory("Business", 7_400_00L)),
        )
        val unusual = findings.single { it.kind == InsightKind.UNUSUAL_CATEGORY }
        assertEquals("Business", unusual.category)
        assertEquals(38_050_00L, unusual.amountMinor)
        assertEquals(7_400_00L, unusual.baselineMinor)
        assertTrue("expected roughly 5x, got ${unusual.multiple}", unusual.multiple > 5.0 && unusual.multiple < 5.3)
    }

    @Test
    fun `a dramatic ratio on a trivial amount is NOT flagged`() {
        // 4x, and utterly unworth a card. The absolute floor is what stops the carousel filling with noise.
        val findings = InsightEngine.detect(
            input(current = mapOf("Gifts" to 160_00L), history = steadyHistory("Gifts", 40_00L)),
        )
        assertTrue(findings.none { it.kind == InsightKind.UNUSUAL_CATEGORY })
    }

    @Test
    fun `a category without enough history is not judged`() {
        // Two windows is not a habit. Calling a third month "unusual" against it would be a guess.
        val history = listOf(mapOf("Travel" to 9_000_00L), mapOf("Travel" to 9_000_00L)) + List(4) { emptyMap<String, Long>() }
        val findings = InsightEngine.detect(input(current = mapOf("Travel" to 40_000_00L), history = history))
        assertTrue(findings.none { it.kind == InsightKind.UNUSUAL_CATEGORY })
    }

    @Test
    fun `months with no spend in a category do not drag its usual figure down`() {
        // Present in 4 of 6 windows at a steady ₹10,000. Counting the two silent months as ₹0 would put the
        // median at ₹5,000 and make an entirely ordinary ₹10,000 month read as "2× your usual".
        val history = List(4) { mapOf("Travel" to 10_000_00L) } + List(2) { emptyMap<String, Long>() }
        val findings = InsightEngine.detect(input(current = mapOf("Travel" to 10_000_00L), history = history))
        assertTrue(
            "an ordinary month was reported as unusual: ${findings.map { it.kind }}",
            findings.none { it.kind == InsightKind.UNUSUAL_CATEGORY },
        )
    }

    @Test
    fun `easing off on a category is reported as a quiet win`() {
        val findings = InsightEngine.detect(
            input(current = mapOf("Entertainment" to 2_000_00L), history = steadyHistory("Entertainment", 9_000_00L)),
        )
        val win = findings.single { it.kind == InsightKind.QUIET_WIN }
        assertEquals("Entertainment", win.category)
        assertEquals(7_000_00L, win.materialityMinor)
    }

    @Test
    fun `the engine never reports a baseline the caller did not give it`() {
        // The engine does NO scaling: whatever "usual" figure it reports is one of the caller's own numbers,
        // measured to the same point in previous cycles (see InsightWindows). This pins that contract —
        // if scaling ever creeps back in, baselineMinor stops matching the input and this fails.
        val history = List(6) { mapOf("Food" to 4_000_00L) }
        val finding = InsightEngine.detect(input(current = mapOf("Food" to 12_000_00L), history = history))
            .single { it.kind == InsightKind.UNUSUAL_CATEGORY }
        assertEquals(4_000_00L, finding.baselineMinor)
        assertEquals(12_000_00L, finding.amountMinor)
    }

    @Test
    fun `two identical charges to the same merchant on one day are flagged`() {
        val slices = listOf(
            slice("Entertainment", 1_240_00L, 20_000L, "bookmyshow"),
            slice("Entertainment", 1_240_00L, 20_000L, "bookmyshow"),
        )
        val findings = InsightEngine.detect(input(current = mapOf("Entertainment" to 2_480_00L), currentSlices = slices))
        val dup = findings.single { it.kind == InsightKind.DUPLICATE_CHARGE }
        assertEquals(2, dup.count)
        assertEquals(1_240_00L, dup.amountMinor)
    }

    @Test
    fun `same amount on the same day at DIFFERENT merchants is not a duplicate`() {
        // Two ₹200 taxis is a coincidence, not a double-billing. Flagging it teaches the user to ignore the card.
        val slices = listOf(
            slice("Transport", 200_00L, 20_000L, "uber"),
            slice("Transport", 200_00L, 20_000L, "ola"),
        )
        val findings = InsightEngine.detect(input(current = mapOf("Transport" to 400_00L), currentSlices = slices))
        assertTrue(findings.none { it.kind == InsightKind.DUPLICATE_CHARGE })
    }

    @Test
    fun `an evenly split single transaction is not mistaken for a double charge`() {
        // A ₹2,000 shop split evenly across two categories arrives as two allocation rows with the same
        // amount, merchant and day — identical to a genuine double-billing unless the parent is considered.
        val split = listOf(
            slice("Food", 1_000_00L, 20_000L, "dmart", id = 77L),
            slice("Groceries", 1_000_00L, 20_000L, "dmart", id = 77L),
        )
        val findings = InsightEngine.detect(
            input(current = mapOf("Food" to 1_000_00L, "Groceries" to 1_000_00L), currentSlices = split),
        )
        assertTrue(findings.none { it.kind == InsightKind.DUPLICATE_CHARGE })
    }

    @Test
    fun `one charge far above the category's typical charge is flagged`() {
        val history = List(8) { slice("Fuel", 2_000_00L, 10_000L + it, null) }
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Fuel" to 12_500_00L),
                currentSlices = listOf(slice("Fuel", 12_500_00L, 20_000L, "shell")),
                historySlices = history,
            ),
        )
        val outlier = findings.single { it.kind == InsightKind.OUTLIER_CHARGE }
        assertEquals(12_500_00L, outlier.amountMinor)
        assertEquals(2_000_00L, outlier.baselineMinor)
    }

    @Test
    fun `concentration is only reported when spending really is top-heavy`() {
        val even = mapOf(
            "A" to 1000_00L, "B" to 1000_00L, "C" to 1000_00L,
            "D" to 1000_00L, "E" to 1000_00L, "F" to 1000_00L,
        )
        assertTrue(InsightEngine.detect(input(even)).none { it.kind == InsightKind.CONCENTRATION })

        val topHeavy = mapOf(
            "A" to 5000_00L, "B" to 3000_00L, "C" to 2000_00L,
            "D" to 300_00L, "E" to 200_00L, "F" to 100_00L,
        )
        val finding = InsightEngine.detect(input(topHeavy)).single { it.kind == InsightKind.CONCENTRATION }
        assertEquals(3, finding.count)
        assertEquals(94, finding.sharePercent)
    }

    @Test
    fun `concentration never fires on a handful of categories where it is arithmetically guaranteed`() {
        // The top 3 of 4 categories are >=75% of spend no matter how the money is split, and the top 3 of 5
        // are >=60%. A threshold below those floors makes the card unconditional — pure padding. So the
        // engine refuses to judge concentration at all until there are enough categories for it to mean
        // something, even when the share is enormous.
        val four = mapOf("A" to 4000_00L, "B" to 3000_00L, "C" to 2000_00L, "D" to 100_00L)
        assertTrue(InsightEngine.detect(input(four)).none { it.kind == InsightKind.CONCENTRATION })

        val five = mapOf("A" to 4000_00L, "B" to 3000_00L, "C" to 2000_00L, "D" to 100_00L, "E" to 100_00L)
        assertTrue(InsightEngine.detect(input(five)).none { it.kind == InsightKind.CONCENTRATION })
    }

    @Test
    fun `the carousel is capped and anomalies come first`() {
        val current = mapOf(
            "Business" to 38_050_00L, "Food" to 20_000_00L, "Travel" to 30_000_00L,
            "Shopping" to 18_000_00L, "Fuel" to 9_000_00L, "Health" to 400_00L,
        )
        val history = List(6) {
            mapOf(
                "Business" to 7_400_00L, "Food" to 9_000_00L, "Travel" to 4_000_00L,
                "Shopping" to 6_000_00L, "Fuel" to 2_500_00L, "Health" to 380_00L,
            )
        }
        val findings = InsightEngine.detect(
            input(
                current = current,
                history = history,
                currentSlices = listOf(
                    slice("Fuel", 8_000_00L, 20_000L, "shell"),
                    slice("Fuel", 8_000_00L, 20_000L, "shell"),
                ),
            ),
        )
        assertTrue("expected at most ${InsightEngine.MAX_FINDINGS}", findings.size <= InsightEngine.MAX_FINDINGS)
        assertEquals(InsightKind.DUPLICATE_CHARGE, findings.first().kind)
        assertTrue("concentration should never displace an anomaly", findings.none { it.kind == InsightKind.CONCENTRATION })
    }

    @Test
    fun `one category never gets two cards saying the same thing`() {
        // A category that trips "unusual" trips "biggest mover" too — the mover's comparison window IS the
        // first baseline window — so the carousel would otherwise burn two of its four slots restating one
        // fact. Asserting on distinct CATEGORIES, not on (kind, category): the latter is the key the engine
        // already dedupes by, so it could never fail.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 30_000_00L),
                previous = mapOf("Food" to 9_000_00L),
                history = steadyHistory("Food", 9_000_00L),
            ),
        )
        val named = findings.mapNotNull { it.category }
        assertEquals("one category, one card", named.size, named.distinct().size)
        assertTrue("the anomaly should survive, not the mover", findings.any { it.kind == InsightKind.UNUSUAL_CATEGORY })
        assertTrue(findings.none { it.kind == InsightKind.MOVER_UP })
    }

    @Test
    fun `median ignores a single extreme so one holiday does not redefine usual`() {
        assertEquals(2_000_00L, InsightEngine.median(listOf(2_000_00L, 2_000_00L, 2_000_00L, 90_000_00L, 1_900_00L)))
        assertEquals(0L, InsightEngine.median(emptyList()))
    }

    @Test
    fun `movers need a previous cycle`() {
        assertNull(
            InsightEngine.detect(input(current = mapOf("Food" to 9_000_00L)))
                .firstOrNull { it.kind == InsightKind.MOVER_UP || it.kind == InsightKind.MOVER_DOWN },
        )
    }
}
