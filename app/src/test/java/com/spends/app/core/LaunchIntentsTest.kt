package com.spends.app.core

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Review & Add" opened on an OLDER transaction: after process death Android handed MainActivity the
 * notification that first opened the task, and onCreate re-parsed that old alert alongside the new tap.
 * Only a genuinely fresh launch may act on the launch intent's one-shot extras.
 */
class LaunchIntentsTest {

    @Test fun freshLaunchActs() {
        assertTrue(LaunchIntents.isFreshLaunch(hasSavedState = false, intentFlags = Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test fun recreateAfterProcessDeathDoesNotAct() {
        assertFalse(LaunchIntents.isFreshLaunch(hasSavedState = true, intentFlags = Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test fun relaunchFromRecentsDoesNotAct() {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        assertFalse(LaunchIntents.isFreshLaunch(hasSavedState = false, intentFlags = flags))
    }
}
