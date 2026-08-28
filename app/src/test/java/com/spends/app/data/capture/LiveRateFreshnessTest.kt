package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import com.spends.app.data.capture.SmsCaptureRepository.Companion.STALE_FOR_LIVE_RATE_MILLIS
import com.spends.app.data.capture.SmsCaptureRepository.Companion.isTooOldForALiveRate
import org.junit.Test

/**
 * Today's exchange rate may only be applied to today's alert.
 *
 * The model is asked one question — "how many rupees is 1 ringgit **right now**" — so its answer is only
 * honest about a message from around now. v1.70.0 passed the wall clock for every capture, including
 * ones a historical inbox scan dug out of 2019, and a *converted* row is then bulk-committable by
 * "Add all" in a single tap with no review. Over the seven years this release was validated against, the
 * ringgit moved more than a tenth: those rows would have been quietly, confidently wrong.
 *
 * A message that is too old is marked unconverted instead, which every no-editor commit path already
 * refuses — so the amount ends up set by a human rather than by a rate from the wrong decade.
 */
class LiveRateFreshnessTest {

    private val now = 1_754_000_000_000L
    private val oneMinute = 60_000L
    private val oneDay = 24 * 60 * 60 * 1000L

    @Test fun a_live_alert_converts() {
        // The case the whole feature exists for: a card alert arriving abroad, seconds old.
        assertThat(isTooOldForALiveRate(now, now)).isFalse()
        assertThat(isTooOldForALiveRate(now - 2_000, now)).isFalse()
        assertThat(isTooOldForALiveRate(now - oneMinute, now)).isFalse()
    }

    @Test fun an_alert_from_yesterday_still_converts() {
        // A phone that was off, in flight mode, or out of signal overnight must not lose conversion for a
        // charge made on the trip it is on. That is why the window is days, not hours.
        assertThat(isTooOldForALiveRate(now - oneDay, now)).isFalse()
        assertThat(isTooOldForALiveRate(now - STALE_FOR_LIVE_RATE_MILLIS, now)).isFalse()
    }

    @Test fun an_alert_from_last_year_does_not() {
        assertThat(isTooOldForALiveRate(now - STALE_FOR_LIVE_RATE_MILLIS - 1, now)).isTrue()
        assertThat(isTooOldForALiveRate(now - 365 * oneDay, now)).isTrue()
        // The 2019 ringgit charge from the brief.
        assertThat(isTooOldForALiveRate(1_567_000_000_000L, now)).isTrue()
    }

    @Test fun a_clock_drifting_slightly_ahead_is_not_treated_as_history() {
        // Phone and network clocks disagree by seconds routinely. A message timestamped a little in the
        // future is plainly a live alert; a negative age must not wrap round into "stale".
        assertThat(isTooOldForALiveRate(now + oneMinute, now)).isFalse()
        assertThat(isTooOldForALiveRate(now + oneDay, now)).isFalse()
    }

    @Test fun the_window_is_generous_enough_to_cover_a_trip_interruption() {
        // Guards the constant itself against being tightened to something that would break the real use
        // case — a traveller whose phone had no data for a day or two.
        assertThat(STALE_FOR_LIVE_RATE_MILLIS).isAtLeast(oneDay)
    }
}
