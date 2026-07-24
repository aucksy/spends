package com.spends.app.core.period

import com.spends.app.core.time.CycleUtils
import com.spends.app.core.time.DateUtils
import java.time.LocalDate
import java.time.YearMonth

/**
 * Smart-Cycle bucketing that honours each credit card's billing day.
 *
 * The plain Smart Cycle is ONE contiguous window anchored on the reset day (see [PeriodResolver]). This
 * refines it for CARDS: a card purchase belongs to the Smart Cycle in which that purchase's statement is
 * **billed** — i.e. the card's billing day is that card's personal cut-off. A purchase made on/after the
 * billing day rolls into the NEXT Smart Cycle; a purchase before it stays in the current one. Bank / UPI
 * spends, and cards with no known billing day, are NEVER shifted — they follow the reset day exactly as
 * before. (Only Smart Cycle uses this; Salary Cycle and Month stay plain windows.)
 *
 * ### Why this can't resurrect the "balance improves on billing day" bug
 * [effectiveWindowStartMillis] is a **partition**: it maps every transaction to exactly ONE window — never
 * zero (the old bug, where a billed spend left the current window and had nowhere to go), never two (a
 * double count). The shift is also at most ONE window forward (a statement bills within ~a month of the
 * purchase). So a shifted purchase never disappears — it only moves to the adjacent, navigable cycle, where
 * its money is still counted. Conservation of every rupee is guaranteed by construction and pinned by tests.
 *
 * All day math uses [DateUtils.ZONE], identical to [CycleUtils], so results line up exactly with how the
 * repository slices stored transactions.
 */
object SmartCardCycle {

    /**
     * The START millis of the Smart Cycle window this transaction belongs to, once the card billing day is
     * honoured. A transaction belongs to the window whose own start equals this value (see [belongsToWindow]).
     *
     * @param occurredAtMillis the transaction's real timestamp. Its DISPLAY date is never changed — only which
     *   cycle it counts in.
     * @param billingDay the paying card's statement day (1..31), or null for Bank / UPI / a card with no known
     *   billing day → no shift (follows the reset day).
     * @param resetDay the Smart Cycle reset day (1..31), i.e. `SettingsState.effectiveSmartResetDay`.
     */
    fun effectiveWindowStartMillis(occurredAtMillis: Long, billingDay: Int?, resetDay: Int): Long {
        val date = DateUtils.toLocalDate(occurredAtMillis)
        val rawWindow = CycleUtils.windowFor(date, resetDay)
        // Bank / UPI or no known billing day → no shift; bucket by the raw date's window.
        if (billingDay == null || billingDay !in 1..31) return rawWindow.startMillis()
        val shifted = CycleUtils.windowFor(statementCloseDate(date, billingDay), resetDay)
        // CAP the shift at ONE window forward. Normally a statement bills within ~a month, so `shifted` is the
        // raw window or the next one. BUT when the billing day AND the reset day both fall in the month-end
        // clamp zone (e.g. bills 30, resets 31), short-month clamping can collapse a reset boundary and a
        // billing date onto the same day, leaving a reset window that contains NO billing occurrence — which
        // would push the purchase TWO windows ahead. Two-ahead is beyond every consumer's one-window-back
        // fetch, so the transaction would silently vanish (the exact "spend disappears when the billing day
        // passes" regression we must never reintroduce). Capping keeps the ≤1-window invariant TRUE, so such a
        // purchase lands in the adjacent, always-fetched, navigable cycle instead of falling into a gap.
        val cap = CycleUtils.nextWindow(rawWindow, resetDay)
        return if (shifted.start.isAfter(cap.start)) cap.startMillis() else shifted.startMillis()
    }

    /** True iff this transaction belongs to the Smart Cycle window whose start is [windowStartMillis]. */
    fun belongsToWindow(windowStartMillis: Long, occurredAtMillis: Long, billingDay: Int?, resetDay: Int): Boolean =
        effectiveWindowStartMillis(occurredAtMillis, billingDay, resetDay) == windowStartMillis

    /**
     * Filter [items] to those belonging to the Smart Cycle window starting at [windowStartMillis], honouring
     * each item's paying-card billing day. Shared by every read-only consumer (Analytics, the per-instrument
     * breakdown, the category drill-down, the widget) so they all bucket a card purchase EXACTLY as the
     * timeline does — the numbers reconcile across screens. [occurredAtOf]/[billingDayOf] pull the timestamp
     * and the paying card's billing day (null = Bank/UPI or no billing day → no shift) out of each item.
     */
    fun <T> filterToWindow(
        items: List<T>,
        windowStartMillis: Long,
        resetDay: Int,
        occurredAtOf: (T) -> Long,
        billingDayOf: (T) -> Int?,
    ): List<T> = items.filter {
        belongsToWindow(windowStartMillis, occurredAtOf(it), billingDayOf(it), resetDay)
    }

    /**
     * The date the statement CONTAINING [date] is billed for a card with [billingDay]: the next billing-day
     * occurrence strictly after [date]. A purchase made exactly ON the billing day is the first purchase of the
     * NEW statement (billed next month), matching the rule "on the billing day or after → next cycle".
     * Month-end days are clamped (e.g. a 31 billing day bills on Feb 28/29), consistently with
     * [CycleUtils.clampDay] — so the mapping stays reversible and never lands on a non-existent date.
     */
    fun statementCloseDate(date: LocalDate, billingDay: Int): LocalDate {
        val thisMonth = CycleUtils.clampDay(YearMonth.from(date), billingDay)
        return if (thisMonth.isAfter(date)) {
            thisMonth
        } else {
            CycleUtils.clampDay(YearMonth.from(date).plusMonths(1), billingDay)
        }
    }
}
