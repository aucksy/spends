package com.spends.app.core.period

import com.spends.app.core.time.CycleUtils
import com.spends.app.core.time.CycleWindow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Light input describing one card for the composite resolver (decoupled from the Room entity). */
data class CardCycleInfo(
    val id: Long,
    val label: String,
    val colorHex: String?,
    val last4: String?,
    /** Statement day; null = this card has no known billing day yet, so it rides the salary cycle. */
    val billingDay: Int?,
)

/** One instrument's current cycle window inside a composite Smart Cycle (a card, or the Bank/UPI bucket). */
data class InstrumentWindow(
    val paymentMethodId: Long?, // null = the Bank / UPI bucket (salary cycle)
    val label: String,
    val colorHex: String?, // card accent; null for Bank
    val last4: String?,
    val window: CycleWindow,
)

/**
 * A composite period — a SET of per-instrument windows (each card by its own billing cycle, bank/UPI by the
 * salary cycle), NOT one contiguous range (PRD §4.8). A transaction belongs to the composite iff its date
 * falls inside ITS instrument's window. [boundingStartMillis, boundingEndExclusiveMillis) is the smallest
 * single range covering every window — used to fetch candidates from the DB in one query, then filtered by
 * [contains].
 */
data class CompositePeriod(
    val instruments: List<InstrumentWindow>,
    val boundingStartMillis: Long,
    val boundingEndExclusiveMillis: Long,
    val label: String,
) {
    private val bankWindow: CycleWindow? = instruments.firstOrNull { it.paymentMethodId == null }?.window

    private fun windowFor(paymentMethodId: Long?): CycleWindow? =
        instruments.firstOrNull { it.paymentMethodId == paymentMethodId }?.window
            // A txn tagged to a card that's no longer an instrument here (deleted, or excluded in Single-Card)
            // falls back to the Bank window — which is null in Single-Card mode, so it's excluded there.
            ?: bankWindow

    /** True iff this transaction sits inside its own instrument's current cycle. */
    fun contains(occurredAt: Long, paymentMethodId: Long?): Boolean {
        val w = windowFor(paymentMethodId) ?: return false
        return occurredAt >= w.startMillis() && occurredAt < w.endExclusiveMillis()
    }
}

/**
 * Builds [CompositePeriod]s for Smart Cycle (all instruments) and Single-Card (one card). Pure logic — no
 * Android / DB deps — so it stays unit-testable. Cycle stepping uses [cycleOffset] (0 = current, -1 = prev).
 */
object CompositeCycleResolver {

    private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /** Composite of every card (by its billing cycle) + the Bank/UPI bucket (by the salary cycle). */
    fun resolveSmartCycle(cards: List<CardCycleInfo>, salaryDay: Int, today: LocalDate, cycleOffset: Int): CompositePeriod {
        val instruments = buildList {
            add(InstrumentWindow(null, "Bank / UPI", null, null, shiftedWindow(today, salaryDay, cycleOffset)))
            cards.forEach {
                add(InstrumentWindow(it.id, it.label, it.colorHex, it.last4, shiftedWindow(today, it.billingDay ?: salaryDay, cycleOffset)))
            }
        }
        return compose(instruments, smartLabel(cycleOffset))
    }

    /** A single card by its own billing cycle (Single-Card mode). */
    fun resolveSingleCard(card: CardCycleInfo, salaryDay: Int, today: LocalDate, cycleOffset: Int): CompositePeriod {
        val w = shiftedWindow(today, card.billingDay ?: salaryDay, cycleOffset)
        val label = "${card.label}  ·  ${dayFmt.format(w.start)} – ${dayFmt.format(w.endInclusive)}"
        return compose(listOf(InstrumentWindow(card.id, card.label, card.colorHex, card.last4, w)), label)
    }

    private fun compose(instruments: List<InstrumentWindow>, label: String): CompositePeriod {
        val minStart = instruments.minOf { it.window.startMillis() }
        val maxEnd = instruments.maxOf { it.window.endExclusiveMillis() }
        return CompositePeriod(instruments, minStart, maxEnd, label)
    }

    private fun shiftedWindow(today: LocalDate, anchorDay: Int, offset: Int): CycleWindow {
        var w = CycleUtils.windowFor(today, anchorDay)
        repeat(abs(offset)) {
            w = if (offset < 0) CycleUtils.previousWindow(w, anchorDay) else CycleUtils.nextWindow(w, anchorDay)
        }
        return w
    }

    private fun smartLabel(offset: Int): String = when {
        offset == 0 -> "Current Smart Cycle"
        offset == -1 -> "Previous Smart Cycle"
        offset == 1 -> "Next Smart Cycle"
        offset < 0 -> "${-offset} Smart Cycles ago"
        else -> "$offset Smart Cycles ahead"
    }
}
