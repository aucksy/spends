package com.spends.app.data.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind the silent-drop fix.
 *
 * A parsed bank alert that is neither shown nor queued is a real transaction lost with no record
 * anywhere. Until now the SMS path checked only the app's MASTER notification toggle and then returned,
 * so a prompt hidden by the "Transaction detection" CATEGORY alone was handed to Android, quietly
 * binned, and gone. [CaptureNotifier.canPost] is the pure rule both live paths now branch on, and the
 * SMS debug screen reads the same function so the screen can never report a verdict the capture path
 * disagrees with.
 *
 * The importance values are Android's own frozen constants, written as literals here so the test needs
 * no framework: IMPORTANCE_NONE = 0, MIN = 1, LOW = 2, DEFAULT = 3, HIGH = 4.
 */
class CapturePromptVisibilityTest {

    private companion object {
        const val NONE = 0
        const val MIN = 1
        const val LOW = 2
        const val DEFAULT = 3
        const val HIGH = 4
    }

    @Test
    fun `the healthy case posts`() {
        assertTrue(CaptureNotifier.canPost(notificationsEnabled = true, channelImportance = HIGH))
    }

    /**
     * THE regression this fix exists for. One long-press on a prompt and "turn these off" switches this
     * category alone; the app's master toggle keeps reading ON, so the old check passed and every bank
     * SMS from that moment vanished in silence.
     */
    @Test
    fun `the category being switched off blocks the prompt even with notifications on`() {
        assertFalse(CaptureNotifier.canPost(notificationsEnabled = true, channelImportance = NONE))
    }

    @Test
    fun `the master toggle being off blocks the prompt whatever the category says`() {
        assertFalse(CaptureNotifier.canPost(notificationsEnabled = false, channelImportance = HIGH))
        assertFalse(CaptureNotifier.canPost(notificationsEnabled = false, channelImportance = NONE))
        assertFalse(CaptureNotifier.canPost(notificationsEnabled = false, channelImportance = null))
    }

    /**
     * A quiet channel is still a VISIBLE one — it lands in the shade without a heads-up. Treating it as
     * blocked would divert captures to the review queue for users who merely turned the noise down, and
     * silently stop the prompts they still expect to see.
     */
    @Test
    fun `a demoted but not silenced category still posts`() {
        assertTrue(CaptureNotifier.canPost(notificationsEnabled = true, channelImportance = MIN))
        assertTrue(CaptureNotifier.canPost(notificationsEnabled = true, channelImportance = LOW))
        assertTrue(CaptureNotifier.canPost(notificationsEnabled = true, channelImportance = DEFAULT))
    }

    /**
     * Null means pre-Oreo, or a channel not created yet — there is no per-category switch to have been
     * turned off. Treating it as blocked would send a first-ever capture to the queue instead of the
     * prompt on every fresh install.
     */
    @Test
    fun `no channel yet is not the same as a blocked channel`() {
        assertTrue(CaptureNotifier.canPost(notificationsEnabled = true, channelImportance = null))
    }
}
