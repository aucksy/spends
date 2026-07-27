package com.spends.app.data.ai.insights

import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.RecurringRuleEntity
import com.spends.app.domain.model.RecurrenceFreq
import com.spends.app.domain.model.TxnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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
        // Phase C. All default to "absent", so every pre-existing fixture keeps producing exactly the
        // findings it did before and the new detectors cannot silently shift an older test's counts.
        commitments: CommitmentTotals? = null,
        savings: SavingsWindows? = null,
        priorFullCycleIncomeMinor: List<Long> = emptyList(),
        expenseMinor: Long? = null,
    ) = InsightInput(
        // Defaults to the categorised sum, which is what it is in production. Overridable so the
        // savings-rate same-basis gate can be tested with the two genuinely disagreeing.
        expenseMinor = expenseMinor ?: current.values.sum(),
        commitments = commitments,
        savings = savings,
        priorFullCycleIncomeMinor = priorFullCycleIncomeMinor,
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
    private val judgementKinds = setOf(
        InsightKind.COMMITMENTS, InsightKind.SAVINGS_RATE,
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
        // ⭐⭐Review round 20 replaced this fixture, which could not detect the bug it names. It was four
        // steady ₹10,000 windows plus two empty ones, current ₹10,000. `median` takes the LOWER middle, so
        // the real engine reads sorted[1] = ₹10,000 and the zero-substituting bug reads sorted[2] of
        // [0, 0, 10k, 10k, 10k, 10k] = ₹10,000 as well. Identical either way — the test was green under
        // the defect it was written to catch, and the old comment's "₹5,000" was arithmetically impossible
        // under any median rule. It survived nineteen review rounds.
        //
        // These values differ, so the two rules separate: real median = ₹10,000 (sorted[1] of
        // [9k, 10k, 11k, 12k]), zero-substituted = ₹9,000 (sorted[2] of [0, 0, 9k, 10k, 11k, 12k]). At
        // ₹19,000 this cycle sits just under 2× the honest usual and just over 2× the doctored one, so the
        // bug produces a card and the correct engine does not.
        val history = listOf(
            mapOf("Travel" to 12_000_00L),
            mapOf("Travel" to 11_000_00L),
            mapOf("Travel" to 10_000_00L),
            mapOf("Travel" to 9_000_00L),
        ) + List(2) { emptyMap<String, Long>() }
        val findings = InsightEngine.detect(input(current = mapOf("Travel" to 19_000_00L), history = history))
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

    // ⭐⭐Review round 21 found this card had exactly ONE test — the positive one above — so ALL FOUR
    // of its gates could be deleted with all 113 AI-insights tests still green. (Round 21 said three;
    // round 22 enumerated every fixture reaching this detector and found the fourth survived too.) It matters more than a
    // typical coverage gap: OUTLIER_CHARGE is one of only TWO kinds that send a single charge's amount off
    // the device, the one deliberate exception to the aggregates-only rule. An unguarded gate here spends
    // that exception on a non-finding. Each fixture below is sized so its own gate is the sole rejecter.

    @Test
    fun `a charge a little above the usual one is not a large charge`() {
        // ₹2,200 against a typical ₹2,000 clears the rupee floor and has plenty of history, so
        // OUTLIER_MULTIPLE is the only rule that can reject it. Deleted, the card reads
        // "a single Fuel charge of ₹2,200 — about 1.1× your usual ₹2,000".
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Fuel" to 2_200_00L),
                currentSlices = listOf(slice("Fuel", 2_200_00L, 20_000L, "shell")),
                historySlices = List(8) { slice("Fuel", 2_000_00L, 10_000L + it, null) },
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.OUTLIER_CHARGE })
    }

    @Test
    fun `one earlier charge is not enough to call anything typical`() {
        // A single prior charge would make "your usual" a median of one observation. The amount and the
        // ratio both clear their bars here (₹12,500 is 6.25× ₹2,000, which the card rounds to 6.3×), so
        // the history count is the only rule left to reject it.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Fuel" to 12_500_00L),
                currentSlices = listOf(slice("Fuel", 12_500_00L, 20_000L, "shell")),
                historySlices = listOf(slice("Fuel", 2_000_00L, 10_000L, null)),
            ),
        )
        assertTrue(
            "a usual measured over one charge is not a usual",
            findings.none { it.kind == InsightKind.OUTLIER_CHARGE },
        )
    }

    @Test
    fun `a small charge is not a large charge, however unusual it looks`() {
        // ₹500 against a typical ₹50 is 10× — the ratio and history bars both wave it through, so only
        // the absolute rupee floor stands between the user and being told a packet of biscuits is notable.
        // This is the Phase A lesson (a card must earn its place) in its original form.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Snacks" to 500_00L),
                currentSlices = listOf(slice("Snacks", 500_00L, 20_000L, "kirana")),
                historySlices = List(8) { slice("Snacks", 50_00L, 10_000L + it, null) },
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.OUTLIER_CHARGE })
    }

    @Test
    fun `a usual of zero is not something to be three times bigger than`() {
        // ⭐⭐The fourth gate, `typical <= 0L`. Round 22 added it after the two reviewers disagreed about
        // whether it could be isolated at all: it can, with prior charges of ₹0. It is not merely a
        // divide-by-zero guard — deleted, `multiple` becomes +∞, `Math.round` saturates to `Long.MAX_VALUE`
        // and `.toInt()` clamps, so the card reads "a single Fuel charge of ₹2,200 — about 2147483647× your
        // usual ₹0". This fixture stays silent under the other three mutations, so the diagonal is complete:
        // one fixture per gate, each the sole rejecter of its own.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Fuel" to 2_200_00L),
                currentSlices = listOf(slice("Fuel", 2_200_00L, 20_000L, "shell")),
                historySlices = List(3) { slice("Fuel", 0L, 10_000L + it, null) },
            ),
        )
        assertTrue(
            "nothing is a multiple of zero",
            findings.none { it.kind == InsightKind.OUTLIER_CHARGE },
        )
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
        // first baseline window — so the carousel would otherwise burn two of its six slots restating one
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

    // ---- how the six slots are shared out ----

    private fun busyCycle(
        daysElapsed: Int = 0,
        cycleDays: Int = 0,
        yearAgo: YearAgoWindow? = null,
        fullHistory: List<Map<String, Long>> = emptyList(),
        habits: HabitBuckets? = null,
    ) = input(
        // Business 5.1x, Travel 7.5x and Fuel 3.6x are all unusual; Entertainment has eased right off; and the
        // two identical Entertainment charges are a possible double-billing. Five anomalies, three slots' worth
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
        // Three RESERVED anomaly slots, but the backfill may hand a spare slot back to a fourth anomaly once
        // the other families have taken what they can use — that is the reservation working, not leaking.
        // What must never happen is anomalies taking the lot, which is what this actually pins.
        assertTrue(
            "anomalies must not take every slot",
            findings.count { it.kind in anomalyKinds } < findings.size,
        )
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
        //
        // `busyCycle()` on its own produces exactly five anomalies (three unusual, one quiet win, one
        // duplicate) and nothing else — one short of MAX_FINDINGS. So this also proves the reverse: the
        // reservations never pad the carousel to its cap with a card the cycle did not earn.
        val findings = InsightEngine.detect(busyCycle())
        assertEquals("every anomaly the cycle produced must be shown", 5, findings.size)
        assertEquals(
            "with no other family available, every slot goes to an anomaly",
            findings.size,
            findings.count { it.kind in anomalyKinds },
        )
        assertTrue("this fixture must stay under the cap for the assertion above to mean anything", findings.size < InsightEngine.MAX_FINDINGS)
    }

    // ---- Phase C: the judgement calls ----

    /**
     * A fixed "now" for the rule fixtures — the engine is pure, so the clock is an input like any other.
     *
     * Deliberately **09:00 on a fixed day in the DEVICE's own zone**, not a raw epoch constant. The boundary
     * that matters is a rule dated today whose `startDate` the date picker anchored at local NOON: it sits
     * hours in the future of a morning `now`, and an instant comparison drops it. Building both from
     * [DateUtils] keeps them on the same local day whatever zone the CI runner is in — a raw constant plus
     * "three hours" crosses midnight in the far-eastern zones and would fail there and nowhere else.
     */
    private val TODAY: LocalDate = LocalDate.of(2027, 1, 15)
    private val NOW = DateUtils.epochMillisFor(TODAY, 9, 0)

    private fun rule(
        amountMinor: Long,
        frequency: RecurrenceFreq = RecurrenceFreq.MONTHLY,
        kind: TxnKind = TxnKind.EXPENSE,
        active: Boolean = true,
        intervalCount: Int = 1,
        startDate: Long = 0L,
    ) = RecurringRuleEntity(
        amountMinor = amountMinor,
        kind = kind,
        categoryId = 1L,
        frequency = frequency,
        intervalCount = intervalCount,
        anchorDay = 1,
        startDate = startDate,
        nextRunAt = 0L,
        active = active,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `a yearly premium is never folded into a monthly commitment`() {
        // ⭐The rule most likely to be "improved" away by someone reasonable: dividing a ₹12,000 annual
        // premium into ₹1,000 a cycle looks like better data and is a figure the user has never paid.
        val totals = InsightsProvider.monthlyCommitments(
            listOf(
                rule(20_000_00L),
                rule(8_400_00L),
                rule(12_000_00L, frequency = RecurrenceFreq.YEARLY),
                rule(500_00L, frequency = RecurrenceFreq.WEEKLY),
                rule(9_000_00L, active = false),
                rule(50_000_00L, kind = TxnKind.INCOME),
                rule(3_000_00L, intervalCount = 2),
                // Set up today, first payment next month. Not money that is spoken for yet.
                rule(7_000_00L, startDate = DateUtils.epochMillisFor(TODAY.plusDays(1))),
                // ⭐Starting TODAY, written exactly as the date picker writes it — anchored at local NOON,
                // three hours AHEAD of this 09:00 `now`. Compared as instants it would be dropped, and the
                // card would omit a commitment the user set up an hour ago, then include it after lunch.
                rule(6_000_00L, startDate = DateUtils.epochMillisFor(TODAY)),
            ),
            now = NOW,
        )
        assertEquals(
            "the two running rules plus the one starting today, but not the one starting next month",
            34_400_00L,
            totals?.monthlyMinor,
        )
        assertEquals(3, totals?.ruleCount)
    }

    @Test
    fun `no monthly rule means no commitments figure at all`() {
        assertNull(
            InsightsProvider.monthlyCommitments(
                listOf(rule(12_000_00L, frequency = RecurrenceFreq.YEARLY), rule(500_00L, frequency = RecurrenceFreq.WEEKLY)),
                now = NOW,
            ),
        )
    }

    /** Prior windows carrying ₹90,000 of income by this point — the savings-rate baseline. */
    private fun steadyIncome(currentIncome: Long, currentExpense: Long = 20_000_00L) = SavingsWindows(
        current = SavingsWindow(incomeMinor = currentIncome, expenseMinor = currentExpense),
        prior = List(4) { SavingsWindow(incomeMinor = 90_000_00L, expenseMinor = 54_000_00L) },
    )

    /** Prior COMPLETE cycles' income — the commitments card's DENOMINATOR since 2026-07-27. */
    private fun fullCycleIncome(amount: Long = 90_000_00L, count: Int = 4) = List(count) { amount }

    private val FREELANCE_CYCLES =
        listOf(30_000_00L, 35_000_00L, 40_000_00L, 150_000_00L, 160_000_00L, 180_000_00L)
    private val ONE_ODD_MONTH =
        listOf(15_000_00L, 90_000_00L, 90_000_00L, 90_000_00L, 90_000_00L, 90_000_00L)

    @Test
    fun `commitments are stated against what a cycle usually brings in`() {
        // ⭐⭐The denominator is the median of COMPLETED cycles, not income so far — owner decision after
        // seven review rounds failed to make a part-month denominator honest. Both figures are stable: the
        // card reads the same on day 3 and day 28, which is exactly the property that removed four gates.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Rent" to 20_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                commitments = CommitmentTotals(monthlyMinor = 28_400_00L, ruleCount = 4),
                priorFullCycleIncomeMinor = fullCycleIncome(),
            ),
        )
        assertTrue("the commitments card must fire", findings.any { it.kind == InsightKind.COMMITMENTS })
        val card = findings.single { it.kind == InsightKind.COMMITMENTS }
        assertEquals(28_400_00L, card.amountMinor)
        assertEquals("the baseline is the usual full cycle, not this one", 90_000_00L, card.baselineMinor)
        assertEquals(32, card.sharePercent)
        assertEquals(4, card.count)
        assertTrue(
            "the sentence must not claim anything about this cycle",
            card.fallbackBody().contains("usually comes in each cycle"),
        )
    }

    @Test
    fun `the commitments card reads the same on any day of the cycle`() {
        // ⭐The property the rewrite bought, pinned. Neither figure depends on the day, so a card built on
        // day 3 and one built on day 28 are identical — no decay, no timing artefact, and no need for the
        // day floor, arrived bar, arrival gate or wholeCycleComparable that the old
        // part-month denominator required. Re-introduce any dependence on `daysElapsed` and this goes red.
        fun cardOn(day: Int, complete: Boolean, comparable: Boolean) = InsightEngine.detect(
            input(
                current = mapOf("Rent" to 20_000_00L),
                daysElapsed = day,
                cycleDays = 30,
                cycleComplete = complete,
                wholeCycleComparable = comparable,
                commitments = CommitmentTotals(28_400_00L, 4),
                priorFullCycleIncomeMinor = fullCycleIncome(),
            ),
        ).singleOrNull { it.kind == InsightKind.COMMITMENTS }

        val early = cardOn(3, complete = false, comparable = true)
        assertTrue("it must fire on day 3", early != null)
        assertEquals("day 28 must be byte-identical to day 3", early, cardOn(28, complete = false, comparable = true))
        assertEquals("nothing here is compared against on-screen totals", early, cardOn(12, complete = false, comparable = false))
        // ⭐The one thing that DOES suppress it, and it is an epoch gate rather than a timing one: the
        // numerator is today's rule set while the denominator is the cycles before whichever cycle is on
        // screen, so browsing back would assert today's commitments against someone else's history.
        assertNull("a cycle browsed back to mixes today's rules with older income", cardOn(30, complete = true, comparable = true))
    }

    @Test
    fun `a job loss is never described as a usual income`() {
        // ⭐⭐⭐The round-8 BLOCKER, and the worst sentence this card has ever been capable of.
        //
        // The ₹5,000 floor used to FILTER the prior cycles before the median was taken — so someone who
        // lost their job three cycles ago, whose three most recent complete cycles logged ₹0, had those
        // cycles removed from the reckoning and was told their ₹28,400 of standing commitments was
        // "about 32% of the ₹90,000 that usually comes in each cycle". Money they no longer earn, presented
        // as their usual income, on a card about what is already spoken for.
        //
        // It is the same self-selecting-baseline defect this project already had to fix on the savings rate,
        // where dropping the cycles someone overspent made "the share you'd usually keep" the median of only
        // their good months. The floor now TRIMS trailing pre-install buckets and nothing else; a lean or
        // empty cycle inside the span stays in, because it is exactly what makes "usually" untrue.
        //
        // The list is newest-first, so the zeros here are the RECENT cycles.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Rent" to 20_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                commitments = CommitmentTotals(28_400_00L, 4),
                priorFullCycleIncomeMinor = listOf(0L, 0L, 0L, 90_000_00L, 90_000_00L, 90_000_00L),
            ),
        )
        assertTrue(
            "an income that stopped is not a usual income",
            findings.none { it.kind == InsightKind.COMMITMENTS },
        )
    }

    @Test
    fun `lean months count towards the usual, they are not filtered out of it`() {
        // ⭐The same blocker in its quieter form. A seasonal earner's lean cycles run ₹4,000 — under the
        // floor — and the filtered version dropped them, then announced ₹28,400 of commitments as "32% of
        // the ₹90,000 that usually comes in". In half of this user's cycles those commitments are seven
        // times that cycle's entire income.
        //
        // ⭐Note how narrow the old cliff was: move the lean months from ₹4,000 to ₹5,000 and the filtered
        // version started behaving correctly. A ₹1,000 difference decided whether the card lied — the same
        // shape of threshold cliff round 4 rejected, inverted and pointing the dangerous way.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Rent" to 20_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                commitments = CommitmentTotals(28_400_00L, 4),
                priorFullCycleIncomeMinor = listOf(
                    4_000_00L, 4_000_00L, 4_000_00L, 90_000_00L, 90_000_00L, 90_000_00L,
                ),
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.COMMITMENTS })
    }

    @Test
    fun `a lean season in the older half is not pre-install data`() {
        // ⭐⭐The round-9 finding, and the only fixture that pins the trim PREDICATE rather than its effect.
        //
        // The trim drops trailing buckets so that cycles from before the app existed do not drag the median
        // down. It must drop **exactly zero** and nothing else: a ₹4,000 cycle is real logged income, and
        // "it is old and small, therefore it is pre-install" is a claim the numbers cannot support.
        //
        // Trimming everything sub-floor also SHORTENED the list enough to move the lower-middle median onto
        // a good cycle — so whether a seasonal earner got a false card came down to a phase offset. These
        // are the same six cycles as `lean months count towards the usual`, reordered: that one was silenced
        // either way, this one was not. Revert `== 0L` to `< COMMITMENTS_MIN_INCOME_MINOR` and the card
        // fires, telling someone whose commitments are 710% of a lean cycle's income that they are "about
        // 32% of the ₹90,000 that usually comes in each cycle".
        //
        // Every other committed fixture behaves identically under both rules, which is precisely why this
        // one had to be written from an enumeration rather than found by the suite.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Rent" to 20_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                commitments = CommitmentTotals(28_400_00L, 4),
                priorFullCycleIncomeMinor = listOf(
                    90_000_00L, 90_000_00L, 90_000_00L, 4_000_00L, 4_000_00L, 4_000_00L,
                ),
            ),
        )
        assertTrue(
            "a lean cycle is data; only an all-zero bucket is absence of it",
            findings.none { it.kind == InsightKind.COMMITMENTS },
        )
    }

    @Test
    fun `cycles from before the app was installed do not count as lean months`() {
        // The other side of the trim, and the reason it is `dropLastWhile` rather than a blanket rejection.
        // The list is newest-first and always six long, so a user three months in has THREE trailing zeros
        // meaning "Spends did not exist yet", not "no income". Those are dropped; the three real cycles
        // remain and the card is correct.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Rent" to 20_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                commitments = CommitmentTotals(28_400_00L, 4),
                priorFullCycleIncomeMinor = listOf(90_000_00L, 90_000_00L, 90_000_00L, 0L, 0L, 0L),
            ),
        )
        assertTrue("absence of data is not absence of income", findings.any { it.kind == InsightKind.COMMITMENTS })
        assertEquals(90_000_00L, findings.single { it.kind == InsightKind.COMMITMENTS }.baselineMinor)
    }

    @Test
    fun `one month with no logged income does not cost the card`() {
        // The counterweight to the two tests above. Trimming only the TRAILING buckets, and judging the
        // median rather than every element, means one forgotten salary in the middle of the span leaves the
        // median untouched. A stricter "every cycle must clear the floor" rule was tried and rejected for
        // killing this case, which is entirely ordinary.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Rent" to 20_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                commitments = CommitmentTotals(28_400_00L, 4),
                priorFullCycleIncomeMinor = listOf(
                    90_000_00L, 0L, 90_000_00L, 90_000_00L, 90_000_00L, 90_000_00L,
                ),
            ),
        )
        assertTrue(findings.any { it.kind == InsightKind.COMMITMENTS })
    }

    @Test
    fun `a variable income has no usual month to be a share of`() {
        // ⭐A freelancer's full cycles run [₹30k, ₹35k, ₹40k, ₹1.5L, ₹1.6L, ₹1.8L]. There is no typical
        // month here: the median is ₹40,000 while the best cycle is ₹1,80,000 — 4.5x it, against a bar of
        // 1.5x — so "what usually comes in" would be a fiction whichever cycle you picked.
        //
        // The spike rule is the SOLE rejecter: delete it and the card fires, announcing ₹30,000 of
        // commitments as **75% of a "usual" ₹40,000** for someone whose good cycles are ₹1.5L and up.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Rent" to 20_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                commitments = CommitmentTotals(30_000_00L, 4),
                priorFullCycleIncomeMinor = FREELANCE_CYCLES,
            ),
        )
        assertTrue(
            "a best cycle 4.5x the typical one means there is no usual month",
            findings.none { it.kind == InsightKind.COMMITMENTS },
        )
    }

    @Test
    fun `the spike bar sits exactly at one and a half times the median`() {
        // ⭐The 1.5x bar had NO test pinning its value: review round 12 found every setting from 0% to 349%
        // satisfied both committed fixtures identically, because one sits at 4.5x and the other at 1.0x.
        // Twenty-eight lines of KDoc justify exactly 50, and nothing held it there.
        //
        // These two fixtures differ by ₹1,000. The median of both is ₹1,00,000, so the bar is ₹1,50,000:
        // a best cycle AT the bar passes (the test is `>`), and one rupee-thousand over it fails.
        fun cardFor(best: Long) = InsightEngine.detect(
            input(
                current = mapOf("Rent" to 20_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                commitments = CommitmentTotals(28_400_00L, 4),
                priorFullCycleIncomeMinor = listOf(100_000_00L, 100_000_00L, best),
            ),
        ).singleOrNull { it.kind == InsightKind.COMMITMENTS }

        assertTrue("a best cycle exactly 1.5x the median is still a usual month", cardFor(150_000_00L) != null)
        assertNull("a whisker over 1.5x is not", cardFor(151_000_00L))
    }

    @Test
    fun `one odd month does not cost an ordinary salaried user the commitments card`() {
        // ⭐⭐The other half of the round-4 finding, and the reason the gate measures the best cycle against
        // the MEDIAN rather than against the worst. A flat ₹90,000 salary with ONE month logged at ₹15,000 —
        // a mid-cycle join, a salary that arrived as two credits, a month someone forgot to confirm — is a
        // 6x best-to-worst spread and was silenced outright. Measured against the median it is untouched:
        // a low outlier cannot move the median, so there plainly still IS a usual month.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Rent" to 20_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                commitments = CommitmentTotals(28_400_00L, 4),
                priorFullCycleIncomeMinor = ONE_ODD_MONTH,
            ),
        )
        assertTrue(
            "one odd month is not variable income",
            findings.any { it.kind == InsightKind.COMMITMENTS },
        )
    }

    @Test
    fun `commitments larger than a usual cycle's income are not reported as a share of it`() {
        // Pins the over-100% rule. Everything else passes — four rules, a flat ₹90,000 usual cycle, no
        // spike — so only the fact that the commitments EXCEED that usual income stops the card.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Rent" to 20_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                commitments = CommitmentTotals(120_000_00L, 5),
                priorFullCycleIncomeMinor = fullCycleIncome(),
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.COMMITMENTS })
    }

    @Test
    fun `pocket-money cycles cannot become the usual income`() {
        // ⭐The ₹5,000 floor on the MEDIAN, sized to pin only that. Four cycles of ₹4,000–₹5,000 leave a
        // median of ₹4,000: no spike (the best is 1.25x the median), and ₹3,000 of commitments sits under
        // it — so with the floor deleted the card fires and announces those commitments as **75% of "what
        // usually comes in"**, against a usual income that is not an income at all.
        //
        // Two earlier versions of this fixture were shadowed: four cycles of ₹1 against ₹4,900 of
        // commitments was rejected by the over-100% rule whether the floor existed or not, and a version
        // ending in ZERO cycles would be trimmed before the median ever saw it. Trailing sub-floor cycles
        // that are not zero are NOT trimmed — that is the point of `a lean season in the older half`.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Rent" to 3_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                commitments = CommitmentTotals(3_000_00L, 3),
                priorFullCycleIncomeMinor = listOf(4_000_00L, 4_000_00L, 5_000_00L, 5_000_00L),
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.COMMITMENTS })
    }

    @Test
    fun `the savings rate is compared against the same point in earlier cycles`() {
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 30_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                savings = SavingsWindows(
                    current = SavingsWindow(incomeMinor = 90_000_00L, expenseMinor = 30_000_00L),
                    prior = List(4) { SavingsWindow(incomeMinor = 90_000_00L, expenseMinor = 54_000_00L) },
                ),
            ),
        )
        assertTrue("the savings-rate card must fire", findings.any { it.kind == InsightKind.SAVINGS_RATE })
        val card = findings.single { it.kind == InsightKind.SAVINGS_RATE }
        assertEquals(60_000_00L, card.amountMinor)
        assertEquals(67, card.sharePercent)
        assertEquals("the baseline must be a share an earlier cycle really reached", 40, card.baselineSharePercent)
        assertEquals(12, card.days)
        assertTrue(
            "a rate above its baseline must never render as being behind it",
            card.fallbackBody().contains("ahead of"),
        )
    }

    @Test
    fun `the savings rate says nothing when more went out than came in`() {
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 95_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                savings = SavingsWindows(
                    current = SavingsWindow(90_000_00L, 95_000_00L),
                    prior = List(4) { SavingsWindow(90_000_00L, 54_000_00L) },
                ),
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.SAVINGS_RATE })
    }

    @Test
    fun `a savings rate in line with usual is not a finding`() {
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 52_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                savings = SavingsWindows(
                    current = SavingsWindow(90_000_00L, 52_000_00L),
                    prior = List(4) { SavingsWindow(90_000_00L, 54_000_00L) },
                ),
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.SAVINGS_RATE })
    }

    @Test
    fun `overspent cycles stay in the usual kept share`() {
        // ⭐The round-1 defect, pinned. Real kept-shares [50, 45, 40, -20, -30, -10] have a true middle of
        // -10%: this user usually overspends by this point. Filtering the negatives out — which the first
        // version did — produced a baseline of 45% and reported a cycle at +35%, far BETTER than their
        // usual, as "behind" it. With the honest median the baseline is negative and the card says nothing.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 58_500_00L),
                daysElapsed = 12,
                cycleDays = 30,
                savings = SavingsWindows(
                    current = SavingsWindow(90_000_00L, 58_500_00L), // kept 35%
                    prior = listOf(
                        SavingsWindow(90_000_00L, 45_000_00L), // +50%
                        SavingsWindow(90_000_00L, 49_500_00L), // +45%
                        SavingsWindow(90_000_00L, 54_000_00L), // +40%
                        SavingsWindow(90_000_00L, 108_000_00L), // -20%
                        SavingsWindow(90_000_00L, 117_000_00L), // -30%
                        SavingsWindow(90_000_00L, 99_000_00L), // -10%
                    ),
                ),
            ),
        )
        assertTrue(
            "a cycle better than this user's real median must never be reported as behind it",
            findings.none { it.kind == InsightKind.SAVINGS_RATE },
        )
    }

    @Test
    fun `there is no usual kept share when the two middle cycles are far apart`() {
        // Shares [60, 62, 58, 5, 8, 10] have no middle worth quoting: the lower-middle rule lands on 10%
        // and the card would say "ahead of usual" about a cycle that is behind the real centre.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 67_500_00L),
                daysElapsed = 12,
                cycleDays = 30,
                savings = SavingsWindows(
                    current = SavingsWindow(90_000_00L, 67_500_00L), // kept 25%
                    prior = listOf(
                        SavingsWindow(90_000_00L, 36_000_00L), // +60%
                        SavingsWindow(90_000_00L, 34_200_00L), // +62%
                        SavingsWindow(90_000_00L, 37_800_00L), // +58%
                        SavingsWindow(90_000_00L, 85_500_00L), // +5%
                        SavingsWindow(90_000_00L, 82_800_00L), // +8%
                        SavingsWindow(90_000_00L, 81_000_00L), // +10%
                    ),
                ),
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.SAVINGS_RATE })
    }

    @Test
    fun `lean income windows count towards the usual kept share, they are not filtered out`() {
        // ⭐⭐⭐The round-12 finding, and the same self-selecting-baseline defect the commitments card was
        // forced to fix in round 8 — sitting in this card the whole time.
        //
        // The prior-window filter dropped any window with under ₹5,000 of income. The justification written
        // above it covers an EMPTY window ("the salary wasn't logged"); it does not cover a window carrying
        // ₹2,000 of genuinely logged commission. A commission earner whose day-aligned shares run
        // [50, 40, 40, −733, −1000, −500] had the three lean windows deleted, and was told
        // *"you've kept ₹9,000 — 20%, behind the 40% you'd usually have kept by this point"* — against a
        // baseline drawn only from their good windows. Their honest median is −500%, and `baselineShare <= 0`
        // would have silenced the card outright, except that the baseline was doctored before that gate
        // could see it.
        //
        // Revert the predicate to `>= SAVINGS_MIN_INCOME_MINOR` and this goes green, which is the point.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 36_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                savings = SavingsWindows(
                    current = SavingsWindow(45_000_00L, 36_000_00L), // kept 20%
                    prior = listOf(
                        SavingsWindow(40_000_00L, 20_000_00L), // +50%
                        SavingsWindow(35_000_00L, 21_000_00L), // +40%
                        SavingsWindow(30_000_00L, 18_000_00L), // +40%
                        SavingsWindow(3_000_00L, 25_000_00L), // -733%
                        SavingsWindow(2_000_00L, 22_000_00L), // -1000%
                        SavingsWindow(4_000_00L, 24_000_00L), // -500%
                    ),
                ),
            ),
        )
        assertTrue(
            "a lean window is logged income, not absence of it — it must count towards \"usually\"",
            findings.none { it.kind == InsightKind.SAVINGS_RATE },
        )
    }

    @Test
    fun `a user who usually overspends is told nothing rather than something bleak`() {
        // ⭐Pins `baselineShare <= 0` on a set where NOTHING else could stop the card. The overspent-cycles
        // test above reaches the same rule now that the ordering has been fixed, but its middles are 50
        // points apart, so it would also be caught by the ambiguity gate if the two were ever reordered
        // again. These shares are tightly clustered (middles one point apart) and all negative, so this rule
        // is provably the only one that can fire — without it the user reads "you've kept 20%, ahead of the
        // -8% you'd usually have kept by this point."
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 72_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                savings = SavingsWindows(
                    current = SavingsWindow(90_000_00L, 72_000_00L), // kept 20%
                    prior = listOf(
                        SavingsWindow(90_000_00L, 94_500_00L), // -5%
                        SavingsWindow(90_000_00L, 97_200_00L), // -8%
                        SavingsWindow(90_000_00L, 99_000_00L), // -10%
                        SavingsWindow(90_000_00L, 95_400_00L), // -6%
                        SavingsWindow(90_000_00L, 98_100_00L), // -9%
                        SavingsWindow(90_000_00L, 96_300_00L), // -7%
                    ),
                ),
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.SAVINGS_RATE })
    }

    @Test
    fun `the savings rate says nothing in the opening days of a cycle`() {
        // ⭐The day floor, which had no test of its own. On day 3 one rent charge swings the share by tens
        // of points on BOTH sides of the comparison, so the figure decays out from under itself. Everything
        // else here is the passing fixture from the happy-path test above — only `daysElapsed` differs, so
        // deleting the floor turns this red.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 30_000_00L),
                daysElapsed = 3,
                cycleDays = 30,
                savings = SavingsWindows(
                    current = SavingsWindow(incomeMinor = 90_000_00L, expenseMinor = 30_000_00L),
                    prior = List(4) { SavingsWindow(incomeMinor = 90_000_00L, expenseMinor = 54_000_00L) },
                ),
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.SAVINGS_RATE })
    }

    @Test
    fun `the savings rate says nothing when the on-screen totals and the history disagree`() {
        // ⭐`wholeCycleComparable`, which also had no test. Under the card-billing-aware Smart Cycle the
        // current expense comes from reconciled on-screen state while the prior windows are bucketed from
        // raw transaction dates, so the two sides of the share are measured on different boundaries. Same
        // fixture as the happy path; only the flag moves.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 30_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                wholeCycleComparable = false,
                savings = SavingsWindows(
                    current = SavingsWindow(incomeMinor = 90_000_00L, expenseMinor = 30_000_00L),
                    prior = List(4) { SavingsWindow(incomeMinor = 90_000_00L, expenseMinor = 54_000_00L) },
                ),
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.SAVINGS_RATE })
    }

    @Test
    fun `an ambiguous middle is not a usual share, even when the range is narrow`() {
        // ⭐⭐The round-3 finding, pinned. Shares [15, 17, 19, 40, 42, 44] span 29 points, so the old
        // min-to-max bar of 40 waved them through — yet the two middles are 19 and 40, and `medianInt`
        // taking the lower one is what decides the headline. The baseline reads 19% while the real centre is
        // near 30%, so this cycle at 25% is announced as "ahead of usual" when it is behind it.
        //
        // Sized so only the ambiguity gate can stop it: the baseline of 19% is positive, so the negative
        // rule cannot fire, and this cycle is set to 30% — an 11-point gap that clears the 8-point
        // difference bar with room to spare. Delete the ambiguity gate and the card appears, saying "ahead".
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 63_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                savings = SavingsWindows(
                    current = SavingsWindow(90_000_00L, 63_000_00L), // kept 30%
                    prior = listOf(
                        SavingsWindow(90_000_00L, 76_500_00L), // +15%
                        SavingsWindow(90_000_00L, 74_700_00L), // +17%
                        SavingsWindow(90_000_00L, 72_900_00L), // +19%
                        SavingsWindow(90_000_00L, 54_000_00L), // +40%
                        SavingsWindow(90_000_00L, 52_200_00L), // +42%
                        SavingsWindow(90_000_00L, 50_400_00L), // +44%
                    ),
                ),
            ),
        )
        assertTrue(
            "a 21-point gap between the two middles means the median is a coin toss",
            findings.none { it.kind == InsightKind.SAVINGS_RATE },
        )
    }

    @Test
    fun `a lumpy but consistent saver still gets a card`() {
        // ⭐The other half of the round-3 finding: the old min-to-max bar was too EXPENSIVE. Shares
        // [20, 35, 38, 41, 45, 70] span 50 points and were silenced outright, though the two middles are 38
        // and 41 — three points apart — so the median of 38% is an entirely honest "usual". One splurge
        // cycle and one windfall cycle must not cost this user the card for the other four.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 45_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                savings = SavingsWindows(
                    current = SavingsWindow(90_000_00L, 45_000_00L), // kept 50%
                    prior = listOf(
                        SavingsWindow(90_000_00L, 72_000_00L), // +20%
                        SavingsWindow(90_000_00L, 58_500_00L), // +35%
                        SavingsWindow(90_000_00L, 55_800_00L), // +38%
                        SavingsWindow(90_000_00L, 53_100_00L), // +41%
                        SavingsWindow(90_000_00L, 49_500_00L), // +45%
                        SavingsWindow(90_000_00L, 27_000_00L), // +70%
                    ),
                ),
            ),
        )
        assertTrue("a wide range with a firm middle still has a usual", findings.any { it.kind == InsightKind.SAVINGS_RATE })
        assertEquals(38, findings.single { it.kind == InsightKind.SAVINGS_RATE }.baselineSharePercent)
    }

    @Test
    fun `the ambiguity bar sits exactly at eight points`() {
        // ⭐⭐Review round 14. Both gates above were pinned against DELETION, but the value 8 itself was
        // free: 5, 7, 9 and 12 all left every test green while changing who gets a card. That is the same
        // unpinned-constant defect round 12 found on the commitments spike bar — fixed there, missed here.
        //
        // These two fixtures bracket the ambiguity role from both sides. Middles 30 and 38 are exactly 8
        // apart, which the gate admits (`> 8`, not `>= 8`); 30 and 39 are 9 apart, which it rejects. Lower
        // the constant and the first goes silent; raise it and the second fires.
        fun ambiguity(upperMiddleExpense: Long) = InsightEngine.detect(
            input(
                current = mapOf("Food" to 45_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                savings = SavingsWindows(
                    current = SavingsWindow(90_000_00L, 45_000_00L), // kept 50%
                    prior = List(3) { SavingsWindow(90_000_00L, 63_000_00L) } + // +30%
                        List(3) { SavingsWindow(90_000_00L, upperMiddleExpense) },
                ),
            ),
        )
        assertTrue(
            "middles exactly 8 points apart are not ambiguous — the bar is `> 8`, not `>= 8`",
            ambiguity(55_800_00L).any { it.kind == InsightKind.SAVINGS_RATE }, // +38%
        )
        assertTrue(
            "middles 9 points apart are ambiguous and must silence the card",
            ambiguity(54_900_00L).none { it.kind == InsightKind.SAVINGS_RATE }, // +39%
        )
    }

    @Test
    fun `the difference bar sits exactly at eight points`() {
        // ⭐⭐The other role the same constant plays, bracketed the same way. A cycle 8 points off its
        // baseline is worth a sentence; 7 points is noise. Priors are identical here, so the ambiguity gap
        // is 0 and this bar is provably the only rule that can decide either case.
        fun difference(thisCycleExpense: Long) = InsightEngine.detect(
            input(
                current = mapOf("Food" to thisCycleExpense),
                daysElapsed = 12,
                cycleDays = 30,
                savings = SavingsWindows(
                    current = SavingsWindow(90_000_00L, thisCycleExpense),
                    prior = List(6) { SavingsWindow(90_000_00L, 54_000_00L) }, // all +40%
                ),
            ),
        )
        assertTrue(
            "8 points clear of the usual is a real difference",
            difference(46_800_00L).any { it.kind == InsightKind.SAVINGS_RATE }, // kept 48%
        )
        assertTrue(
            "7 points is not worth a card",
            difference(47_700_00L).none { it.kind == InsightKind.SAVINGS_RATE }, // kept 47%
        )
    }

    @Test
    fun `a cycle with no spending yet says nothing about commitments either`() {
        // ⭐Review round 14. `detect()` returns early on `expenseMinor <= 0`, which also silences
        // COMMITMENTS — a card that reads no expense figure at all. That is deliberate (see the comment on
        // the gate), but `an empty cycle produces nothing` passes with the gate deleted, so nothing pinned
        // it: every commitments gate below is satisfied here, and without the early return this user is
        // told "…about 32% of the ₹90,000 that usually comes in" on the morning they were paid.
        val findings = InsightEngine.detect(
            input(
                current = emptyMap(),
                daysElapsed = 1,
                cycleDays = 30,
                commitments = CommitmentTotals(28_400_00L, 4),
                priorFullCycleIncomeMinor = List(6) { 90_000_00L },
            ),
        )
        assertTrue(
            "a spending carousel says nothing on a day with no spending on it",
            findings.none { it.kind == InsightKind.COMMITMENTS },
        )
    }

    @Test
    fun `medianInt returns a share some cycle actually reached`() {
        // The rule is duplicated per type precisely so it cannot drift. On an even list it must take the
        // LOWER middle, never the average — 45 is a share nobody had.
        assertEquals(40, InsightEngine.medianInt(listOf(40, 50)))
        assertEquals(40, InsightEngine.medianInt(listOf(50, 40, 60, 40)))
        assertEquals(0, InsightEngine.medianInt(emptyList()))
    }

    @Test
    fun `the savings rate says nothing about a cycle that has already ended`() {
        // Stepping back with the prev arrow keeps the range on CURRENT, so a finished cycle really does
        // reach the engine. Every figure on both of these asserts "so far".
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 30_000_00L),
                daysElapsed = 30,
                cycleDays = 30,
                cycleComplete = true,
                commitments = CommitmentTotals(28_400_00L, 4),
                savings = steadyIncome(95_000_00L, currentExpense = 30_000_00L),
                priorFullCycleIncomeMinor = fullCycleIncome(),
                currentSlices = List(12) { slice("Dining out", 500_00L, 20_000L + it, "swiggy") },
            ),
        )
        // Both judgement cards are silent here, for DIFFERENT reasons, and the distinction matters enough
        // to assert separately. The savings rate is about a cycle in progress, so a finished one has nothing
        // for it to say. Commitments is timeless in its figures but is suppressed by the epoch gate: its
        // numerator is today's rule set, so showing it over a past cycle's income would mix two eras. See
        // `the commitments card reads the same on any day of the cycle`.
        assertTrue(findings.none { it.kind == InsightKind.SAVINGS_RATE })
        assertTrue(findings.none { it.kind == InsightKind.COMMITMENTS })
    }

    @Test
    fun `a small standing load is not worth a commitments card`() {
        // ⭐The ₹2,000 commitments floor, which had no test of its own. Everything else passes: income
        // ₹90,000 against a flat ₹90,000 prior, day 12, four rules. Only the floor stops ₹1,000 of standing
        // payments being announced as 1% of income.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Rent" to 20_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                commitments = CommitmentTotals(1_000_00L, 4),
                savings = steadyIncome(90_000_00L),
                priorFullCycleIncomeMinor = fullCycleIncome(),
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.COMMITMENTS })
    }

    @Test
    fun `two prior cycles are not enough history to call an income usual`() {
        // ⭐The prior-cycle COUNT. Two complete cycles at ₹90,000 each is not a distribution — a median of
        // two is whichever one is smaller, and the spike gate would be judged against it. Only the count
        // stops this, and deleting it makes the card fire.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Rent" to 20_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                commitments = CommitmentTotals(28_400_00L, 4),
                savings = steadyIncome(95_000_00L),
                priorFullCycleIncomeMinor = listOf(90_000_00L, 90_000_00L),
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.COMMITMENTS })
    }

    @Test
    fun `a savings rate on pocket money is not a savings rate`() {
        // ⭐The ₹5,000 floor on THIS cycle's income, which had no test — only its twin on the prior windows
        // did. ₹4,000 in and ₹1,000 out is a kept-share of 75% against a usual 40%, clearing every other
        // gate, and "you've kept ₹3,000 — 75%, ahead of usual" is a card about nothing.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 1_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                savings = SavingsWindows(
                    current = SavingsWindow(4_000_00L, 1_000_00L),
                    prior = List(4) { SavingsWindow(90_000_00L, 54_000_00L) },
                ),
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.SAVINGS_RATE })
    }

    @Test
    fun `two prior windows are not enough to call a kept share usual`() {
        // ⭐The prior-WINDOW count on the savings rate, which had no test of its own. The third window here
        // carries no income at all, so it is dropped and only two remain — and a "usual" drawn from two
        // cycles is whichever was worse.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 30_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                savings = SavingsWindows(
                    current = SavingsWindow(90_000_00L, 30_000_00L),
                    prior = listOf(
                        SavingsWindow(90_000_00L, 54_000_00L),
                        SavingsWindow(90_000_00L, 54_000_00L),
                        SavingsWindow(0L, 12_000_00L),
                    ),
                ),
            ),
        )
        assertTrue(findings.none { it.kind == InsightKind.SAVINGS_RATE })
    }

    @Test
    fun `uncategorised spending is never counted as money kept`() {
        // ⭐⭐The round-4 defect, and the gate that had no test until round 5 caught that too. `kept` is
        // income MINUS expense, but income is the screen's headline total while the expense side is the
        // CATEGORISED total — the only basis the prior windows can be built from. With ₹10,000 of
        // uncategorised spend the card would read "You've kept ₹60,000 — 67%" when the honest figures are
        // ₹50,000 and 56%: money the user does not have.
        //
        // This is the happy-path fixture with ONE change — the headline total raised to ₹40,000 while the
        // categorised total stays ₹30,000. Delete the same-basis gate and this goes green.
        val findings = InsightEngine.detect(
            input(
                current = mapOf("Food" to 30_000_00L),
                daysElapsed = 12,
                cycleDays = 30,
                expenseMinor = 40_000_00L,
                savings = SavingsWindows(
                    current = SavingsWindow(incomeMinor = 90_000_00L, expenseMinor = 30_000_00L),
                    prior = List(4) { SavingsWindow(incomeMinor = 90_000_00L, expenseMinor = 54_000_00L) },
                ),
            ),
        )
        assertTrue(
            "a kept figure built from two different bases must never be shown",
            findings.none { it.kind == InsightKind.SAVINGS_RATE },
        )
    }

    @Test
    fun `the savings rate never doubles up with another whole-cycle card`() {
        val base = input(
            current = mapOf("Food" to 60_000_00L),
            history = List(6) { mapOf("Food" to 20_000_00L) },
            daysElapsed = 12,
            cycleDays = 30,
            savings = SavingsWindows(
                current = SavingsWindow(90_000_00L, 60_000_00L),
                prior = List(4) { SavingsWindow(90_000_00L, 20_000_00L) },
            ),
        )
        // Live on its own, so the cap below is guarding something real. Dropping the history kills pace
        // (no windows to build a baseline from) and leaves the savings rate standing.
        val savingsAlone = InsightEngine.detect(base.copy(history = emptyList()))
        assertTrue("the savings rate must fire on its own", savingsAlone.any { it.kind == InsightKind.SAVINGS_RATE })

        val findings = InsightEngine.detect(base)
        assertTrue("pace must fire in this fixture", findings.any { it.kind == InsightKind.PACE })
        assertEquals(
            "pace and the savings rate both describe this cycle's total",
            1,
            findings.count { it.kind == InsightKind.PACE || it.kind == InsightKind.SAVINGS_RATE },
        )
    }

    @Test
    fun `a judgement card keeps its seat in a cycle full of anomalies`() {
        // The mirror of the over-time slot test, for the new family. Without a reservation the four
        // anomalies out-punch a judgement card on materiality every time — a category swing is tens of
        // thousands of rupees — and Phase C would have been built and never seen.
        val findings = InsightEngine.detect(
            busyCycle().copy(
                commitments = CommitmentTotals(28_400_00L, 4),
                savings = steadyIncome(95_000_00L, currentExpense = 86_030_00L),
                priorFullCycleIncomeMinor = fullCycleIncome(),
                daysElapsed = 12,
                cycleDays = 30,
            ),
        )
        assertTrue(
            "anomalies must not crowd out the judgement family",
            findings.any { it.kind in judgementKinds },
        )
        assertTrue("the over-time family must still get a seat", findings.any { it.kind in overTimeKinds })
        assertTrue("and anomalies must still lead", findings.count { it.kind in anomalyKinds } >= 3)
        assertEquals(InsightEngine.MAX_FINDINGS, findings.size)
    }
}
