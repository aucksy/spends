package com.spends.app.data.ai.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        daysElapsed: Int = 0,
        cycleDays: Int = 0,
        fullHistory: List<Map<String, Long>> = emptyList(),
        yearAgo: YearAgoWindow? = null,
        habits: HabitBuckets? = null,
        cycleComplete: Boolean = false,
        wholeCycleComparable: Boolean = true,
    ) = InsightInput(
        expenseMinor = current.values.sum(),
        current = current,
        previous = previous,
        history = history,
        currentSlices = currentSlices,
        historySlices = historySlices,
        daysElapsed = daysElapsed,
        cycleDays = cycleDays,
        fullHistory = fullHistory,
        yearAgo = yearAgo,
        habits = habits,
        cycleComplete = cycleComplete,
        wholeCycleComparable = wholeCycleComparable,
    )

    private val anomalyKinds = setOf(
        InsightKind.DUPLICATE_CHARGE, InsightKind.UNUSUAL_CATEGORY,
        InsightKind.OUTLIER_CHARGE, InsightKind.QUIET_WIN,
    )
    private val overTimeKinds = setOf(
        InsightKind.PACE, InsightKind.YEAR_ON_YEAR,
        InsightKind.CATEGORY_TREND, InsightKind.HABIT_PAYDAY,
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
    fun `a directional finding is never emitted pointing the wrong way`() {
        // The fallback sentences call abs() so an inverted finding can't render "— -₹7,000 less". That hides
        // the breakage rather than fixing it, so the invariant it relies on is pinned HERE instead: whatever
        // fixture is thrown at the engine, a quiet win must be smaller than its baseline and a mover must
        // move the way its name says. Without this, a future sign error reads as a perfectly plausible lie.
        // ⭐The mover fixtures need a category the anomaly detectors will NOT judge, or the movers are
        // stripped by RESTATES_A_JUDGED_CATEGORY and those two branches never execute — a guard that guards
        // nothing, which is exactly the tautology trap from the previous round. Rent appears in no history
        // window, so it can never be called unusual, and its mover survives.
        val risingRent = input(
            current = mapOf("Food" to 30_000_00L, "Rent" to 25_000_00L),
            previous = mapOf("Food" to 30_000_00L, "Rent" to 9_000_00L),
            history = steadyHistory("Food", 30_000_00L),
        )
        val fallingRent = input(
            current = mapOf("Food" to 30_000_00L, "Rent" to 9_000_00L),
            previous = mapOf("Food" to 30_000_00L, "Rent" to 25_000_00L),
            history = steadyHistory("Food", 30_000_00L),
        )
        val fixtures = listOf(
            risingRent,
            fallingRent,
            input(current = mapOf("Food" to 30_000_00L), previous = mapOf("Food" to 9_000_00L), history = steadyHistory("Food", 9_000_00L)),
            busyCycle(daysElapsed = 12, cycleDays = 30, habits = paydayHeavy()),
            input(current = mapOf("Entertainment" to 2_000_00L), history = steadyHistory("Entertainment", 9_000_00L)),
        )
        val all = fixtures.flatMap { InsightEngine.detect(it) }
        all.forEach { finding ->
            when (finding.kind) {
                InsightKind.QUIET_WIN ->
                    assertTrue("a quiet win must be BELOW its baseline: $finding", finding.amountMinor <= finding.baselineMinor)
                InsightKind.MOVER_UP ->
                    assertTrue("a rise must actually rise: $finding", finding.amountMinor > finding.baselineMinor)
                InsightKind.MOVER_DOWN ->
                    assertTrue("a fall must actually fall: $finding", finding.amountMinor < finding.baselineMinor)
                InsightKind.UNUSUAL_CATEGORY ->
                    assertTrue("an unusual category must be ABOVE its baseline: $finding", finding.amountMinor >= finding.baselineMinor)
                else -> Unit
            }
        }
        // Prove the branches above actually ran. Without this the whole test passes on an empty list.
        assertTrue("no MOVER_UP was produced — the guard is decoration", all.any { it.kind == InsightKind.MOVER_UP })
        assertTrue("no MOVER_DOWN was produced — the guard is decoration", all.any { it.kind == InsightKind.MOVER_DOWN })
        assertTrue(all.any { it.kind == InsightKind.QUIET_WIN })
        assertTrue(all.any { it.kind == InsightKind.UNUSUAL_CATEGORY })
    }

    @Test
    fun `median always returns a figure that was really spent`() {
        // ⭐The textbook median averages the two middle values on an even-length list — and six cycles of
        // history is the ORDINARY case, so the baseline pace quoted as "your recent cycles were at ₹4,500"
        // was routinely a number no cycle ever reached. Every value the engine reports has to be one the
        // user actually spent; that is the whole basis for trusting "3× your usual".
        val cycles = listOf(4_000_00L, 5_000_00L)
        assertEquals(4_000_00L, InsightEngine.median(cycles))

        val six = listOf(1_000_00L, 2_000_00L, 3_000_00L, 4_000_00L, 5_000_00L, 6_000_00L)
        assertTrue("the median must be one of the observations", InsightEngine.median(six) in six)
    }

    @Test
    fun `the carousel never shows two comparisons of the same whole-cycle total`() {
        // ⭐REGRESSION GUARD. The slate enforced "at most one whole-cycle card", then the back-fill re-admitted
        // the very card it had skipped — so a quiet cycle showed pace saying "₹5,100 ahead" on one page and
        // year-on-year saying "₹19,470 less" on the next, about the same total.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Dining" to 20_000_00L),
                history = steadyPace(),
                daysElapsed = 12,
                cycleDays = 30,
                fullHistory = climbingDining,
                yearAgo = YearAgoWindow(8_000_00L, "July"),
                habits = paydayHeavy(),
            ),
        )
        assertEquals(
            "pace and year-on-year must never both survive",
            1,
            findings.count { it.kind == InsightKind.PACE || it.kind == InsightKind.YEAR_ON_YEAR },
        )
        // The freed slot must go to a card that says something DIFFERENT, not stay empty.
        assertTrue(findings.any { it.kind == InsightKind.CATEGORY_TREND })
        assertTrue(findings.any { it.kind == InsightKind.HABIT_PAYDAY })
    }

    @Test
    fun `movers need a previous cycle`() {
        assertNull(
            InsightEngine.detect(input(current = mapOf("Food" to 9_000_00L)))
                .firstOrNull { it.kind == InsightKind.MOVER_UP || it.kind == InsightKind.MOVER_DOWN },
        )
    }

    // ---- pace ----

    /** Four prior cycles that each reached ₹10,000 by this day. No scaling anywhere — this IS the baseline. */
    private fun steadyPace(perWindow: Long = 10_000_00L) = List(4) { mapOf("Food" to perWindow) }

    @Test
    fun `pace compares this cycle against where earlier ones stood at the same point`() {
        val finding = InsightEngine.detect(
            input(current = mapOf("Food" to 20_000_00L), history = steadyPace(), daysElapsed = 12, cycleDays = 30),
        ).single { it.kind == InsightKind.PACE }
        assertEquals(20_000_00L, finding.amountMinor)
        assertEquals(10_000_00L, finding.baselineMinor)
        assertEquals(12, finding.days)
    }

    @Test
    fun `pace says nothing in the first few days of a cycle`() {
        // On day 3, one rent payment is most of the cycle's spend and every user is "miles ahead of usual".
        val findings = InsightEngine.detect(
            input(current = mapOf("Food" to 20_000_00L), history = steadyPace(), daysElapsed = 3, cycleDays = 30),
        )
        assertTrue(findings.none { it.kind == InsightKind.PACE })
    }

    @Test
    fun `pace says nothing about a cycle that has already finished`() {
        // Reachable by stepping back. "Day 30 of the cycle and…" would describe the past in the present tense.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 20_000_00L), history = steadyPace(),
                daysElapsed = 30, cycleDays = 30, cycleComplete = true,
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.PACE })
    }

    @Test
    fun `pace still speaks on the last day of a cycle that is still running`() {
        // The completed check is an explicit flag rather than daysElapsed >= cycleDays precisely because the
        // clamped day count cannot tell day 30 of a live cycle from a cycle finished months ago — and the
        // arithmetic version silently threw away the last day of every cycle.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 20_000_00L), history = steadyPace(),
                daysElapsed = 30, cycleDays = 30, cycleComplete = false,
            ),
        )
        assertTrue(findings.any { it.kind == InsightKind.PACE })
    }

    @Test
    fun `the whole-cycle comparisons stay silent when the totals are not comparable`() {
        // Smart Cycle buckets a card purchase into the cycle its statement BILLS, while the history queries
        // read raw transaction dates. A per-category card needing a 2x swing tolerates that; pace at 1.25x and
        // year-on-year at 1.15x compare whole totals and would be quoting two different definitions of the
        // same cycle at each other.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 20_000_00L),
                history = steadyPace(),
                daysElapsed = 12,
                cycleDays = 30,
                yearAgo = YearAgoWindow(8_000_00L, "July"),
                wholeCycleComparable = false,
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.PACE })
        assertTrue(findings.none { it.kind == InsightKind.YEAR_ON_YEAR })
    }

    @Test
    fun `pace ignores a big ratio on a small amount`() {
        // Double the usual, and the usual was ₹1,000. Nothing here is worth a card. Isolating the rupee floor:
        // at this size the unusual-category detector stays quiet too, so PACE is the only thing being tested.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 2_000_00L),
                history = List(4) { mapOf("Food" to 1_000_00L) },
                daysElapsed = 12,
                cycleDays = 30,
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.PACE })
    }

    @Test
    fun `pace needs several earlier cycles before it means anything`() {
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 20_000_00L),
                history = List(2) { mapOf("Food" to 10_000_00L) },
                daysElapsed = 12,
                cycleDays = 30,
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.PACE })
    }

    // ---- year on year ----

    /** Twelve days into a thirty-day cycle — past the point where a single fixed charge dominates. */
    private fun midCycle(current: Map<String, Long>, yearAgo: YearAgoWindow?, complete: Boolean = false) =
        input(current = current, daysElapsed = if (complete) 30 else 12, cycleDays = 30, yearAgo = yearAgo, cycleComplete = complete)

    @Test
    fun `year on year compares the same stretch a year earlier`() {
        val finding = InsightEngine.detect(
            midCycle(mapOf("Food" to 30_000_00L), YearAgoWindow(20_000_00L, "July")),
        ).single { it.kind == InsightKind.YEAR_ON_YEAR }
        assertEquals(30_000_00L, finding.amountMinor)
        assertEquals(20_000_00L, finding.baselineMinor)
        assertEquals("July", finding.periodLabel)
        assertEquals(12, finding.days)
        assertTrue("an unfinished cycle still reads as 'so far'", finding.fallbackBody().contains("so far"))
    }

    @Test
    fun `year on year says nothing in the first few days of a cycle`() {
        // On day 2 this compares a two-day stretch against a two-day stretch. One rent payment on either side
        // clears every rupee floor, and "₹40,000 this August against ₹6,000 last August" is a true statement
        // about two days and a false impression of the year.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 30_000_00L), daysElapsed = 2, cycleDays = 30,
                yearAgo = YearAgoWindow(6_000_00L, "August"),
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.YEAR_ON_YEAR })
    }

    @Test
    fun `year on year drops the so-far wording once the cycle has finished`() {
        // Unlike pace, this card is still worth showing for a cycle browsed back to — the comparison holds.
        // Only the tense has to stop claiming it is still happening.
        val finding = InsightEngine.detect(
            midCycle(mapOf("Food" to 30_000_00L), YearAgoWindow(20_000_00L, "July"), complete = true),
        ).single { it.kind == InsightKind.YEAR_ON_YEAR }
        assertEquals(0, finding.days)
        assertFalse("a finished cycle is not still 'so far'", finding.fallbackBody().contains("so far"))
        assertTrue(finding.fallbackBody().contains("this July"))
    }

    @Test
    fun `a nearly empty year-ago window is missing history, not a frugal month`() {
        // The dangerous case: the app wasn't in use then, so "₹27,000 more than last July" is FALSE rather
        // than merely unflattering. The provider gates on the records reaching back; this is the last gate.
        val findings = InsightEngine.detect(
            midCycle(mapOf("Food" to 30_000_00L), YearAgoWindow(3_000_00L, "July")),
        )
        assertTrue(findings.none { it.kind == InsightKind.YEAR_ON_YEAR })
    }

    @Test
    fun `year on year stays quiet when the two years look much the same`() {
        // ₹5,000 apart clears the rupee floor, but on ₹1,00,000 that is 5% — noise dressed as a finding.
        val findings = InsightEngine.detect(
            midCycle(mapOf("Food" to 105_000_00L), YearAgoWindow(100_000_00L, "July")),
        )
        assertTrue(findings.none { it.kind == InsightKind.YEAR_ON_YEAR })
    }

    // ---- category trend ----

    /** Most recent cycle FIRST. Time order here is ₹4,000 → ₹4,500 → ₹5,000 → ₹5,800 → ₹6,300 → ₹6,800. */
    private val climbingDining = listOf(
        mapOf("Dining" to 6_800_00L), mapOf("Dining" to 6_300_00L), mapOf("Dining" to 5_800_00L),
        mapOf("Dining" to 5_000_00L), mapOf("Dining" to 4_500_00L), mapOf("Dining" to 4_000_00L),
    )

    @Test
    fun `a category climbing across six cycles is reported with two figures that were really spent`() {
        val finding = InsightEngine.detect(
            input(current = mapOf("Dining" to 6_500_00L), fullHistory = climbingDining),
        ).single { it.kind == InsightKind.CATEGORY_TREND }
        // Median of the recent three (₹6,800 / ₹6,300 / ₹5,800) against the older three (₹5,000 / ₹4,500 /
        // ₹4,000). Both are cycle totals the user actually spent — not the endpoints of a fitted line, which
        // would be amounts nobody ever paid.
        assertEquals(6_300_00L, finding.amountMinor)
        assertEquals(4_500_00L, finding.baselineMinor)
        assertEquals(6, finding.spanCycles)
        assertEquals("Dining", finding.category)
    }

    @Test
    fun `a category that spiked once long ago is not called a trend`() {
        // Time order: ₹50,000 → ₹3,000 → ₹3,100 → ₹6,000 → ₹6,200 → ₹6,400. The halves say "up ₹3,100"
        // because the median shrugs off the spike, but the line through all six points slopes DOWN. Calling
        // this "creeping up" would describe a category that has in fact collapsed since its peak.
        val spiked = listOf(
            mapOf("Dining" to 6_400_00L), mapOf("Dining" to 6_200_00L), mapOf("Dining" to 6_000_00L),
            mapOf("Dining" to 3_100_00L), mapOf("Dining" to 3_000_00L), mapOf("Dining" to 50_000_00L),
        )
        val findings = InsightEngine.detect(input(current = mapOf("Dining" to 6_500_00L), fullHistory = spiked))
        assertTrue(findings.none { it.kind == InsightKind.CATEGORY_TREND })
    }

    @Test
    fun `a category bought in only some cycles has gaps, not a trend`() {
        val patchy = climbingDining.mapIndexed { index, window ->
            if (index % 3 == 0) emptyMap<String, Long>() else window
        }
        val findings = InsightEngine.detect(input(current = mapOf("Dining" to 6_500_00L), fullHistory = patchy))
        assertTrue(findings.none { it.kind == InsightKind.CATEGORY_TREND })
    }

    @Test
    fun `a small drift across six cycles is not worth a card`() {
        val flat = List(6) { mapOf("Dining" to 4_000_00L + it * 20_00L) }
        val findings = InsightEngine.detect(input(current = mapOf("Dining" to 4_000_00L), fullHistory = flat))
        assertTrue(findings.none { it.kind == InsightKind.CATEGORY_TREND })
    }

    @Test
    fun `a trend never restates a category that already has an anomaly card`() {
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Dining" to 30_000_00L),
                history = List(6) { mapOf("Dining" to 4_500_00L) },
                fullHistory = climbingDining,
            ),
        )
        assertTrue(findings.any { it.kind == InsightKind.UNUSUAL_CATEGORY })
        assertTrue(findings.none { it.kind == InsightKind.CATEGORY_TREND })
    }

    // ---- payday habit ----

    /** ₹40,000 of ₹1,00,000 lands in 42 of 180 days — 40% of the money in 23% of the days. */
    private fun paydayHeavy(paydayMinor: Long = 40_000_00L, windows: Int = 6) =
        HabitBuckets(
            totalMinor = 100_000_00L,
            totalDays = 180,
            paydayWeekMinor = paydayMinor,
            paydayWeekDays = 42,
            windowsWithData = windows,
        )

    @Test
    fun `the payday habit is stated as two shares, of money and of days`() {
        val finding = InsightEngine.detect(
            input(current = mapOf("Food" to 5_000_00L), habits = paydayHeavy()),
        ).single { it.kind == InsightKind.HABIT_PAYDAY }
        assertEquals(40, finding.sharePercent)
        assertEquals(23, finding.dayShare)
        assertEquals(6, finding.count)
    }

    @Test
    fun `a payday week carrying barely more than its share of days is not a habit`() {
        // 28% of the money in 23% of the days. Real, tiny, and not worth telling anyone about.
        val findings = InsightEngine.detect(
            input(current = mapOf("Food" to 5_000_00L), habits = paydayHeavy(paydayMinor = 28_000_00L)),
        )
        assertTrue(findings.none { it.kind == InsightKind.HABIT_PAYDAY })
    }

    @Test
    fun `a habit needs several cycles of evidence`() {
        val findings = InsightEngine.detect(
            input(current = mapOf("Food" to 5_000_00L), habits = paydayHeavy(windows = 3)),
        )
        assertTrue(findings.none { it.kind == InsightKind.HABIT_PAYDAY })
    }

    @Test
    fun `a habit is not read into a handful of small charges`() {
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 5_000_00L),
                habits = HabitBuckets(
                    totalMinor = 9_000_00L,
                    totalDays = 180,
                    paydayWeekMinor = 8_000_00L,
                    paydayWeekDays = 42,
                    windowsWithData = 6,
                ),
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.HABIT_PAYDAY })
    }

    // ---- how the five slots are shared out ----

    private fun busyCycle(
        daysElapsed: Int = 0,
        cycleDays: Int = 0,
        yearAgo: YearAgoWindow? = null,
        fullHistory: List<Map<String, Long>> = emptyList(),
        habits: HabitBuckets? = null,
    ) = input(
        // Business 5.1x, Travel 7.5x and Fuel 3.6x are all unusual; Entertainment has eased right off; and the
        // two identical Entertainment charges are a possible double-billing. Five anomalies, four slots' worth
        // of appetite — which is the whole point.
        current = mapOf(
            "Business" to 38_050_00L, "Entertainment" to 2_480_00L, "Travel" to 30_000_00L,
            "Fuel" to 9_000_00L, "Dining" to 6_500_00L,
        ),
        history = List(6) {
            mapOf("Business" to 7_400_00L, "Entertainment" to 9_000_00L, "Travel" to 4_000_00L, "Fuel" to 2_500_00L)
        },
        currentSlices = listOf(
            slice("Entertainment", 1_240_00L, 20_000L, "bookmyshow"),
            slice("Entertainment", 1_240_00L, 20_000L, "bookmyshow"),
        ),
        daysElapsed = daysElapsed,
        cycleDays = cycleDays,
        fullHistory = fullHistory,
        yearAgo = yearAgo,
        habits = habits,
    )

    @Test
    fun `a cycle rich in anomalies still makes room for the over-time cards`() {
        // Ranked purely by rupee impact, anomalies win every slot forever: a category swing is measured in
        // tens of thousands while a habit is measured in percentages. The carousel would then be built with
        // four new card types that are never once seen — which is the complaint this round exists to fix.
        val findings = InsightEngine.detect(
            busyCycle(
                daysElapsed = 12,
                cycleDays = 30,
                yearAgo = YearAgoWindow(60_000_00L, "July"),
                fullHistory = climbingDining,
                habits = paydayHeavy(),
            ),
        )
        assertEquals(InsightEngine.MAX_FINDINGS, findings.size)
        assertEquals("anomalies must not take every slot", 3, findings.count { it.kind in anomalyKinds })
        assertEquals("the over-time slots must be filled", 2, findings.count { it.kind in overTimeKinds })
        assertEquals(InsightKind.DUPLICATE_CHARGE, findings.first().kind)
        // Pace and year-on-year both answer "how does this cycle's total compare", so by materiality they
        // take BOTH over-time slots and the trend and habit cards never appear. One of them is the cap.
        assertEquals(
            "only one whole-cycle comparison may hold an over-time slot",
            1,
            findings.count { it.kind == InsightKind.PACE || it.kind == InsightKind.YEAR_ON_YEAR },
        )
        assertTrue("the second slot should say a different kind of thing", findings.any { it.kind == InsightKind.CATEGORY_TREND })
    }

    @Test
    fun `reserved slots are not left empty when there is nothing to put in them`() {
        // The mirror of the test above: a reservation that wasted slots would SHRINK the carousel for users
        // with no history, which would be a worse outcome than the ranking it replaced.
        val findings = InsightEngine.detect(busyCycle())
        assertEquals(InsightEngine.MAX_FINDINGS, findings.size)
        assertEquals(InsightEngine.MAX_FINDINGS, findings.count { it.kind in anomalyKinds })
    }
}
