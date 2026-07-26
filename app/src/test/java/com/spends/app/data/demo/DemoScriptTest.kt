package com.spends.app.data.demo

import com.spends.app.data.seed.CategorySeed
import com.spends.app.domain.model.TxnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * Guards on the demo account [DemoScript] generates.
 *
 * Two kinds of assertion here, and the second kind is the interesting one:
 *
 *  1. **Structural** — money conserved across splits, nothing dated in the future, every category name real.
 *     A break here would show up as visibly wrong data.
 *  2. **The scripted stories are actually present** — the anomaly really is anomalous, the quiet win really is
 *     quieter, the duplicate pair really is a duplicate. These matter because a drift in the generator would
 *     leave demo mode silently *claiming* to demonstrate anomaly detection while giving it nothing to find,
 *     and nobody would notice until it fell flat in front of an audience.
 *
 * Every case runs over several fixed dates — including a 31st and a February — so month-end clamping is
 * exercised. [DemoScript] is deterministic, so these can never be flaky.
 */
class DemoScriptTest {

    private val dates = listOf(
        LocalDate.of(2026, 1, 15),
        LocalDate.of(2026, 3, 31),
        LocalDate.of(2026, 7, 26),
        LocalDate.of(2026, 12, 5),
        LocalDate.of(2028, 2, 29), // leap day
    )

    private fun live(data: DemoDataSet) = data.txns.filter { it.deletedDaysAgo == null }

    @Test
    fun `every transaction's slices add up to its amount`() {
        for (today in dates) {
            for (txn in DemoScript.build(today).txns) {
                assertEquals(
                    "allocations must sum to the transaction amount on $today",
                    txn.amountMinor,
                    txn.allocations.sumOf { it.amountMinor },
                )
            }
        }
    }

    @Test
    fun `amounts are positive whole paise`() {
        for (today in dates) {
            for (txn in DemoScript.build(today).txns) {
                assertTrue("amount must be positive", txn.amountMinor > 0)
                txn.allocations.forEach { assertTrue("slice must be positive", it.amountMinor > 0) }
            }
        }
    }

    @Test
    fun `nothing is dated in the future`() {
        for (today in dates) {
            val data = DemoScript.build(today)
            data.txns.forEach {
                assertTrue("txn on ${it.date} is after $today", !it.date.isAfter(today))
            }
            data.pending.forEach { assertTrue("pending capture cannot be in the future", it.daysAgo >= 0) }
        }
    }

    @Test
    fun `history spans the full window so year-on-year has something to compare`() {
        for (today in dates) {
            val data = DemoScript.build(today)
            val oldest = data.txns.minOf { it.date }
            val expectedOldestMonth = YearMonth.from(today)
                .minusMonths((DemoScript.RICH_MONTHS + DemoScript.BASELINE_MONTHS - 1).toLong())
            assertEquals(
                "history must start at the oldest scripted month on $today",
                expectedOldestMonth,
                YearMonth.from(oldest),
            )
            // The same calendar month a year ago must hold data, or a year-on-year card has nothing to say.
            val lastYear = YearMonth.from(today).minusYears(1)
            assertTrue(
                "no data in $lastYear to compare against",
                data.txns.any { YearMonth.from(it.date) == lastYear },
            )
        }
    }

    @Test
    fun `the same day always produces the same account`() {
        val today = LocalDate.of(2026, 7, 26)
        val a = DemoScript.build(today)
        val b = DemoScript.build(today)
        assertEquals(a.txns, b.txns)
        assertEquals(a.pending, b.pending)
    }

    @Test
    fun `every category named actually exists`() {
        val known = (CategorySeed.allRows().map { it.name } +
            DemoScript.CUSTOM_EXPENSE_CATEGORIES +
            DemoScript.ARCHIVED_CATEGORY).toSet()
        for (today in dates) {
            val data = DemoScript.build(today)
            data.txns.flatMap { it.allocations }.map { it.categoryName }.distinct().forEach {
                assertTrue("transaction category '$it' is not a real category", it in known)
            }
            data.pending.forEach { assertTrue("pending category '${it.categoryName}' is not real", it.categoryName in known) }
            data.recurring.forEach { assertTrue("recurring category '${it.categoryName}' is not real", it.categoryName in known) }
            data.learned.forEach { assertTrue("learned category '${it.categoryName}' is not real", it.categoryName in known) }
        }
    }

    @Test
    fun `review queue rows are distinguishable so none is silently dropped`() {
        // pending_captures has a UNIQUE index on dedupeHash, and the seeder derives that hash from
        // (merchant, amount, daysAgo). Two rows agreeing on all three would be silently IGNORED on insert
        // and the review queue would come up short.
        val keys = DemoScript.build(LocalDate.of(2026, 7, 26)).pending.map {
            Triple(it.merchant, it.amountMinor, it.daysAgo)
        }
        assertEquals("review-queue rows must be unique", keys.size, keys.distinct().size)
    }

    @Test
    fun `the anomaly category really is anomalous right now`() {
        // Measured over a rolling recent window rather than "this calendar month", because the demo has to
        // hold up when it is run on the 3rd of a month as readily as on the 28th.
        for (today in dates) {
            val data = live(DemoScript.build(today))
            val recent = data.filter { !it.date.isBefore(today.minusDays(13)) }
                .flatMap { it.allocations }
                .filter { it.categoryName == DemoScript.ANOMALY_CATEGORY }
                .sumOf { it.amountMinor }
            // The same-length window, averaged over the preceding six months.
            val baselineWindows = (1..6).map { back ->
                val end = today.minusDays((back * 14).toLong())
                data.filter { !it.date.isBefore(end.minusDays(13)) && !it.date.isAfter(end) }
                    .flatMap { it.allocations }
                    .filter { it.categoryName == DemoScript.ANOMALY_CATEGORY }
                    .sumOf { it.amountMinor }
            }
            val baseline = baselineWindows.average()
            assertTrue("baseline for ${DemoScript.ANOMALY_CATEGORY} must be non-zero on $today", baseline > 0)
            assertTrue(
                "on $today ${DemoScript.ANOMALY_CATEGORY} was $recent over 14 days vs a $baseline norm — not obviously unusual",
                recent > baseline * 2.0,
            )
        }
    }

    @Test
    fun `the quiet-win category really has eased off`() {
        for (today in dates) {
            val data = live(DemoScript.build(today))
            val recent = meanAmount(data, today, 0 until DemoScript.RICH_MONTHS, DemoScript.QUIET_WIN_CATEGORY)
            val earlier = meanAmount(data, today, 4..9, DemoScript.QUIET_WIN_CATEGORY)
            assertTrue("no earlier ${DemoScript.QUIET_WIN_CATEGORY} data on $today", earlier > 0)
            assertTrue(
                "on $today ${DemoScript.QUIET_WIN_CATEGORY} averaged $recent recently vs $earlier before — not a visible drop",
                recent < earlier * 0.70,
            )
        }
    }

    @Test
    fun `the trending category really is climbing`() {
        // Compares mean amount per transaction, not monthly totals, so a partially-elapsed current month
        // can't skew it.
        for (today in dates) {
            val data = live(DemoScript.build(today))
            val now = meanAmount(data, today, 0..2, DemoScript.TREND_CATEGORY)
            val sixMonthsBack = meanAmount(data, today, 6..11, DemoScript.TREND_CATEGORY)
            assertTrue("no ${DemoScript.TREND_CATEGORY} history on $today", sixMonthsBack > 0)
            assertTrue(
                "on $today ${DemoScript.TREND_CATEGORY} averaged $now now vs $sixMonthsBack six months back — no visible climb",
                now > sixMonthsBack * 1.15,
            )
        }
    }

    @Test
    fun `monthly transaction volume stays flat so trends are real signals`() {
        // If recent months simply carried more rows than old ones, every category would read as "increasing"
        // and the genuine anomaly would be indistinguishable from the artefact. Compare only whole months.
        val today = LocalDate.of(2026, 7, 26)
        val data = live(DemoScript.build(today)).filter { it.recurringKey == null }
        val counts = (1..12).map { back ->
            val month = YearMonth.from(today).minusMonths(back.toLong())
            data.count { YearMonth.from(it.date) == month }
        }
        val mean = counts.average()
        counts.forEachIndexed { i, c ->
            assertTrue(
                "month -${i + 1} has $c transactions against a $mean average — volume is not flat",
                c > mean * 0.7 && c < mean * 1.3,
            )
        }
    }

    @Test
    fun `a single outlier charge and a duplicate pair are always present`() {
        for (today in dates) {
            val data = live(DemoScript.build(today))
            // The outlier is sized against the plan's CEILING, not its median, so this holds for every date
            // and every seed rather than depending on where a 34-sample median happens to land.
            val fuel = data.filter { txn -> txn.allocations.any { it.categoryName == DemoScript.ANOMALY_ONE_OFF_CATEGORY } }
            val biggest = fuel.maxOf { it.amountMinor }
            val organic = fuel.map { it.amountMinor }.sorted().dropLast(1)
            assertTrue(
                "on $today the biggest ${DemoScript.ANOMALY_ONE_OFF_CATEGORY} charge ($biggest) is not 3x every ordinary one (max ${organic.last()})",
                biggest >= organic.last() * 3,
            )

            val duplicates = data
                .filter { it.merchant == DemoScript.DUPLICATE_MERCHANT }
                .groupBy { it.date to it.amountMinor }
                .filterValues { it.size >= 2 }
            assertTrue("no duplicate-charge pair planted on $today", duplicates.isNotEmpty())
        }
    }

    @Test
    fun `recurring rules only ever step forward and never run ahead of today`() {
        for (today in dates) {
            val data = DemoScript.build(today)
            for (rule in data.recurring) {
                var date = DemoScript.firstOccurrence(rule, today)
                var guard = 0
                while (!date.isAfter(today)) {
                    val next = DemoScript.stepRecurring(date, rule)
                    assertTrue("${rule.key} stepped backwards from $date to $next", next.isAfter(date))
                    date = next
                    assertTrue("${rule.key} did not terminate", ++guard < 1000)
                }
                // Every replayed occurrence must be on or before today; the first future one becomes nextRunAt.
                assertTrue("${rule.key} first future run must be after $today", date.isAfter(today))
            }
            data.txns.filter { it.recurringKey != null }.forEach {
                assertTrue("recurring occurrence ${it.date} is in the future", !it.date.isAfter(today))
            }
        }
    }

    @Test
    fun `income and expenses both exist in the current cycle`() {
        for (today in dates) {
            val data = live(DemoScript.build(today))
            val month = YearMonth.from(today)
            assertTrue("no expenses this month on $today", data.any { YearMonth.from(it.date) == month && it.kind == TxnKind.EXPENSE })
            // The current month's salary only exists once the salary day has passed, so look back a month too.
            val window = listOf(month, month.minusMonths(1))
            assertTrue(
                "no income in the last two months on $today",
                data.any { YearMonth.from(it.date) in window && it.kind == TxnKind.INCOME },
            )
        }
    }

    /** Mean slice amount for [category] across the months [range] back from [today]. 0 when there are none. */
    private fun meanAmount(txns: List<DemoTxn>, today: LocalDate, range: Iterable<Int>, category: String): Double {
        val months = range.map { YearMonth.from(today).minusMonths(it.toLong()) }.toSet()
        val slices = txns.filter { YearMonth.from(it.date) in months }
            .flatMap { it.allocations }
            .filter { it.categoryName == category }
            .map { it.amountMinor }
        return if (slices.isEmpty()) 0.0 else slices.average()
    }
}
