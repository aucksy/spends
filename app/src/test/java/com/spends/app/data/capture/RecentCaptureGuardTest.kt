package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Phase 4: the in-memory repost / twin-prompt guard. */
class RecentCaptureGuardTest {

    private val t0 = 1_700_000_000_000L

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

    @Test fun message_and_prompt_keys_do_not_collide() {
        val guard = RecentCaptureGuard()
        val msg = guard.messageKey("com.google.android.apps.messaging", "AXISBK", "body")
        val prompt = guard.promptKey("somehash")
        assertThat(msg).isNotEqualTo(prompt)
        assertThat(guard.checkAndMark(msg, ttlMillis = 1_000, now = t0)).isTrue()
        assertThat(guard.checkAndMark(prompt, ttlMillis = 1_000, now = t0)).isTrue()
    }
}
