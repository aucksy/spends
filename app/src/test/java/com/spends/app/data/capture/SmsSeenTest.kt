package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The catch-up sweep's memory: which texts the live receiver already got, and how far back to look. */
class SmsSeenTest {

    private val hour = 60L * 60 * 1000
    private val now = 1_793_000_000_000L

    // The two texts the live path missed on 31 Oct 2026.
    private val card = "Transaction Successful! INR 130.00 spent on your IDFC FIRST Bank Credit Card ending XX1507 " +
        "at JAIPUR SCHOOL OF YOGA on 31 OCT 2026 at 07:24 PM Avbl Limit: INR 157695.03"
    private val account = "Your A/c XX3452 debited by Rs. 200.00 on 31/10/26; Mr Zaki credited. RRN 219399321719. " +
        "Available balance Rs. 2,61,882.73. Team IDFC FIRST Bank"

    @Test fun broadcast_and_inbox_copies_of_one_text_match() {
        // The receiver joins multipart PDUs; the inbox may differ only in stray whitespace.
        assertThat(SmsSeen.fingerprint(card)).isEqualTo(SmsSeen.fingerprint("  $card\n"))
        assertThat(SmsSeen.fingerprint(card.replace(" spent ", "  spent  "))).isEqualTo(SmsSeen.fingerprint(card))
    }

    @Test fun different_texts_differ() {
        assertThat(SmsSeen.fingerprint(card)).isNotEqualTo(SmsSeen.fingerprint(account))
        assertThat(SmsSeen.fingerprint(account)).isNotEqualTo(SmsSeen.fingerprint(account.replace("200.00", "300.00")))
    }

    @Test fun first_sweep_looks_back_one_day_so_todays_misses_are_found() {
        val (start, end) = SmsSeen.window(lastSweepAt = 0L, now = now)
        assertThat(start).isEqualTo(now - 24 * hour)
        assertThat(end).isEqualTo(now - SmsSeen.SETTLE_MILLIS)
    }

    @Test fun later_sweeps_overlap_the_last_one() {
        val (start, _) = SmsSeen.window(lastSweepAt = now - hour, now = now)
        assertThat(start).isEqualTo(now - hour - SmsSeen.OVERLAP_MILLIS)
    }

    @Test fun a_long_unopened_app_never_looks_back_more_than_two_days() {
        val (start, _) = SmsSeen.window(lastSweepAt = now - 30 * 24 * hour, now = now)
        assertThat(start).isEqualTo(now - SmsSeen.MAX_LOOKBACK_MILLIS)
    }

    @Test fun the_list_keeps_the_newest_and_never_repeats() {
        var list = emptyList<String>()
        for (i in 1..5) list = SmsSeen.appendCapped(list, "f$i", max = 3)
        assertThat(list).containsExactly("f3", "f4", "f5").inOrder()
        assertThat(SmsSeen.appendCapped(list, "f4", max = 3)).isSameInstanceAs(list)
    }
}
