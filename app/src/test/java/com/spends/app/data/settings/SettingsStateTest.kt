package com.spends.app.data.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The Smart Cycle reset day: 0 = follow the salary day; 1..31 = an explicit pinned day. */
class SettingsStateTest {

    @Test fun `reset day 0 follows the salary day`() {
        val s = SettingsState(salaryCycleStartDay = 25, smartCycleResetDay = 0)
        assertThat(s.effectiveSmartResetDay).isEqualTo(25)
    }

    @Test fun `explicit reset day wins over the salary day`() {
        val s = SettingsState(salaryCycleStartDay = 25, smartCycleResetDay = 10)
        assertThat(s.effectiveSmartResetDay).isEqualTo(10)
    }

    @Test fun `reset day follows a salary day change while unset`() {
        val before = SettingsState(salaryCycleStartDay = 25, smartCycleResetDay = 0)
        val after = before.copy(salaryCycleStartDay = 1)
        assertThat(before.effectiveSmartResetDay).isEqualTo(25)
        assertThat(after.effectiveSmartResetDay).isEqualTo(1)
    }

    @Test fun `out-of-range stored values fall back to the salary day`() {
        // The setter coerces to 0..31, but a foreign/corrupt store must still resolve sanely.
        assertThat(SettingsState(salaryCycleStartDay = 25, smartCycleResetDay = -3).effectiveSmartResetDay).isEqualTo(25)
        assertThat(SettingsState(salaryCycleStartDay = 25, smartCycleResetDay = 32).effectiveSmartResetDay).isEqualTo(25)
    }
}
