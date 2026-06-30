package com.spends.app.core.period

import com.google.common.truth.Truth.assertThat
import com.spends.app.core.time.CycleUtils
import com.spends.app.core.time.DateUtils
import org.junit.Test
import java.time.LocalDate

/**
 * Money-critical: the Smart Cycle composite decides which transactions are SUMMED into every total
 * (timeline, analytics, breakdown, widget). A txn belongs iff it sits in ITS OWN instrument's window —
 * a card by its billing cycle, Bank/UPI by the salary cycle. These tests pin the membership + boundary
 * semantics so an off-by-one can never silently mis-total real money.
 */
class CompositeCycleResolverTest {

    private val today = LocalDate.of(2026, 6, 20)
    private val salaryDay = 1
    private val cardBillingDay = 15

    private fun noon(y: Int, m: Int, d: Int): Long = DateUtils.epochMillisFor(LocalDate.of(y, m, d))

    @Test fun composite_has_bank_plus_each_card() {
        val cards = listOf(CardCycleInfo(1L, "HDFC", "#FFFFFF", "1234", cardBillingDay))
        val c = CompositeCycleResolver.resolveSmartCycle(cards, salaryDay, today, cycleOffset = 0)
        // Exactly the Bank bucket (null) + the one card.
        assertThat(c.instruments.map { it.paymentMethodId }).containsExactly(null, 1L)
    }

    @Test fun each_txn_is_judged_against_its_own_instrument_window() {
        val cards = listOf(CardCycleInfo(1L, "HDFC", "#FFFFFF", "1234", cardBillingDay))
        val c = CompositeCycleResolver.resolveSmartCycle(cards, salaryDay, today, cycleOffset = 0)

        val bankW = CycleUtils.windowFor(today, salaryDay)   // 1 Jun .. 1 Jul
        val cardW = CycleUtils.windowFor(today, cardBillingDay) // 15 Jun .. 15 Jul

        // A bank (no-card) txn inside the salary window is IN; a card txn inside the card window is IN.
        assertThat(c.contains(bankW.startMillis(), null)).isTrue()
        assertThat(c.contains(cardW.startMillis(), 1L)).isTrue()

        // 5 Jun sits in the salary window but BEFORE the card's window. A bank txn there counts; the SAME
        // date for the card does NOT — it's last cycle for that card. This is the off-by-one guard.
        assertThat(c.contains(noon(2026, 6, 5), null)).isTrue()
        assertThat(c.contains(noon(2026, 6, 5), 1L)).isFalse()

        // End is EXCLUSIVE — a txn exactly at endExclusiveMillis() belongs to the NEXT cycle, not this one.
        assertThat(c.contains(bankW.endExclusiveMillis(), null)).isFalse()
        assertThat(c.contains(cardW.endExclusiveMillis(), 1L)).isFalse()
    }

    @Test fun unknown_or_deleted_card_falls_back_to_the_bank_bucket() {
        val cards = listOf(CardCycleInfo(1L, "HDFC", "#FFFFFF", "1234", cardBillingDay))
        val c = CompositeCycleResolver.resolveSmartCycle(cards, salaryDay, today, cycleOffset = 0)
        // instrumentIdFor must mirror contains()'s fallback so the breakdown groups a deleted-card txn
        // under Bank exactly the way the totals count it.
        assertThat(c.instrumentIdFor(1L)).isEqualTo(1L)
        assertThat(c.instrumentIdFor(null)).isNull()
        assertThat(c.instrumentIdFor(999L)).isNull()
        // A txn tagged to a now-deleted card (999) is judged against the BANK window.
        assertThat(c.contains(noon(2026, 6, 5), 999L)).isTrue() // 5 Jun is in the salary window
    }

    @Test fun single_card_excludes_the_bank_bucket() {
        val card = CardCycleInfo(1L, "HDFC", "#FFFFFF", "1234", cardBillingDay)
        val c = CompositeCycleResolver.resolveSingleCard(card, salaryDay, today, cycleOffset = 0)
        val cardW = CycleUtils.windowFor(today, cardBillingDay)
        // Only the one card is an instrument — its own txns count, but a bank (null) txn has no window → out.
        assertThat(c.contains(cardW.startMillis(), 1L)).isTrue()
        assertThat(c.contains(cardW.startMillis(), null)).isFalse()
    }

    @Test fun card_with_no_billing_day_rides_the_salary_cycle_but_stays_its_own_instrument() {
        val cards = listOf(CardCycleInfo(2L, "Amazon Pay", "#000000", "9999", billingDay = null))
        val c = CompositeCycleResolver.resolveSmartCycle(cards, salaryDay, today, cycleOffset = 0)
        val bankW = CycleUtils.windowFor(today, salaryDay)
        // It uses the salary window (same dates as Bank) but a txn tagged to it maps to card id 2, NOT the
        // Bank bucket — so it is counted once under the card, never double-counted.
        assertThat(c.contains(bankW.startMillis(), 2L)).isTrue()
        assertThat(c.instrumentIdFor(2L)).isEqualTo(2L)
    }
}
