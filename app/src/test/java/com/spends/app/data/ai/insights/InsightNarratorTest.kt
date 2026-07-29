package com.spends.app.data.ai.insights

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What leaves the phone, what it is CALLED, and what happens when the model doesn't play along.
 *
 * Two different guards live here and they matter for different reasons.
 *
 * The payload test is a privacy guard: it asserts the *absence* of merchant names, dates and
 * transaction-level detail. The narrator sees findings computed from rows containing all three, so
 * "we didn't include them" has to be enforced rather than assumed.
 *
 * The key-NAME tests are a truthfulness guard, and they exist because four review rounds each found another
 * field whose name over-claimed. The model is instructed to use only the numbers it is given, so a key name
 * is not a label — it is an assertion the app makes about the user's money. A baseline measured to day 12 of
 * earlier cycles must not be called "your usual"; a ratio of two percentages must not be called a multiple of
 * spending; a per-cycle median from months the user isn't looking at must not be called "amount".
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
    fun `the payload carries no merchant, no transaction date and no row identity`() {
        val json = InsightNarrator.buildUserPayload("Current Salary Cycle", findings)
        val lower = json.lowercase()
        // Distinctive tokens only — a substring like "id" would false-positive on an ordinary category name.
        listOf("merchant", "bookmyshow", "occurredat", "dayepoch", "last4", "dedupe", "rawbody", "sender",
            "balance", "2026", "-07-")
            .forEach { assertFalse("payload leaked '$it': $json", lower.contains(it)) }
        val root = JSONObject(json)
        // ⭐Review round 21. The PER-FINDING key set is pinned exhaustively by `every kind sends exactly
        // the keys it is supposed to and no others`, but the ROOT had nothing: a new top-level key — an
        // install id, a device model, a cycle start date — would have left all 113 AI-insights tests green.
        // The summary payload's root has been pinned since Phase A; this is its missing twin.
        assertEquals(setOf("cycleLabel", "findings"), root.keys().asSequence().toSet())
        assertEquals("Current Salary Cycle", root.getString("cycleLabel"))
        val first = root.getJSONArray("findings").getJSONObject(0)
        // Rupees, not paise — the model is told to quote these verbatim, so they must already be right.
        assertEquals(38050.0, first.getDouble("amountSoFarThisCycle"), 0.001)
        assertEquals(7400.0, first.getDouble("usualByThisPointAmount"), 0.001)
        assertEquals("Business Expenses", first.getString("category"))
    }

    @Test
    fun `a card sends only the fields its own kind uses`() {
        val json = JSONObject(InsightNarrator.buildUserPayload("This month", findings))
        val duplicate = json.getJSONArray("findings").getJSONObject(1)
        assertFalse(duplicate.has("usualByThisPointAmount"))
        assertFalse(duplicate.has("topCategoriesSharePercent"))
        // Ratios, counts and shares are still dropped when absent — only amounts are unconditional.
        assertFalse(duplicate.has("timesUsualByThisPoint"))
        assertEquals(2, duplicate.getInt("chargesCounted"))
    }

    @Test
    fun `a figure of zero is sent, because zero is an answer`() {
        // ⭐Suppressing it left a quiet win carrying ONLY its baseline — a figure from a different period,
        // and the sole number handed to a model told to use only the numbers it is given. It wrote
        // "Travel is at ₹9,000 this cycle, nicely down." The engine knew the answer was ₹0.
        val json = JSONObject(
            InsightNarrator.buildUserPayload(
                "Current Salary Cycle",
                listOf(InsightFinding(InsightKind.QUIET_WIN, "Travel", amountMinor = 0L, baselineMinor = 9_000_00L)),
            ),
        ).getJSONArray("findings").getJSONObject(0)

        assertEquals(0.0, json.getDouble("amountSoFarThisCycle"), 0.001)
        assertEquals(9000.0, json.getDouble("usualByThisPointAmount"), 0.001)
    }

    @Test
    fun `a baseline measured to today is never called simply the usual`() {
        // ⭐The deepest of the naming defects, and the last one found. Every day-aligned baseline — the
        // unusual/quiet cards, the movers, pace — is "what you'd usually have spent BY THIS POINT". On day 5
        // that is five days' worth. Sent as `usualAmount` with no day attached, a model writes "well above
        // your usual ₹7,400" to someone whose usual month is ₹25,000. The name has to carry the caveat,
        // because the number alone cannot.
        val json = JSONObject(
            InsightNarrator.buildUserPayload(
                "Current Salary Cycle",
                listOf(
                    InsightFinding(InsightKind.UNUSUAL_CATEGORY, "Business", 38_050_00L, 7_400_00L, multiple = 5.1),
                    InsightFinding(InsightKind.MOVER_UP, "Food", 30_000_00L, 9_000_00L),
                    InsightFinding(InsightKind.PACE, amountMinor = 24_100_00L, baselineMinor = 19_000_00L, days = 12),
                ),
            ),
        ).getJSONArray("findings")

        listOf(0, 1, 2).forEach { index ->
            assertFalse(
                "a by-this-point baseline must not be offered as a plain 'usual'",
                json.getJSONObject(index).has("usualAmount"),
            )
        }
        assertEquals(7400.0, json.getJSONObject(0).getDouble("usualByThisPointAmount"), 0.001)
        assertEquals(5.1, json.getJSONObject(0).getDouble("timesUsualByThisPoint"), 0.001)

        // A mover compares against ONE previous cycle, measured to the same day — two caveats, not one.
        val mover = json.getJSONObject(1)
        assertEquals(9000.0, mover.getDouble("lastCycleByThisPointAmount"), 0.001)
        assertFalse("last cycle is not 'your usual'", mover.has("usualByThisPointAmount"))

        assertEquals(12, json.getJSONObject(2).getInt("dayOfCycle"))
    }

    @Test
    fun `the ratio is never offered to the model as a multiple of usual spending`() {
        // ⭐The payday card's multiple is one percentage divided by another (38% of the money ÷ 23% of the
        // days = 1.7). Sent as `timesUsual`, an obedient model writes "about 1.7× your usual" about a card
        // that carries no amount at all and has no "usual" in it. It is now not sent, because both shares
        // are already there and the ratio adds nothing.
        val mixed = listOf(
            InsightFinding(InsightKind.HABIT_PAYDAY, sharePercent = 38, dayShare = 23, multiple = 1.7, count = 6),
            InsightFinding(
                // days is what drives cycleStillRunning — a year-on-year finding for a LIVE cycle carries it,
                // and the detector only zeroes it once the cycle has finished.
                kind = InsightKind.YEAR_ON_YEAR,
                amountMinor = 30_000_00L, baselineMinor = 20_000_00L, multiple = 1.5, days = 12, periodLabel = "July",
            ),
            InsightFinding(
                kind = InsightKind.CATEGORY_TREND,
                category = "Dining", amountMinor = 6_300_00L, baselineMinor = 4_500_00L, multiple = 1.4, spanCycles = 6,
            ),
            InsightFinding(
                kind = InsightKind.OUTLIER_CHARGE,
                category = "Fuel", amountMinor = 12_500_00L, baselineMinor = 2_000_00L, multiple = 6.3,
            ),
        )
        val json = JSONObject(InsightNarrator.buildUserPayload("Current Salary Cycle", mixed))
            .getJSONArray("findings")

        val habit = json.getJSONObject(0)
        listOf("timesUsual", "timesUsualByThisPoint", "timesLastYear", "timesEarlier")
            .forEach { assertFalse("two percentages divided by each other is not a spending multiple", habit.has(it)) }
        assertEquals(38, habit.getInt("paydayWeekShareOverEarlierCyclesPercent"))
        assertEquals(23, habit.getInt("shareOfCycleDaysPercent"))
        assertEquals(6, habit.getInt("cyclesMeasured"))
        assertFalse("a habit card is about shares, not an amount", habit.has("amountSoFarThisCycle"))

        val yoy = json.getJSONObject(1)
        assertEquals(1.5, yoy.getDouble("timesLastYear"), 0.001)
        assertEquals(30000.0, yoy.getDouble("amountThisStretch"), 0.001)
        assertTrue(yoy.getBoolean("cycleStillRunning"))
        assertFalse(yoy.has("timesUsual"))

        // Neither trend figure belongs to the cycle on screen: both are per-cycle medians over COMPLETED
        // earlier cycles. Sent as plain `amount` it read as "Dining is at ₹6,300 now" on a screen whose
        // donut showed Dining at ₹0.
        val trend = json.getJSONObject(2)
        assertEquals(6300.0, trend.getDouble("recentPerCycleAmount"), 0.001)
        assertEquals(4500.0, trend.getDouble("earlierPerCycleAmount"), 0.001)
        assertEquals(6, trend.getInt("cyclesCompared"))
        assertFalse("a per-cycle median is not this cycle's amount", trend.has("amountSoFarThisCycle"))

        // "usual" on an outlier means a typical single CHARGE, not a typical month.
        val outlier = json.getJSONObject(3)
        assertEquals(12500.0, outlier.getDouble("chargeAmount"), 0.001)
        assertEquals(2000.0, outlier.getDouble("typicalChargeAmount"), 0.001)
        assertEquals(6.3, outlier.getDouble("timesTypicalCharge"), 0.001)
        assertFalse(outlier.has("usualByThisPointAmount"))
    }

    @Test
    fun `a count and a share always say what they are counting`() {
        // `count` meant charges on one card and categories on another; `sharePercent` meant this cycle on one
        // and six earlier cycles on another. A model handed {"amount":10000,"sharePercent":94,"count":3}
        // writes "3 charges account for 94% of your spending — ₹10,000 of it": wrong noun, and ₹10,000 is the
        // top-three subtotal rather than the cycle's total.
        val json = JSONObject(
            InsightNarrator.buildUserPayload(
                "Current Salary Cycle",
                listOf(
                    InsightFinding(InsightKind.CONCENTRATION, amountMinor = 10_000_00L, sharePercent = 94, count = 3),
                    InsightFinding(InsightKind.DUPLICATE_CHARGE, "Entertainment", amountMinor = 1_240_00L, count = 2),
                ),
            ),
        ).getJSONArray("findings")

        val concentration = json.getJSONObject(0)
        assertEquals(3, concentration.getInt("categoriesCounted"))
        assertEquals(10000.0, concentration.getDouble("topCategoriesSubtotal"), 0.001)
        assertEquals(94, concentration.getInt("topCategoriesSharePercent"))
        assertFalse("the subtotal is not the cycle's total", concentration.has("amountSoFarThisCycle"))
        assertFalse(concentration.has("count"))

        val duplicate = json.getJSONObject(1)
        assertEquals(2, duplicate.getInt("chargesCounted"))
        assertEquals(1240.0, duplicate.getDouble("amountEachCharge"), 0.001)
        assertFalse(duplicate.has("count"))
    }

    @Test
    fun `well-formed cards are parsed in order`() {
        val cards = InsightNarrator.parseCards(
            """{"cards":[{"kind":"UNUSUAL_CATEGORY","category":"Business","title":"Business is up","body":"You spent a lot."},
               {"kind":"DUPLICATE_CHARGE","title":"Charged twice?","body":"Two the same."}]}""",
        )
        assertEquals(2, cards.size)
        assertEquals("Business is up", cards[0].title)
        assertEquals("UNUSUAL_CATEGORY", cards[0].kind)
        assertEquals("Business", cards[0].category)
        assertEquals("Two the same.", cards[1].body)
        assertNull(cards[1].category)
    }

    @Test
    fun `a card with no kind is still usable`() {
        // Validation is a guard against reordering, not a demand. If the model omits the echo entirely we
        // fall back to positional pairing rather than throwing away a perfectly good reply.
        val cards = InsightNarrator.parseCards("""{"cards":[{"title":"T","body":"B"}]}""")
        assertEquals(1, cards.size)
        assertNull(cards[0].kind)
        assertNull(cards[0].category)
    }

    // ---- pairing a reply back to the findings it claims to describe ----

    private val threeUnusual = listOf(
        InsightFinding(InsightKind.UNUSUAL_CATEGORY, "Fuel", 12_000_00L, 3_000_00L, multiple = 4.0),
        InsightFinding(InsightKind.UNUSUAL_CATEGORY, "Travel", 30_000_00L, 4_000_00L, multiple = 7.5),
        InsightFinding(InsightKind.UNUSUAL_CATEGORY, "Dining", 9_000_00L, 3_000_00L, multiple = 3.0),
    )

    private fun card(kind: String?, category: String?, title: String) =
        NarratedCard(kind, category, title, "Body for $title.")

    @Test
    fun `a scrambled reply falls back to templates instead of misattributing`() {
        // ⭐The failure this guard exists for. Three UNUSUAL_CATEGORY findings is an ORDINARY carousel, so a
        // kind-only check passes for every permutation of them — the model's Travel card would be rendered
        // against Fuel's slot, putting one category's heading over another category's numbers.
        val scrambled = listOf(
            card("UNUSUAL_CATEGORY", "Travel", "Travel is up"),
            card("UNUSUAL_CATEGORY", "Dining", "Dining is up"),
            card("UNUSUAL_CATEGORY", "Fuel", "Fuel is up"),
        )
        val paired = InsightNarrator.pair(threeUnusual, scrambled)
        assertEquals(3, paired.size)
        paired.forEachIndexed { index, result ->
            assertEquals(threeUnusual[index].fallbackTitle(), result.title)
            assertEquals(threeUnusual[index].fallbackBody(), result.body)
        }
    }

    @Test
    fun `a partial echo does not sneak a scramble through`() {
        // `kind` is trivial to echo — it is an enum literal sitting in the input. If echoing it alone were
        // enough, the scramble above would survive intact.
        val scrambledKindOnly = listOf(
            card("UNUSUAL_CATEGORY", null, "Travel is up"),
            card("UNUSUAL_CATEGORY", null, "Dining is up"),
            card("UNUSUAL_CATEGORY", null, "Fuel is up"),
        )
        InsightNarrator.pair(threeUnusual, scrambledKindOnly).forEachIndexed { index, result ->
            assertEquals(threeUnusual[index].fallbackTitle(), result.title)
        }
    }

    @Test
    fun `a correct reply is used, and a model that echoes nothing still pairs by position`() {
        val correct = threeUnusual.map { card("UNUSUAL_CATEGORY", it.category, "${it.category} is up") }
        assertEquals("Fuel is up", InsightNarrator.pair(threeUnusual, correct)[0].title)

        // Validation is a guard against a scrambled reply, not a demand that the model cooperate.
        val noEcho = threeUnusual.map { card(null, null, "${it.category} is up") }
        assertEquals("Travel is up", InsightNarrator.pair(threeUnusual, noEcho)[1].title)
    }

    @Test
    fun `a dropped card never shifts the ones after it`() {
        // The model returns two cards for three findings, omitting the middle one.
        val short = listOf(
            card("UNUSUAL_CATEGORY", "Fuel", "Fuel is up"),
            card("UNUSUAL_CATEGORY", "Dining", "Dining is up"),
        )
        val paired = InsightNarrator.pair(threeUnusual, short)
        assertEquals(3, paired.size)
        assertEquals("Fuel is up", paired[0].title)
        // Dining's card sits at index 1, where Travel's finding is — it must NOT be used there.
        assertEquals(threeUnusual[1].fallbackTitle(), paired[1].title)
        assertEquals(threeUnusual[2].fallbackTitle(), paired[2].title)
    }

    @Test
    fun `a card is taken whole or not at all`() {
        // A blank title with a good body would otherwise render the model's prose under a template heading.
        val blankTitle = listOf(NarratedCard("UNUSUAL_CATEGORY", "Fuel", "", "Fuel ran hot this cycle."))
        val paired = InsightNarrator.pair(listOf(threeUnusual[0]), blankTitle)
        assertEquals(threeUnusual[0].fallbackTitle(), paired[0].title)
        assertEquals(threeUnusual[0].fallbackBody(), paired[0].body)
        // ⭐Review round 20. The guard is `title.isNotBlank() && body.isNotBlank()`, and only the TITLE
        // half was fixtured: `&& it.body.isNotBlank()` could be deleted with all 113 tests still green,
        // after which a good heading renders over an empty card. Same defect on the other operand.
        val blankBody = listOf(NarratedCard("UNUSUAL_CATEGORY", "Fuel", "Fuel is up", ""))
        val pairedBlankBody = InsightNarrator.pair(listOf(threeUnusual[0]), blankBody)
        assertEquals(threeUnusual[0].fallbackTitle(), pairedBlankBody[0].title)
        assertEquals(threeUnusual[0].fallbackBody(), pairedBlankBody[0].body)
    }

    @Test
    fun `every finding gets exactly one card and none is dropped`() {
        listOf(emptyList(), threeUnusual.take(1), threeUnusual).forEach { subject ->
            val paired = InsightNarrator.pair(subject, emptyList())
            assertEquals(subject.size, paired.size)
            paired.forEachIndexed { index, result -> assertEquals(subject[index].kind, result.kind) }
        }
    }

    @Test
    fun `malformed replies fall back to nothing rather than garbage`() {
        assertTrue(InsightNarrator.parseCards("not json at all").isEmpty())
        assertTrue(InsightNarrator.parseCards("""{"unexpected":true}""").isEmpty())
        assertTrue(InsightNarrator.parseCards("").isEmpty())
    }

    @Test
    fun `every finding kind has usable wording without the AI`() {
        // A failed call must cost the prose, not the card. Each fallback has to be a real sentence carrying a
        // concrete figure. NOT "must contain ₹": the payday-habit card's whole story is two percentages, and
        // demanding a rupee sign of it would either red-build CI or push a meaningless amount into the copy.
        InsightKind.entries.filter { it != InsightKind.CYCLE_SUMMARY }.forEach { kind ->
            val finding = InsightFinding(
                kind = kind,
                category = "Food",
                amountMinor = 5_000_00L,
                baselineMinor = 2_000_00L,
                multiple = 2.5,
                sharePercent = 68,
                count = 2,
                days = 12,
                spanCycles = 6,
                dayShare = 23,
                periodLabel = "July",
            )
            assertTrue("$kind has no fallback title", finding.fallbackTitle().isNotBlank())
            val body = finding.fallbackBody()
            assertTrue("$kind has no fallback body", body.length > 20)
            assertTrue("$kind fallback states no figure", body.any { it.isDigit() })
            assertTrue("$kind fallback quotes neither rupees nor a share", body.contains("₹") || body.contains("%"))
        }
    }

    @Test
    fun `the year-on-year card still reads properly with no month to name`() {
        // periodLabel is always set by the detector. This pins the null branch anyway, because the failure
        // would be silent bad copy — "This  vs last " — rather than a crash anyone would notice.
        val finding = InsightFinding(
            kind = InsightKind.YEAR_ON_YEAR,
            amountMinor = 30_000_00L,
            baselineMinor = 20_000_00L,
            multiple = 1.5,
        )
        assertFalse("blank month left a gap in the title", finding.fallbackTitle().contains("  "))
        assertFalse("blank month left a gap in the body", finding.fallbackBody().contains("  "))
    }

    @Test
    fun `the over-time payload still carries nothing transaction-level`() {
        val json = InsightNarrator.buildUserPayload(
            "Current Salary Cycle",
            listOf(
                InsightFinding(InsightKind.YEAR_ON_YEAR, amountMinor = 1L, baselineMinor = 2L, periodLabel = "July"),
                InsightFinding(InsightKind.HABIT_PAYDAY, sharePercent = 38, dayShare = 23),
                InsightFinding(InsightKind.PACE, amountMinor = 1L, baselineMinor = 2L, days = 12),
            ),
        ).lowercase()
        // A month name is the one date-shaped value that may appear (owner decision, disclosed). A concrete
        // DAY never may — that is what would turn an aggregate into a record of when someone did something.
        listOf("merchant", "occurredat", "dayepoch", "last4", "dedupe", "rawbody", "sender", "balance", "2026", "-07-")
            .forEach { assertFalse("payload leaked '$it': $json", json.contains(it)) }
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

    // ---- Phase C: the judgement cards ----

    @Test
    fun `the judgement cards name every figure for what it actually is`() {
        val json = JSONObject(
            InsightNarrator.buildUserPayload(
                "Current Salary Cycle",
                listOf(
                    InsightFinding(
                        kind = InsightKind.COMMITMENTS,
                        amountMinor = 28_400_00L, baselineMinor = 95_000_00L, sharePercent = 30, count = 4,
                    ),
                    InsightFinding(
                        kind = InsightKind.SAVINGS_RATE,
                        amountMinor = 60_000_00L, sharePercent = 67, baselineSharePercent = 40, days = 12,
                    ),
                ),
            ),
        ).getJSONArray("findings")

        // ⭐"the monthly rules you set up", never "your fixed costs". A rent paid by card with no rule behind
        // it is not in this number, so the broader name would be the app asserting something it never
        // computed — the defect that recurred in all four Phase B review rounds.
        val commitments = json.getJSONObject(0)
        // ⭐The name has to carry "AlreadyStarted": rules the user set up to begin next month are excluded
        // from this sum, so a bare "monthlyRecurringRulesTotal" would assert more than the number contains.
        assertEquals(28400.0, commitments.getDouble("monthlyRulesAlreadyStartedTotalAmount"), 0.001)
        // ⭐⭐NOT "so far" and NOT "this cycle". The denominator is the median income of the user's
        // COMPLETED cycles, which is what makes the card independent of the day of the month. Seven review
        // rounds failed to make a part-month denominator honest before it was replaced; a key here saying
        // "ThisCycle" would hand the model back the exact falsehood that cost those rounds.
        assertEquals(95000.0, commitments.getDouble("usualIncomePerCycleAmount"), 0.001)
        assertEquals(30, commitments.getInt("shareOfUsualIncomePercent"))
        listOf("incomeSoFarThisCycleAmount", "shareOfIncomeSoFarPercent", "amountSoFarThisCycle")
            .forEach { assertFalse("a part-month name must not return: '$it'", commitments.has(it)) }
        assertEquals(4, commitments.getInt("monthlyRulesAlreadyStartedCounted"))
        listOf("fixedCosts", "fixedCostsAmount", "amountSoFarThisCycle", "usualByThisPointAmount", "incomeThisCycleAmount")
            .forEach { assertFalse("commitments must not claim '$it'", commitments.has(it)) }

        // Two SHARES, compared against each other. baselineMinor carries nothing on this card, so sending a
        // rupee baseline would hand a model told to use only the figures given a ₹0 to write about — the
        // same defect that once produced "Travel is at ₹9,000 this cycle, nicely down."
        val savingsRate = json.getJSONObject(1)
        assertEquals(60000.0, savingsRate.getDouble("keptSoFarAmount"), 0.001)
        assertEquals(67, savingsRate.getInt("keptSharePercent"))
        assertEquals(40, savingsRate.getInt("usualKeptShareByThisPointPercent"))
        assertEquals(12, savingsRate.getInt("dayOfCycle"))
        listOf("usualByThisPointAmount", "lastCycleByThisPointAmount", "typicalChargeAmount")
            .forEach { assertFalse("a share comparison carries no rupee baseline: '$it'", savingsRate.has(it)) }
    }

    @Test
    fun `a kept share of zero is still sent, because zero is an answer`() {
        // ⭐The same defect as the quiet-win card, on a different helper. SAVINGS_RATE is one of the TWO
        // kinds whose share can legitimately be 0 — COMMITMENTS is the other, pinned 76 lines below — so
        // routing it through the
        // `if (sharePercent > 0)` helper left the payload carrying `usualKeptShareByThisPointPercent` as its
        // ONLY percentage. A model told to use only the figures given then writes an earlier period's 40% as
        // this cycle's, on the very card whose whole story is the gap between two shares.
        val json = JSONObject(
            InsightNarrator.buildUserPayload(
                "Current Salary Cycle",
                listOf(
                    InsightFinding(
                        kind = InsightKind.SAVINGS_RATE,
                        amountMinor = 10_00L, sharePercent = 0, baselineSharePercent = 40, days = 12,
                    ),
                ),
            ),
        ).getJSONArray("findings").getJSONObject(0)

        assertEquals(0, json.getInt("keptSharePercent"))
        assertEquals(40, json.getInt("usualKeptShareByThisPointPercent"))
    }

    @Test
    fun `every kind sends exactly the keys it is supposed to and no others`() {
        // ⭐The standing guard against the defect that recurred in all four Phase B rounds and twice more in
        // Phase C: a key whose name is not true of its number. Pinning the EXACT key set per kind means a
        // rename, a stray extra field, or a generic `else` fallthrough in `putFigures` all fail here rather
        // than shipping as a confident false sentence.
        val expected = mapOf(
            InsightKind.UNUSUAL_CATEGORY to setOf("amountSoFarThisCycle", "usualByThisPointAmount", "timesUsualByThisPoint"),
            InsightKind.QUIET_WIN to setOf("amountSoFarThisCycle", "usualByThisPointAmount", "timesUsualByThisPoint"),
            InsightKind.PACE to setOf("amountSoFarThisCycle", "usualByThisPointAmount", "timesUsualByThisPoint", "dayOfCycle"),
            InsightKind.MOVER_UP to setOf("amountSoFarThisCycle", "lastCycleByThisPointAmount", "timesLastCycleByThisPoint"),
            InsightKind.MOVER_DOWN to setOf("amountSoFarThisCycle", "lastCycleByThisPointAmount", "timesLastCycleByThisPoint"),
            InsightKind.OUTLIER_CHARGE to setOf("chargeAmount", "typicalChargeAmount", "timesTypicalCharge"),
            InsightKind.DUPLICATE_CHARGE to setOf("amountEachCharge", "chargesCounted"),
            // `topCategories` is the one card whose subject could not be named from the fields it already
            // sent: the engine sorted the amounts and discarded the keys, so the card could only ever say
            // "your top 3 categories" and never which three.
            InsightKind.CONCENTRATION to setOf(
                "topCategoriesSubtotal", "categoriesCounted", "topCategoriesSharePercent", "topCategories",
            ),
            InsightKind.YEAR_ON_YEAR to setOf(
                "amountThisStretch", "sameStretchLastYearAmount", "timesLastYear", "dayOfCycle",
                "cycleStillRunning", "month",
            ),
            InsightKind.CATEGORY_TREND to setOf(
                "recentPerCycleAmount", "earlierPerCycleAmount", "timesEarlier", "cyclesCompared",
            ),
            InsightKind.HABIT_PAYDAY to setOf(
                "paydayWeekShareOverEarlierCyclesPercent", "shareOfCycleDaysPercent", "cyclesMeasured",
            ),
            InsightKind.COMMITMENTS to setOf(
                "monthlyRulesAlreadyStartedTotalAmount", "usualIncomePerCycleAmount",
                "shareOfUsualIncomePercent", "monthlyRulesAlreadyStartedCounted",
            ),
            InsightKind.SAVINGS_RATE to setOf(
                "keptSoFarAmount", "keptSharePercent", "usualKeptShareByThisPointPercent", "dayOfCycle",
            ),
        )

        // Every kind the engine can emit must be listed. A new kind added without a payload contract fails
        // HERE rather than reaching a user.
        val emitted = InsightKind.entries.filter { it != InsightKind.CYCLE_SUMMARY }.toSet()
        assertEquals("every engine-emitted kind needs a pinned key set", emitted, expected.keys)

        expected.forEach { (kind, keys) ->
            // Fully populated, so every optional field is present and the key set is maximal.
            val finding = InsightFinding(
                kind = kind, category = "Food", amountMinor = 5_000_00L, baselineMinor = 2_000_00L,
                multiple = 2.5, sharePercent = 47, count = 6, days = 12, spanCycles = 6, dayShare = 29,
                baselineSharePercent = 40, periodLabel = "July",
                // Populated for the same reason as every other optional field above: leaving it out would
                // have let a brand-new payload key escape this guard entirely while the test still claimed
                // the key set was maximal.
                topCategories = listOf("Food", "Rent", "Travel"),
            )
            val obj = JSONObject(InsightNarrator.buildUserPayload("Current Salary Cycle", listOf(finding)))
                .getJSONArray("findings").getJSONObject(0)
            val actual = obj.keys().asSequence().toSet() - setOf("kind", "category")
            assertEquals("$kind sends the wrong set of figures", keys, actual)
        }
    }

    @Test
    fun `a zero commitments share is still sent as a figure`() {
        // ⭐ZERO IS A FIGURE. ₹2,000 of commitments against a usual ₹5,00,000 rounds to 0%, and the
        // helper that normally writes this key drops anything falsy — while the card's own template still
        // renders "about 0% of the ₹5,00,000". A model told to use only the figures given would then be
        // handed a total with no share beside it. Same defect the savings-rate share was fixed for.
        val json = JSONObject(
            InsightNarrator.buildUserPayload(
                "Current Salary Cycle",
                listOf(
                    InsightFinding(
                        kind = InsightKind.COMMITMENTS,
                        amountMinor = 2_000_00L, baselineMinor = 500_000_00L, sharePercent = 0, count = 1,
                    ),
                ),
            ),
        ).getJSONArray("findings").getJSONObject(0)
        assertTrue("the share must be present even at zero", json.has("shareOfUsualIncomePercent"))
        assertEquals(0, json.getInt("shareOfUsualIncomePercent"))
    }

    @Test
    fun `the narrator prompt still forbids advice outright, with no known carve-out or dropped symbol left in it`() {
        // ⭐Phase C briefly carried one, for a savings-suggestion card. The owner dropped that card on
        // 2026-07-27 and the exception went with it. This test exists because a dangling permission is worse
        // than no permission: the kind it named no longer exists, so nothing would fail if it were left
        // behind, and the next card added would silently inherit a licence to advise.
        val system = InsightNarrator.SYSTEM
        // ⭐Review round 18. Verb alternation, negation KEPT — its twin in AiInsightsPayloadTest asserts
        // the same prohibition on a prompt that words it "Never give". Round 17 pinned the bare topic on
        // both, which "You may give financial advice" would have satisfied.
        assertTrue(
            "the base prohibition must survive, and must still be a prohibition",
            Regex("(never|do not|don['\u2019]t) give financial advice").containsMatchIn(system.lowercase()),
        )
        assertTrue("suggesting less spending must be forbidden outright", system.contains("Never suggest spending less"))
        // ⭐Review round 20. The THIRD prohibition, and the arithmetic clause behind it — neither was
        // asserted here, while the twin asserted the third on the summary prompt. Found because
        // `docs/AI-INSIGHTS-PLAN.md` claimed both tests pinned all three; the claim was false of this one.
        // Both clauses could be deleted from the prompt and all 113 tests stayed green. README and
        // PERMISSIONS_DECLARATION make the three-prohibition claim of EVERY card, so both prompts need
        // all three held, not one prompt and part of another.
        assertTrue(
            "proposing an action must be forbidden outright",
            system.lowercase().contains("never propose an action"),
        )
        assertTrue(
            "the arithmetic form of advice must stay forbidden",
            system.lowercase().contains("never say what fewer purchases would come to"),
        )
        // ⭐Round 18: the same tripwire list its twin carries. These were asymmetric — this test forbade
        // one phrasing, its twin four, and the twin's comment claimed to mirror this one. A carve-out
        // written as "…, except for a savings tip" passed here and was caught there. Known phrasings only;
        // this is not a proof that no carve-out exists.
        listOf("only exception", "one exception", "except for", "except when", "unless the")
            .forEach { assertFalse("a carve-out appeared in the prompt: $it", system.lowercase().contains(it)) }
        assertFalse("the dropped card must not be referenced", system.contains("SAVINGS_OPPORTUNITY"))
        // The named figure went with the card. A prompt clause about a key the payload can no longer contain
        // is instructions for a number the model will never see.
        assertFalse("a dropped payload key must not survive in the prompt", system.contains("estimatedSavingIfFewer"))
        // The prompt names payload keys literally; a renamed key must not leave the old name behind for a
        // model to look for and never find.
        assertFalse("the superseded commitments key must be gone", system.contains("monthlyRecurringRulesTotalAmount"))
    }

    @Test
    fun `the judgement payload still carries nothing transaction-level`() {
        val json = InsightNarrator.buildUserPayload(
            "Current Salary Cycle",
            listOf(
                InsightFinding(
                    kind = InsightKind.COMMITMENTS,
                    amountMinor = 28_400_00L, baselineMinor = 95_000_00L, sharePercent = 30, count = 4,
                ),
            ),
        ).lowercase()
        // Phase C sums commitments and income, but must still never name a transaction. "saturday" and
        // "sunday" stay in the list even though the weekend card was dropped in review round 11: they are
        // the cheapest possible guard against a future card re-introducing a day-of-week leak.
        listOf(
            "merchant", "occurredat", "dayepoch", "last4", "dedupe", "rawbody", "sender", "balance",
            "2026", "-07-", "saturday", "sunday", "swiggy",
        ).forEach { assertFalse("payload leaked '$it': $json", json.contains(it)) }
    }
}
