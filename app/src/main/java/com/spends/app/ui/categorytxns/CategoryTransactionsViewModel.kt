package com.spends.app.ui.categorytxns

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.period.PeriodRange
import com.spends.app.core.period.PeriodResolver
import com.spends.app.core.period.PeriodSelection
import com.spends.app.core.period.PeriodSelectionStore
import com.spends.app.core.period.PeriodType
import com.spends.app.core.period.SmartCardCycle
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.entity.ExpenseWithAllocations
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.TxnKind
import com.spends.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import javax.inject.Inject

/** Trailing window the monthly-average metric is computed over (#8). null months = all time. */
enum class AvgWindow(val label: String, val monthsBack: Long?) {
    M3("3M", 3), M6("6M", 6), ALL("All", null)
}

/**
 * Which shape the average takes.
 *
 * [MONTHLY] is unchanged: a trailing 3M / 6M / All window, with the list below showing only the selected
 * cycle. [YEARLY] swaps the window for a calendar YEAR — and, crucially, the list below then shows that
 * whole year, so the headline figure and the list finally describe the same stretch of time. That mismatch
 * is the entire reason the average moved to the top of the screen.
 */
enum class AvgMode(val label: String) { MONTHLY("Monthly"), YEARLY("Yearly") }

/**
 * The whole average control as one value.
 *
 * One flow rather than three because `combine` tops out at five typed flows and this screen already uses
 * all five; splitting these would force another layer of nesting for no gain.
 */
data class AvgSelection(
    val mode: AvgMode = AvgMode.MONTHLY,
    val window: AvgWindow = AvgWindow.M6,
    /** null = "whichever year is newest in the data" — resolved once the rows arrive. */
    val year: Int? = null,
)

/** One transaction row in the per-category drill-down list. */
data class CategoryTxnRow(
    val id: Long,
    val title: String,
    val note: String?,
    val dateLabel: String,
    val timeLabel: String,
    val kind: TxnKind,
    val iconKey: String,
    val colorHex: String,
    /** The amount actually allocated to *this* category on the transaction (handles splits). */
    val amountMinor: Long,
    /**
     * True when this row's own date falls OUTSIDE the cycle's printed dates, but it belongs to the cycle
     * anyway because Smart Cycle buckets by the paying card's BILLING day. Without saying so on the row,
     * a 23 Jul transaction under a "25 Jul – 24 Aug" heading reads as a bug in the app rather than as the
     * statement date it actually is.
     */
    val billedIntoCycle: Boolean = false,
)

data class CategoryTxnsUiState(
    val loading: Boolean = true,
    val categoryName: String = "",
    // The concrete date range of the selected cycle (#5) — shown by the selector pill as its secondary line.
    // Monthly mode only; in Yearly mode the list is a whole calendar year and no cycle is involved.
    val cycleLabel: String = "",
    /** What the LIST below adds up to: the selected cycle in Monthly mode, the selected year in Yearly. */
    val totalMinor: Long = 0,
    val count: Int = 0,
    // Average spend per month. Monthly mode: over the chosen trailing window (#8: Last 3M / 6M / All),
    // independent of the selected cycle. Yearly mode: over the selected calendar year. Both divide by the
    // months that have actually elapsed, capped at the category's own first transaction, so a young
    // category (or the current, unfinished year) isn't divided by months it never existed for.
    val monthlyAverageMinor: Long = 0,
    val avgMode: AvgMode = AvgMode.MONTHLY,
    val avgWindow: AvgWindow = AvgWindow.M6,
    /** Every calendar year this category has a transaction in, newest first. No cap — the owner's choice. */
    val availableYears: List<Int> = emptyList(),
    /** The year being shown in Yearly mode; null when there is no data at all. */
    val selectedYear: Int? = null,
    val rows: List<CategoryTxnRow> = emptyList(),
    /**
     * "About ₹1,500 more than your usual month." — the answer the screen exists to give, in words.
     * Null whenever there is no baseline: no history in Monthly, no earlier year with data in Yearly.
     */
    val comparison: CycleComparison? = null,
    /** The two comparison bars, ready to draw. Monthly: this cycle vs usual. Yearly: this year vs last. */
    val comparisonSelfLabel: String = "",
    val comparisonRefLabel: String = "",
    val comparisonSelfMinor: Long = 0,
    val comparisonRefMinor: Long = 0,
    /**
     * The heading above the headline figure. Computed here rather than in the screen because only this
     * layer knows whether the selected window is the current cycle, an earlier one, or not a cycle at
     * all — a screen that assumed "current" called a cycle from three months ago "THIS CYCLE".
     */
    val periodHeading: String = "THIS CYCLE",
)

@HiltViewModel
class CategoryTransactionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    expenseRepository: ExpenseRepository,
    settingsRepository: SettingsRepository,
    periodSelectionStore: PeriodSelectionStore,
    paymentMethodRepository: PaymentMethodRepository,
) : ViewModel() {

    private val categoryId: Long = savedStateHandle[Routes.ARG_CATEGORY_ID] ?: -1L
    private val categoryName: String = savedStateHandle[Routes.ARG_CATEGORY_NAME] ?: ""

    private val avg = MutableStateFlow(AvgSelection())
    fun setAvgMode(mode: AvgMode) = avg.update { it.copy(mode = mode) }
    fun setAvgWindow(window: AvgWindow) = avg.update { it.copy(window = window) }
    fun setYear(year: Int) = avg.update { it.copy(year = year) }

    // Drives the compact selector's Smart pill (the drill-down offers Smart minus the card narrowing).
    val smartCycleEnabled: StateFlow<Boolean> =
        settingsRepository.settings.map { it.smartCycleEnabled }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // A LOCAL cycle selector (#5), independent of the shared Transactions/Analytics cycle (user's choice):
    // changing it here NEVER writes back to the shared store. It's SEEDED from the cycle you were viewing so
    // the drill-down matches the number you tapped in Analytics (no mismatch). It's shown as a compact
    // single-line control: for a single current cycle (the usual case) it's a ‹ › prev/next stepper; for
    // All-time / Last-N / Custom it's a tappable name (those ranges have no prev/next). Tapping the name opens
    // the full picker either way. A Smart Cycle selection keeps its type (and offset) — since Smart is one
    // contiguous window anchored on the reset day, this drill-down slices the EXACT window you tapped; only
    // the single-card narrowing is dropped (a per-category list isn't per-card).
    private val period = MutableStateFlow(
        periodSelectionStore.selection.value.let { s ->
            if (s.type == PeriodType.SMART_CYCLE) s.copy(selectedCardId = null) else s
        },
    )
    val periodSelection: StateFlow<PeriodSelection> = period.asStateFlow()
    fun setPeriod(selection: PeriodSelection) = period.update { selection }

    val state: StateFlow<CategoryTxnsUiState> =
        // Query the category's WHOLE history once, then slice in memory: the list/total show the selected
        // cycle, while the monthly average is computed over the chosen trailing window (#8).
        combine(
            expenseRepository.observeByCategoryBetween(categoryId, 0L, Long.MAX_VALUE),
            avg,
            period,
            settingsRepository.settings,
            // Pair earliest-day with the confirmed cards (their billing days) so the Smart Cycle slice can bucket
            // each txn by its paying card — combine tops out at 5 typed flows, so nest these two.
            combine(expenseRepository.observeEarliestDay(), paymentMethodRepository.observeConfirmed()) { e, c -> e to c },
        ) { allItems, avgSel, sel, settings, earliestAndCards ->
            val earliest = earliestAndCards.first
            val cards = earliestAndCards.second
            val now = DateUtils.nowMillis()
            val today = LocalDate.now(DateUtils.ZONE)

            // Every year this category has data in, newest first — the Yearly picker's whole content.
            val availableYears = allItems
                .map { DateUtils.toLocalDate(it.expense.occurredAt).year }
                .distinct()
                .sortedDescending()
            // A year the user picked can stop existing (the last transaction in it was deleted or moved),
            // so it is validated against the live list rather than trusted. Falling back to the newest year
            // keeps the screen showing real data instead of an empty list under a stale heading.
            val year = avgSel.year?.takeIf { it in availableYears } ?: availableYears.firstOrNull()
            // Yearly with no data anywhere has no year to show; fall back so the screen still renders.
            val mode = if (avgSel.mode == AvgMode.YEARLY && year == null) AvgMode.MONTHLY else avgSel.mode
            // Resolve the selected cycle to a concrete [start, end) window, the same way Analytics does.
            // A Smart selection anchors on the reset day (default = salary day), matching the tapped slice;
            // a stale SMART selection while the feature is off coerces to the salary cycle (like every
            // other consumer — the bar already displays it as Salary in that state).
            val effType = if (sel.type == PeriodType.SMART_CYCLE && !settings.smartCycleEnabled) {
                PeriodType.SALARY_CYCLE
            } else {
                sel.type
            }
            val isSmartCard = settings.smartCycleEnabled && sel.type == PeriodType.SMART_CYCLE
            val resolved = PeriodResolver.resolve(
                type = effType,
                // Smart Cycle is always the single CURRENT cycle (stepped by the arrows) — force it so a stray
                // Last-N/All range can't make `resolved.startMillis` the oldest cycle in a span and shrink the
                // card-aware filter below to just that one cycle. Matches Analytics/Transactions.
                range = if (isSmartCard) PeriodRange.CURRENT else sel.range,
                salaryDay = settings.salaryCycleStartDay,
                smartDay = settings.effectiveSmartResetDay,
                today = today,
                earliestDataDay = earliest?.let { DateUtils.toLocalDate(it) },
                customStartMillis = sel.customStartMillis,
                customEndExclusiveMillis = sel.customEndExclusiveMillis,
                cycleOffset = sel.cycleOffset,
            )
            // In Smart Cycle, bucket each txn by its paying card's billing day (same rule as the timeline), so
            // this drill-down reconciles with the Analytics slice you tapped. Otherwise slice by plain date.
            val periodItems = if (isSmartCard) {
                val billingDays = cards.associate { it.id to it.billingDay }
                SmartCardCycle.filterToWindow(
                    items = allItems,
                    windowStartMillis = resolved.startMillis,
                    resetDay = settings.effectiveSmartResetDay,
                    occurredAtOf = { it.expense.occurredAt },
                    billingDayOf = { billingDays[it.expense.paymentMethodId] },
                )
            } else {
                allItems.filter {
                    it.expense.occurredAt >= resolved.startMillis && it.expense.occurredAt < resolved.endExclusiveMillis
                }
            }
            // ---- The average, and the rows the list shows ----
            //
            // The two are computed together on purpose. In Monthly mode they deliberately DISAGREE (a 6-month
            // average above a one-cycle list), which is exactly what used to read as one thing when the
            // average sat next to the list; in Yearly mode they must AGREE, or the same confusion returns a
            // year at a time.
            val earliestAll = allItems.minOfOrNull { it.expense.occurredAt }
            val avgStart: Long
            val avgEndExclusive: Long
            val listItems: List<ExpenseWithAllocations>
            if (mode == AvgMode.YEARLY && year != null) {
                avgStart = DateUtils.startOfDayMillis(LocalDate.of(year, 1, 1))
                avgEndExclusive = DateUtils.startOfDayMillis(LocalDate.of(year + 1, 1, 1))
                // The list IS the year — a plain date range, with no Smart-Cycle card bucketing, because a
                // calendar year is not a billing cycle and there is no statement to bucket into.
                listItems = allItems.filter { it.expense.occurredAt in avgStart until avgEndExclusive }
            } else {
                avgStart = avgSel.window.monthsBack
                    ?.let { DateUtils.startOfDayMillis(today.minusMonths(it)) }
                    ?: 0L
                avgEndExclusive = now
                listItems = periodItems
            }
            // A row can sit outside the cycle's printed dates and still belong to it: in Smart Cycle the
            // bucketing is by the paying card's BILLING day (above), not by the calendar. Flag those so the
            // row can say so, instead of looking like the total counted something it shouldn't have.
            val yearlyList = mode == AvgMode.YEARLY && year != null
            val rows = listItems.map { item ->
                val outsidePrintedDates = isSmartCard && !yearlyList &&
                    (
                        item.expense.occurredAt < resolved.startMillis ||
                            item.expense.occurredAt >= resolved.endExclusiveMillis
                        )
                item.toRow(billedIntoCycle = outsidePrintedDates)
            }
            val total = rows.sumOf { it.amountMinor }

            // Spend in the averaging window ÷ the months it actually covers. Both ends are clamped:
            //  - the start at the category's own first transaction, so a young category isn't divided by
            //    the full 3/6 months (or by a whole year it did not exist for);
            //  - the end at now, so the CURRENT year divides by the months elapsed rather than by twelve —
            //    without which this August would report an "average" a third under the real one, and 2026
            //    would look like a spending collapse next to 2025 every year until December.
            // Extracted so the Yearly view can run the identical calculation over the PREVIOUS year and
            // compare like with like. Inlining it twice is how the two would drift apart.
            fun monthlyAverageOver(startMillis: Long, endExclusiveMillis: Long): Long {
                val windowTotal = allItems
                    .filter { it.expense.occurredAt in startMillis until endExclusiveMillis }
                    .sumOf { it.allocatedToCategory() }
                val effectiveStart = maxOf(startMillis, earliestAll ?: now)
                val effectiveEnd = minOf(endExclusiveMillis, now)
                val months = if (effectiveEnd <= effectiveStart) {
                    1.0
                } else {
                    ((effectiveEnd - effectiveStart).toDouble() / 86_400_000.0 / 30.44).coerceAtLeast(1.0)
                }
                return (windowTotal / months).toLong()
            }

            val averageMinor = monthlyAverageOver(avgStart, avgEndExclusive)

            // Yearly compares this year's per-month figure against the newest EARLIER year that actually
            // has data — not `year - 1`, which would show an empty bar for anyone with a gap in their
            // history. Both sides divide by months elapsed, so a part-finished year compares fairly.
            val previousYear = if (yearlyList) availableYears.firstOrNull { it < year!! } else null
            val previousYearAverage = previousYear?.let {
                monthlyAverageOver(
                    DateUtils.startOfDayMillis(LocalDate.of(it, 1, 1)),
                    DateUtils.startOfDayMillis(LocalDate.of(it + 1, 1, 1)),
                )
            } ?: 0L

            val comparison = if (yearlyList) {
                CycleComparison.of(
                    totalMinor = averageMinor,
                    usualMonthMinor = previousYearAverage,
                    stillRunning = year == today.year,
                    reference = "your monthly average in $previousYear",
                    emptyText = "Nothing in $year yet.",
                )
            } else {
                // `stillRunning` keeps the wording honest about a cycle that has not finished.
                CycleComparison.of(
                    totalMinor = total,
                    usualMonthMinor = averageMinor,
                    stillRunning = resolved.endExclusiveMillis > now,
                )
            }

            CategoryTxnsUiState(
                loading = false,
                categoryName = categoryName,
                cycleLabel = resolved.label,
                totalMinor = total,
                count = rows.size,
                monthlyAverageMinor = averageMinor,
                comparison = comparison,
                comparisonSelfLabel = if (yearlyList) year.toString() else "This cycle",
                comparisonRefLabel = if (yearlyList) previousYear?.toString().orEmpty() else "Usual",
                comparisonSelfMinor = if (yearlyList) averageMinor else total,
                comparisonRefMinor = if (yearlyList) previousYearAverage else averageMinor,
                // Only a CURRENT-range selection is a single cycle that can be "this" one; stepping back
                // ends the window, and All-time / Last-N / Custom are not cycles at all.
                periodHeading = when {
                    yearlyList -> "AVERAGE PER MONTH IN $year"
                    sel.range != PeriodRange.CURRENT -> "SELECTED PERIOD"
                    resolved.startMillis > now -> "UPCOMING CYCLE"
                    resolved.endExclusiveMillis > now -> "THIS CYCLE"
                    else -> "EARLIER CYCLE"
                },
                avgMode = mode,
                avgWindow = avgSel.window,
                availableYears = availableYears,
                selectedYear = year,
                rows = rows,
            )
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                CategoryTxnsUiState(categoryName = categoryName),
            )

    /** The amount allocated to THIS category on a transaction (handles splits). */
    private fun ExpenseWithAllocations.allocatedToCategory(): Long =
        allocations.filter { it.allocation.categoryId == categoryId }.sumOf { it.allocation.amountMinor }

    private fun ExpenseWithAllocations.toRow(billedIntoCycle: Boolean = false): CategoryTxnRow {
        // The amount belonging to this category specifically (a transaction may split across many).
        val allocated = allocations
            .filter { it.allocation.categoryId == categoryId }
            .sumOf { it.allocation.amountMinor }
        val thisCat = allocations.firstOrNull { it.allocation.categoryId == categoryId }?.category
        val title = expense.merchantRaw?.takeIf { it.isNotBlank() }
            ?: thisCat?.name
            ?: expense.note?.takeIf { it.isNotBlank() }
            ?: "Transaction"
        return CategoryTxnRow(
            id = expense.id,
            title = title,
            note = expense.note,
            dateLabel = DateUtils.formatDay(expense.occurredAt),
            timeLabel = DateUtils.formatTime(expense.occurredAt),
            kind = expense.kind,
            iconKey = thisCat?.iconKey ?: "tag",
            colorHex = thisCat?.colorHex ?: "#78716C",
            amountMinor = allocated,
            billedIntoCycle = billedIntoCycle,
        )
    }
}
