package com.spends.app.data.demo

import com.spends.app.core.time.RecurrenceMath
import com.spends.app.domain.model.RecurrenceFreq
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.random.Random

/** The instruments the demo account spends through. Resolved to real `payment_methods` rows by the seeder. */
enum class DemoInstrument { CARD_HDFC, CARD_AXIS, BANK_ICICI, UPI_GPAY }

/** One category slice of a demo transaction. A single-category transaction has exactly one. */
data class DemoAllocation(val categoryName: String, val amountMinor: Long)

/**
 * A generated transaction, in plain terms. Category and instrument are named, not id'd — the seeder resolves
 * them once the rows exist. [recurringKey] links a materialised occurrence back to the rule that "created" it.
 */
data class DemoTxn(
    val amountMinor: Long,
    val date: LocalDate,
    val hour: Int,
    val minute: Int,
    val kind: TxnKind,
    val merchant: String?,
    val note: String?,
    val source: TxnSource,
    val instrument: DemoInstrument?,
    val allocations: List<DemoAllocation>,
    val recurringKey: String? = null,
    /** Non-null → the row sits in Trash, deleted this many days ago (demonstrates restore + auto-purge). */
    val deletedDaysAgo: Int? = null,
)

/** A row waiting in the review queue, exactly as a real capture would look. */
data class DemoPendingCapture(
    val amountMinor: Long,
    val kind: TxnKind,
    val daysAgo: Int,
    val merchant: String?,
    val last4: String?,
    val institution: String?,
    val categoryName: String,
    val parseConfidence: Int,
    val rawBody: String,
    val sender: String,
    /** null = came from SMS; a package name = came from that app's notification. */
    val sourceApp: String?,
)

/** A recurring rule, plus enough detail to replay its history. */
data class DemoRecurring(
    val key: String,
    val amountMinor: Long,
    val kind: TxnKind,
    val categoryName: String,
    val merchant: String,
    val note: String?,
    val freq: RecurrenceFreq,
    val anchorDay: Int,
    val startMonthsAgo: Int,
    val instrument: DemoInstrument?,
)

/** A remembered merchant → category (and note) shortcut, as if the user had taught it. */
data class DemoLearnedMerchant(val merchant: String, val categoryName: String, val note: String? = null)

/** Everything demo mode needs to write. */
data class DemoDataSet(
    val txns: List<DemoTxn>,
    val pending: List<DemoPendingCapture>,
    val recurring: List<DemoRecurring>,
    val learned: List<DemoLearnedMerchant>,
)

/**
 * Builds the demo account's data.
 *
 * **Pure and deterministic**: given the same `today` it produces byte-identical output, so "Reset demo data"
 * is idempotent and a unit test can assert its invariants without a device. Nothing here touches Room, Android
 * or the clock — the caller passes `today` in.
 *
 * ## What the shape is designed to demonstrate
 * The data is not random noise; it is scripted so that every feature — and every planned AI insight — has
 * something real to say about it:
 *
 * | Woven-in pattern                                            | What it lets you demo                     |
 * |-------------------------------------------------------------|-------------------------------------------|
 * | A burst of [ANOMALY_CATEGORY] charges in the last few days   | "unusual spending" / anomaly detection    |
 * | One [ANOMALY_ONE_OFF_CATEGORY] charge at ~3× the usual       | "today's fuel is 3× your average"         |
 * | A same-day same-amount pair at [DUPLICATE_MERCHANT]          | duplicate-charge detection                |
 * | [TREND_CATEGORY] drifting up ~50% across the last 6 months   | category trend analysis                   |
 * | Last year's equivalent months ~15% pricier                   | year-on-year comparison                   |
 * | Spending ~32% heavier in the week after payday               | habit discovery                           |
 * | Discretionary categories ~22% heavier at weekends            | habit discovery                           |
 * | [QUIET_WIN_CATEGORY] well below its own norm recently        | "quiet wins" / spending less than usual   |
 * | Rent, EMI, SIP, subscriptions as recurring rules             | commitments / needs-vs-wants              |
 *
 * Note that transaction *volume* is flat across the whole span on purpose — see [UNIFORM_MONTHLY_VOLUME].
 * The recent [RICH_MONTHS] months are richer in kind, not in quantity: that is where the splits, the trash,
 * the review queue, the mixed capture sources and the planted stories live.
 */
object DemoScript {

    /** Fixed so a reset reproduces the same account for a given day. */
    private const val SEED = 20260726L

    /** The recent window that carries the scripted texture and the carry-forward anchor (incl. this month). */
    const val RICH_MONTHS = 3

    /** Months of plain history behind it — the baseline every comparison measures against. */
    const val BASELINE_MONTHS = 11

    /** The demo account's salary day. Distinct from 1 so salary cycles look different from calendar months. */
    const val SALARY_DAY = 25

    // Custom categories the demo adds on top of the seeded set (so category management has something to show).
    const val ANOMALY_CATEGORY = "Business Expenses"
    const val CAT_PET_CARE = "Pet Care"
    const val ARCHIVED_CATEGORY = "Old Gym Membership"

    const val QUIET_WIN_CATEGORY = "Entertainment"
    const val ANOMALY_ONE_OFF_CATEGORY = "Fuel"
    const val TREND_CATEGORY = "Food"
    const val DUPLICATE_MERCHANT = "BookMyShow"

    val CUSTOM_EXPENSE_CATEGORIES = listOf(ANOMALY_CATEGORY, CAT_PET_CARE)

    // ---- the scripted magnitudes, named so the doc table above stays truthful ----
    // These are sized to be unmistakable, not subtle. A 10% drift is realistic but reads as noise in a demo
    // (and in a unit test); a story worth showing has to be visible at a glance.
    private const val QUIET_WIN_FACTOR = 0.35
    private const val TREND_TOTAL_DRIFT = 0.50
    private const val YOY_FACTOR = 1.15
    private const val PAYDAY_FACTOR = 1.32
    private const val WEEKEND_FACTOR = 1.22

    /**
     * ⚠ Transaction **volume is deliberately uniform across every month**, and must stay that way.
     *
     * The obvious way to build this data would be "recent months dense, older months sparse". That quietly
     * ruins it: if the last three months carry ~1.8× the transactions of the baseline, then *every* category
     * shows an ~80% jump three months ago, every trend card fires at once, and the one genuine anomaly is
     * lost in the noise. The baseline only earns its keep if it is comparable.
     *
     * Recency is expressed through *scripted texture* instead — splits, trash, the review queue, mixed
     * capture sources, the planted anomaly and duplicate — not through more rows.
     */
    private const val UNIFORM_MONTHLY_VOLUME = 1.0

    // The planted anomaly: a burst of charges in one category over the last few days, sized so it stands out
    // against that category's own history no matter what day the demo runs. Scripted rather than derived from
    // a monthly multiplier, because early in a month a multiplier has almost no data to act on.
    // Day offsets are explicit rather than computed — a divided-by-count spacing silently re-spaces the whole
    // burst the moment someone adds a fifth amount.
    private val ANOMALY_BURST = listOf(
        1L to 12_400_00L,
        3L to 9_850_00L,
        5L to 8_200_00L,
        7L to 7_600_00L,
    )

    private data class Plan(
        val category: String,
        val perMonth: Double,
        val minRupees: Int,
        val maxRupees: Int,
        val merchants: List<String>,
        /** Discretionary categories get the weekend lift — essentials don't. */
        val discretionary: Boolean = false,
    )

    private val plans: List<Plan> = listOf(
        Plan("Food", 9.0, 180, 1400, listOf("Swiggy", "Zomato", "Domino's Pizza", "Chaayos", "Third Wave Coffee", "Haldiram's", "Behrouz Biryani", "Starbucks India"), discretionary = true),
        Plan("Groceries", 5.0, 450, 3800, listOf("BigBasket", "Blinkit", "DMart", "Zepto", "Reliance Fresh")),
        Plan("Transport", 8.0, 80, 640, listOf("Uber", "Ola Cabs", "Rapido", "Namma Yatri", "Delhi Metro")),
        Plan("Shopping", 3.0, 600, 7500, listOf("Amazon", "Flipkart", "Myntra", "Ajio", "Croma", "Decathlon"), discretionary = true),
        Plan("Entertainment", 3.5, 250, 1600, listOf("BookMyShow", "PVR Cinemas", "Prime Video", "Hotstar"), discretionary = true),
        // Band deliberately kept tight (a typical fill, not a road trip) so the scripted outlier below stands
        // clear of it. A wide band would put the median close enough to the outlier that "3× your usual"
        // stops being true on some dates — which is exactly what the outlier exists to demonstrate.
        Plan("Fuel", 2.5, 900, 2600, listOf("Indian Oil", "HP Petrol Pump", "Bharat Petroleum", "Shell")),
        Plan("Utilities", 2.0, 620, 3400, listOf("Tata Power", "Adani Electricity", "Mahanagar Gas", "ACT Fibernet")),
        Plan("Bills", 1.5, 299, 1299, listOf("Airtel Postpaid", "Jio Recharge", "Vi Postpaid")),
        Plan("Health", 1.2, 220, 2600, listOf("Apollo Pharmacy", "Tata 1mg", "Practo", "Dr Lal PathLabs")),
        Plan("Personal Care", 1.4, 320, 2400, listOf("Nykaa", "Urban Company", "Enrich Salon")),
        Plan("Subscriptions", 1.2, 149, 999, listOf("Spotify Premium", "YouTube Premium", "iCloud", "Adobe Creative Cloud")),
        Plan("Travel", 0.8, 1800, 19000, listOf("MakeMyTrip", "IRCTC", "IndiGo", "Oyo Rooms"), discretionary = true),
        Plan("Education", 0.5, 500, 4200, listOf("Coursera", "Udemy", "Kindle Store")),
        Plan("Gifts", 0.6, 500, 5200, listOf("Ferns N Petals", "Amazon Gift Card")),
        Plan(ANOMALY_CATEGORY, 2.0, 900, 6500, listOf("WeWork India", "Zoho Books", "Canva Pro", "Uber for Business")),
        Plan(CAT_PET_CARE, 1.0, 300, 2800, listOf("Heads Up For Tails", "Vetic")),
        Plan("Other", 1.0, 200, 1500, listOf("Local store", "Misc")),
    )

    private val notesPool = mapOf(
        "Food" to listOf("Team lunch", "Friday takeaway", "Coffee with Ananya"),
        "Groceries" to listOf("Weekly stock-up", "Ran out of everything"),
        "Travel" to listOf("Goa trip", "Client visit — Pune"),
        "Health" to listOf("Monthly medicines", "Annual check-up"),
        ANOMALY_CATEGORY to listOf("Client pitch", "Reimbursable", "Co-working day pass"),
        "Shopping" to listOf("Birthday gift", "Replaced the old one"),
    )

    private val recurringRules: List<DemoRecurring> = listOf(
        DemoRecurring("rent", 32_000_00L, TxnKind.EXPENSE, "Rent", "Monthly Rent", "Flat 402", RecurrenceFreq.MONTHLY, 3, 13, DemoInstrument.BANK_ICICI),
        DemoRecurring("sip", 15_000_00L, TxnKind.EXPENSE, "Investments", "Groww SIP", "Index fund", RecurrenceFreq.MONTHLY, 5, 13, DemoInstrument.BANK_ICICI),
        DemoRecurring("emi", 12_450_00L, TxnKind.EXPENSE, "Loan/EMI", "HDFC Home Loan EMI", null, RecurrenceFreq.MONTHLY, 7, 13, DemoInstrument.BANK_ICICI),
        DemoRecurring("netflix", 649_00L, TxnKind.EXPENSE, "Subscriptions", "Netflix", null, RecurrenceFreq.MONTHLY, 12, 13, DemoInstrument.CARD_HDFC),
        DemoRecurring("help", 1_200_00L, TxnKind.EXPENSE, "Other", "House Help", null, RecurrenceFreq.MONTHLY, 1, 13, null),
        DemoRecurring("gym", 18_000_00L, TxnKind.EXPENSE, "Fitness", "Cult.fit Annual", "Annual pass", RecurrenceFreq.YEARLY, 15, 13, DemoInstrument.CARD_AXIS),
        DemoRecurring("milk", 90_00L, TxnKind.EXPENSE, "Groceries", "Country Delight", null, RecurrenceFreq.WEEKLY, 1, 13, DemoInstrument.UPI_GPAY),
    )

    private val learnedMerchants: List<DemoLearnedMerchant> = listOf(
        DemoLearnedMerchant("Swiggy", "Food"),
        DemoLearnedMerchant("Zomato", "Food"),
        DemoLearnedMerchant("BigBasket", "Groceries", "Weekly stock-up"),
        DemoLearnedMerchant("Blinkit", "Groceries"),
        DemoLearnedMerchant("Uber", "Transport"),
        DemoLearnedMerchant("Indian Oil", "Fuel"),
        DemoLearnedMerchant("Apollo Pharmacy", "Health"),
        DemoLearnedMerchant("Tata Power", "Utilities"),
        DemoLearnedMerchant("WeWork India", ANOMALY_CATEGORY, "Reimbursable"),
        DemoLearnedMerchant("Amazon", "Shopping"),
    )

    /** Build the whole demo account as of [today]. */
    fun build(today: LocalDate): DemoDataSet {
        val rnd = Random(SEED)
        val txns = mutableListOf<DemoTxn>()

        val currentMonth = YearMonth.from(today)
        val oldestMonth = currentMonth.minusMonths((RICH_MONTHS + BASELINE_MONTHS - 1).toLong())

        var month = oldestMonth
        while (!month.isAfter(currentMonth)) {
            val monthsAgo = monthsBetween(month, currentMonth)
            txns += generateMonth(rnd, month, monthsAgo, today)
            txns += generateIncome(rnd, month, monthsAgo, today)
            month = month.plusMonths(1)
        }

        txns += replayRecurring(today)
        txns += scriptedHighlights(today)
        txns += scriptedSplits(today)
        txns += scriptedTrash(today)

        return DemoDataSet(
            txns = txns.sortedBy { it.date },
            pending = pendingCaptures(),
            recurring = recurringRules,
            learned = learnedMerchants,
        )
    }

    // ---- ordinary month-by-month spending ----

    private fun generateMonth(rnd: Random, month: YearMonth, monthsAgo: Int, today: LocalDate): List<DemoTxn> {
        val out = mutableListOf<DemoTxn>()
        // The current month is only partly over, so it gets a proportional share of a month's activity.
        // Without this, a full month's transactions would be crammed into however many days have elapsed and
        // the current cycle would look inflated against every month behind it.
        val elapsed = if (month == YearMonth.from(today)) today.dayOfMonth.toDouble() / month.lengthOfMonth() else 1.0

        for (plan in plans) {
            val n = wobbledCount(rnd, plan.perMonth * UNIFORM_MONTHLY_VOLUME * elapsed)
            repeat(n) {
                val date = pickDay(rnd, month, today, plan.discretionary) ?: return@repeat
                val base = rnd.nextInt(plan.minRupees, plan.maxRupees + 1).toDouble()
                val amount = roundRupees(base * amountMultiplier(plan, monthsAgo, date))
                if (amount <= 0L) return@repeat
                val merchant = plan.merchants.random(rnd)
                out += DemoTxn(
                    amountMinor = amount,
                    date = date,
                    hour = rnd.nextInt(8, 22),
                    minute = rnd.nextInt(0, 60),
                    kind = TxnKind.EXPENSE,
                    merchant = merchant,
                    note = notesPool[plan.category]?.let { pool -> if (rnd.nextDouble() < 0.18) pool.random(rnd) else null },
                    source = pickSource(rnd),
                    instrument = pickInstrument(rnd),
                    allocations = listOf(DemoAllocation(plan.category, amount)),
                )
            }
        }
        return out
    }

    /**
     * The amount lift applied to one transaction. Every factor here is a scripted story the insights can find
     * (see the table on [DemoScript]) — none of it is noise.
     */
    private fun amountMultiplier(plan: Plan, monthsAgo: Int, date: LocalDate): Double {
        var m = 1.0
        // Last year's equivalent months read pricier, so year-on-year has a clear direction.
        if (monthsAgo in 11..13) m *= YOY_FACTOR
        // A steady climb in one everyday category over the last six months.
        if (plan.category == TREND_CATEGORY) m *= 1.0 - TREND_TOTAL_DRIFT * (minOf(monthsAgo, 6) / 6.0)
        // The positive mirror image of the anomaly — a category the user has genuinely eased off, held across
        // the whole recent window so it reads as a habit change rather than one quiet month.
        if (plan.category == QUIET_WIN_CATEGORY && monthsAgo < RICH_MONTHS) m *= QUIET_WIN_FACTOR
        // Behavioural texture.
        if (date.dayOfMonth >= SALARY_DAY) m *= PAYDAY_FACTOR
        if (plan.discretionary && (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY)) m *= WEEKEND_FACTOR
        return m
    }

    // ---- income ----

    private fun generateIncome(rnd: Random, month: YearMonth, monthsAgo: Int, today: LocalDate): List<DemoTxn> {
        val out = mutableListOf<DemoTxn>()
        val salaryDate = clampToMonth(month, SALARY_DAY)
        if (!salaryDate.isAfter(today)) {
            // A raise partway through the history, so income isn't a flat line. Deliberately not far above
            // outgoings — a demo account that is wildly cash-positive has nothing interesting to say about
            // savings, pace or commitments.
            val salary = if (monthsAgo >= 6) 1_45_000_00L else 1_60_000_00L
            out += DemoTxn(
                amountMinor = salary,
                date = salaryDate,
                hour = 10, minute = 32,
                kind = TxnKind.INCOME,
                merchant = "Acme Technologies Payroll",
                note = null,
                source = TxnSource.SMS,
                instrument = DemoInstrument.BANK_ICICI,
                allocations = listOf(DemoAllocation("Salary", salary)),
            )
        }
        fun extra(chance: Double, day: Int, amount: Long, category: String, merchant: String, source: TxnSource) {
            if (rnd.nextDouble() >= chance) return
            val date = clampToMonth(month, day)
            if (date.isAfter(today)) return
            out += DemoTxn(amount, date, 14, 5, TxnKind.INCOME, merchant, null, source, DemoInstrument.BANK_ICICI, listOf(DemoAllocation(category, amount)))
        }
        extra(0.40, 18, roundRupees(rnd.nextInt(18_000, 48_000).toDouble()), "Business", "Northwind Retainer", TxnSource.MANUAL)
        extra(0.30, 9, roundRupees(rnd.nextInt(300, 2_500).toDouble()), "Refund", "Amazon Refund", TxnSource.SMS)
        extra(if (monthsAgo % 3 == 0) 1.0 else 0.0, 28, roundRupees(rnd.nextInt(800, 2_200).toDouble()), "Interest", "ICICI Savings Interest", TxnSource.SMS)
        extra(0.50, 21, roundRupees(rnd.nextInt(50, 600).toDouble()), "Cashback", "Credit Card Cashback", TxnSource.NOTIFICATION)
        return out
    }

    // ---- recurring rules replayed as history ----

    /**
     * Every past occurrence of each rule, as a real `RECURRING`-sourced transaction. The seeder sets each
     * rule's `nextRunAt` to the first occurrence *after* today, so the app's materialiser has nothing to
     * catch up on and can never duplicate this history.
     */
    private fun replayRecurring(today: LocalDate): List<DemoTxn> {
        val out = mutableListOf<DemoTxn>()
        for (rule in recurringRules) {
            var date = firstOccurrence(rule, today)
            while (!date.isAfter(today)) {
                out += DemoTxn(
                    amountMinor = rule.amountMinor,
                    date = date,
                    hour = 0, minute = 0,
                    kind = rule.kind,
                    merchant = rule.merchant,
                    note = rule.note,
                    source = TxnSource.RECURRING,
                    instrument = rule.instrument,
                    allocations = listOf(DemoAllocation(rule.categoryName, rule.amountMinor)),
                    recurringKey = rule.key,
                )
                date = stepRecurring(date, rule)
            }
        }
        return out
    }

    /** The first occurrence on or after the rule's start date. */
    fun firstOccurrence(rule: DemoRecurring, today: LocalDate): LocalDate {
        val startMonth = YearMonth.from(today).minusMonths(rule.startMonthsAgo.toLong())
        return when (rule.freq) {
            RecurrenceFreq.WEEKLY -> {
                var d = startMonth.atDay(1)
                while (d.dayOfWeek.value != rule.anchorDay) d = d.plusDays(1)
                d
            }
            RecurrenceFreq.DAILY -> startMonth.atDay(1)
            RecurrenceFreq.MONTHLY -> clampToMonth(startMonth, rule.anchorDay)
            RecurrenceFreq.YEARLY -> clampToMonth(startMonth, rule.anchorDay)
        }
    }

    /**
     * Delegates to the app's own [RecurrenceMath] rather than repeating the cadence maths. That is what makes
     * the `nextRunAt` the seeder stores agree exactly with what the app's materialiser will compute from it —
     * a private copy that drifted would leave the demo's recurring rules firing on the wrong days.
     */
    fun stepRecurring(from: LocalDate, rule: DemoRecurring): LocalDate =
        RecurrenceMath.nextDate(from, rule.freq, 1, rule.anchorDay)

    // ---- deliberately planted, findable stories ----

    /** The planted anomaly, the one-off outlier and the duplicate pair — what an anomaly card should catch. */
    private fun scriptedHighlights(today: LocalDate): List<DemoTxn> {
        val out = mutableListOf<DemoTxn>()

        // A burst of business spending over the last few days. Placed by fixed offsets rather than by a
        // monthly multiplier so it is always present and always recent, whatever day the demo is given.
        val burstMerchants = listOf("WeWork India", "IndiGo", "Taj Hotels", "Uber for Business")
        ANOMALY_BURST.forEachIndexed { i, (daysAgo, amount) ->
            out += DemoTxn(
                amount, today.minusDays(daysAgo), 11 + i, 20, TxnKind.EXPENSE, burstMerchants[i],
                "Client pitch — reimbursable",
                if (i % 2 == 0) TxnSource.SMS else TxnSource.NOTIFICATION,
                DemoInstrument.CARD_AXIS, listOf(DemoAllocation(ANOMALY_CATEGORY, amount)),
            )
        }

        // ⭐Sized so "3× the usual" is PROVABLE, not merely likely. The Fuel plan caps at ₹2,600, lifted at
        // most by payday (×1.32) and the year-on-year factor (×1.15) — so no organic fuel charge can exceed
        // ~₹3,950. At ₹12,500 this is ≥3× *every possible* organic charge, for every seed and every date.
        // Leaving it near the plan's own median made the guarantee a 34-sample coin flip instead.
        val fuelDay = today.minusDays(1)
        val bigFuel = 12_500_00L
        out += DemoTxn(
            bigFuel, fuelDay, 19, 12, TxnKind.EXPENSE, "Shell", "Filled up for the road trip",
            TxnSource.SMS, DemoInstrument.CARD_HDFC, listOf(DemoAllocation(ANOMALY_ONE_OFF_CATEGORY, bigFuel)),
        )
        // A same-merchant, same-amount, same-day pair: exactly what a duplicate-charge check should surface.
        val dupDay = today.minusDays(4)
        val dupAmount = 1_240_00L
        repeat(2) { i ->
            out += DemoTxn(
                dupAmount, dupDay, 20, 14 + i, TxnKind.EXPENSE, DUPLICATE_MERCHANT, null,
                TxnSource.NOTIFICATION, DemoInstrument.CARD_AXIS, listOf(DemoAllocation("Entertainment", dupAmount)),
            )
        }
        return out
    }

    /** Multi-category transactions, so the split view has something real behind it. */
    private fun scriptedSplits(today: LocalDate): List<DemoTxn> {
        fun split(daysAgo: Long, merchant: String, parts: List<Pair<String, Long>>, instrument: DemoInstrument): DemoTxn {
            val allocations = parts.map { DemoAllocation(it.first, it.second) }
            return DemoTxn(
                amountMinor = allocations.sumOf { it.amountMinor },
                date = today.minusDays(daysAgo),
                hour = 18, minute = 40,
                kind = TxnKind.EXPENSE,
                merchant = merchant,
                note = "Split across categories",
                source = TxnSource.MANUAL,
                instrument = instrument,
                allocations = allocations,
            )
        }
        return listOf(
            split(3, "Amazon", listOf("Shopping" to 3_200_00L, "Groceries" to 1_150_00L, "Gifts" to 900_00L), DemoInstrument.CARD_HDFC),
            split(12, "DMart", listOf("Groceries" to 2_800_00L, "Personal Care" to 640_00L), DemoInstrument.BANK_ICICI),
            split(26, "Decathlon", listOf("Fitness" to 4_100_00L, "Shopping" to 1_500_00L), DemoInstrument.CARD_AXIS),
            split(48, "MakeMyTrip", listOf("Travel" to 14_200_00L, "Food" to 1_800_00L), DemoInstrument.CARD_HDFC),
        )
    }

    /** A few rows sitting in Trash so restore and auto-purge are demonstrable. */
    private fun scriptedTrash(today: LocalDate): List<DemoTxn> = listOf(
        DemoTxn(560_00L, today.minusDays(6), 13, 5, TxnKind.EXPENSE, "Zomato", "Cancelled order", TxnSource.SMS, DemoInstrument.UPI_GPAY, listOf(DemoAllocation("Food", 560_00L)), deletedDaysAgo = 3),
        DemoTxn(2_100_00L, today.minusDays(11), 16, 25, TxnKind.EXPENSE, "Myntra", "Returned", TxnSource.MANUAL, DemoInstrument.CARD_AXIS, listOf(DemoAllocation("Shopping", 2_100_00L)), deletedDaysAgo = 5),
        DemoTxn(320_00L, today.minusDays(14), 9, 45, TxnKind.EXPENSE, "Uber", "Duplicate entry", TxnSource.MANUAL, DemoInstrument.UPI_GPAY, listOf(DemoAllocation("Transport", 320_00L)), deletedDaysAgo = 8),
    )

    /**
     * The review queue. Bodies are realistic Indian bank alerts so the review card, the source-text view and
     * the origin badges all look right. One row is deliberately left on "Other" with low confidence — that is
     * the row the AI category suggestion chip appears on.
     */
    private fun pendingCaptures(): List<DemoPendingCapture> = listOf(
        DemoPendingCapture(
            2_499_00L, TxnKind.EXPENSE, 0, "Croma", "4821", "HDFC Bank", "Shopping", 88,
            "Spent Rs.2499.00 On HDFC Bank Card 4821 At CROMA RETAIL On 2024-01-01. Avl Lmt: Rs.148500.00. Not you? Call 18002586161.",
            "HDFCBK", null,
        ),
        DemoPendingCapture(
            780_00L, TxnKind.EXPENSE, 0, "Swiggy", null, "ICICI Bank", "Food", 92,
            "Dear Customer, Acct XX8842 debited with INR 780.00 on 01-Jan-24; SWIGGY credited. UPI:402911883746. Call 18002662 for dispute.",
            "ICICIB", "com.google.android.apps.messaging",
        ),
        DemoPendingCapture(
            15_600_00L, TxnKind.EXPENSE, 1, "Vertex Interiors", null, "Axis Bank", "Other", 61,
            "INR 15600.00 debited from A/c no. XX4409 on 01-01-24 12:41:03 IST VERTEX INTERIORS Ref no 004094 Avl Bal INR 88450.22",
            "AXISBK", "com.truecaller",
        ),
        DemoPendingCapture(
            1_180_00L, TxnKind.EXPENSE, 2, "Indian Oil", "9013", "SBI Card", "Fuel", 95,
            "Rs.1180.00 spent on SBI Credit Card ending 9013 at INDIAN OIL on 01-01-24. Trxn. not done by you? Report at sbicard.com/dispute",
            "SBICRD", null,
        ),
        DemoPendingCapture(
            449_00L, TxnKind.EXPENSE, 3, "Spotify", "4821", "HDFC Bank", "Subscriptions", 90,
            "Spent Rs.449.00 On HDFC Bank Card 4821 At SPOTIFY INDIA On 01-01-24. Avl Lmt: Rs.151000.00.",
            "HDFCBK", "com.truecaller",
        ),
        DemoPendingCapture(
            6_400_00L, TxnKind.INCOME, 4, "Northwind Retainer", null, "ICICI Bank", "Business", 84,
            "Dear Customer, Acct XX8842 credited with INR 6400.00 on 01-Jan-24 from NORTHWIND. Avl Bal INR 94850.22.",
            "ICICIB", null,
        ),
        DemoPendingCapture(
            2_050_00L, TxnKind.EXPENSE, 5, "Heads Up For Tails", null, "Axis Bank", "Other", 58,
            "INR 2050.00 debited from A/c no. XX4409 on 01-01-24 HEADS UP FOR TAILS Ref no 771230 Avl Bal INR 86400.22",
            "AXISBK", "com.google.android.apps.messaging",
        ),
    )

    // ---- small helpers ----

    private fun monthsBetween(from: YearMonth, to: YearMonth): Int =
        ((to.year - from.year) * 12 + (to.monthValue - from.monthValue))

    /** Day-of-month [day], clamped to months that are too short (the 31st of a 30-day month → the 30th). */
    private fun clampToMonth(month: YearMonth, day: Int): LocalDate = month.atDay(day.coerceIn(1, month.lengthOfMonth()))

    /** `2.6` becomes 2 or 3, weighted by the fraction — so a fractional rate averages out across months. */
    private fun wobbledCount(rnd: Random, expected: Double): Int {
        val whole = floor(expected).toInt()
        return whole + if (rnd.nextDouble() < (expected - whole)) 1 else 0
    }

    /** A weighted day in [month], never in the future. Payday week and (for treats) weekends pull harder. */
    private fun pickDay(rnd: Random, month: YearMonth, today: LocalDate, weekendBias: Boolean): LocalDate? {
        val last = if (month == YearMonth.from(today)) today.dayOfMonth else month.lengthOfMonth()
        if (last < 1) return null
        val days = (1..last).map { month.atDay(it) }
        val weights = days.map { d ->
            var w = 1.0
            if (d.dayOfMonth >= SALARY_DAY) w += 0.9
            if (weekendBias && (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY)) w += 0.6
            w
        }
        var ticket = rnd.nextDouble() * weights.sum()
        for (i in days.indices) {
            ticket -= weights[i]
            if (ticket <= 0) return days[i]
        }
        return days.last()
    }

    /** Rupees → paise, rounded to the nearest ₹5 so amounts read like real prices. */
    private fun roundRupees(rupees: Double): Long = ((rupees / 5.0).roundToLong() * 5L) * 100L

    private fun pickSource(rnd: Random): TxnSource = when {
        rnd.nextDouble() < 0.30 -> TxnSource.SMS
        rnd.nextDouble() < 0.20 -> TxnSource.NOTIFICATION
        else -> TxnSource.MANUAL
    }

    /** Spread across both cards and both bank-side instruments, so Smart Cycle's per-card billing has work. */
    private fun pickInstrument(rnd: Random): DemoInstrument? = when {
        rnd.nextDouble() < 0.34 -> DemoInstrument.CARD_HDFC
        rnd.nextDouble() < 0.34 -> DemoInstrument.CARD_AXIS
        rnd.nextDouble() < 0.50 -> DemoInstrument.UPI_GPAY
        else -> DemoInstrument.BANK_ICICI
    }
}
