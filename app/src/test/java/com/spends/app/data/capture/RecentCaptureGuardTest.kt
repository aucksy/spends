package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Phase 4: the in-memory repost guard + the twin-prompt claim. */
class RecentCaptureGuardTest {

    private val t0 = 1_700_000_000_000L

    // ---- checkAndMark (repost guard) ----

    @Test fun first_check_passes_repeat_within_ttl_blocks() {
        val guard = RecentCaptureGuard()
        assertThat(guard.checkAndMark("k", ttlMillis = 1_000, now = t0)).isTrue()
        assertThat(guard.checkAndMark("k", ttlMillis = 1_000, now = t0 + 500)).isFalse()
    }

    @Test fun after_ttl_the_key_is_fresh_again() {
        val guard = RecentCaptureGuard()
        assertThat(guard.checkAndMark("k", ttlMillis = 1_000, now = t0)).isTrue()
        assertThat(guard.checkAndMark("k", ttlMillis = 1_000, now = t0 + 1_001)).isTrue()
    }

    @Test fun distinct_keys_are_independent() {
        val guard = RecentCaptureGuard()
        assertThat(guard.checkAndMark("a", ttlMillis = 1_000, now = t0)).isTrue()
        assertThat(guard.checkAndMark("b", ttlMillis = 1_000, now = t0)).isTrue()
    }

    @Test fun blocked_check_does_not_extend_the_window() {
        val guard = RecentCaptureGuard()
        assertThat(guard.checkAndMark("k", ttlMillis = 1_000, now = t0)).isTrue()
        assertThat(guard.checkAndMark("k", ttlMillis = 1_000, now = t0 + 900)).isFalse()
        // 1_000 after the ORIGINAL mark (not the blocked re-check) the key is fresh.
        assertThat(guard.checkAndMark("k", ttlMillis = 1_000, now = t0 + 1_001)).isTrue()
    }

    // ---- claimPrompt (twin collapse across the SMS + notification live paths) ----

    @Test fun identical_twin_same_relaxed_hash_same_ref_is_suppressed() {
        val guard = RecentCaptureGuard()
        assertThat(guard.claimPrompt("R1", refNumber = "REF9", now = t0)).isTrue()
        assertThat(guard.claimPrompt("R1", refNumber = "REF9", now = t0 + 60_000)).isFalse()
    }

    @Test fun ref_loss_twin_one_side_missing_ref_is_suppressed_both_directions() {
        val withRefFirst = RecentCaptureGuard()
        assertThat(withRefFirst.claimPrompt("R1", refNumber = "REF9", now = t0)).isTrue()
        assertThat(withRefFirst.claimPrompt("R1", refNumber = null, now = t0 + 60_000)).isFalse()

        val noRefFirst = RecentCaptureGuard()
        assertThat(noRefFirst.claimPrompt("R1", refNumber = null, now = t0)).isTrue()
        assertThat(noRefFirst.claimPrompt("R1", refNumber = "REF9", now = t0 + 60_000)).isFalse()
    }

    @Test fun provably_distinct_transactions_refs_differ_both_prompt() {
        val guard = RecentCaptureGuard()
        assertThat(guard.claimPrompt("R1", refNumber = "REF1", now = t0)).isTrue()
        // Same relaxed identity (same day/amount/card) but a DIFFERENT reference number — a genuine
        // second purchase, not a twin: it must prompt too.
        assertThat(guard.claimPrompt("R1", refNumber = "REF2", now = t0 + 60_000)).isTrue()
    }

    @Test fun different_relaxed_hashes_never_collide() {
        val guard = RecentCaptureGuard()
        assertThat(guard.claimPrompt("R1", refNumber = null, now = t0)).isTrue()
        assertThat(guard.claimPrompt("R2", refNumber = null, now = t0)).isTrue()
    }

    @Test fun twin_claim_reopens_after_the_prompt_ttl() {
        val guard = RecentCaptureGuard()
        assertThat(guard.claimPrompt("R1", refNumber = null, now = t0)).isTrue()
        assertThat(
            guard.claimPrompt("R1", refNumber = null, now = t0 + RecentCaptureGuard.PROMPT_TTL_MILLIS + 1),
        ).isTrue()
    }

    @Test fun message_keys_differ_per_app_and_body() {
        val guard = RecentCaptureGuard()
        val a = guard.messageKey("com.google.android.apps.messaging", "AXISBK", "body one")
        val b = guard.messageKey("com.truecaller", "AXISBK", "body one")
        val c = guard.messageKey("com.google.android.apps.messaging", "AXISBK", "body two")
        assertThat(a).isNotEqualTo(b)
        assertThat(a).isNotEqualTo(c)
    }
}
