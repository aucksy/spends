package com.spends.app.ui.capture

import com.spends.app.data.capture.SmsDebugLog
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict line is the whole point of the SMS debug screen — it is the one sentence the owner reads
 * and reports back. It must never contradict the counters printed underneath it, and it must never
 * blame the app for something the phone is doing (or the reverse).
 *
 * Order is load-bearing: each branch assumes the ones above it passed.
 */
class SmsVerdictTest {

    private fun snapshot(received: Int = 0, fromBanks: Int = 0) =
        SmsDebugLog.Snapshot(
            totalReceived = received,
            lastReceivedAt = if (received > 0) 1_000L else null,
            fromKnownBanks = fromBanks,
            entries = emptyList(),
        )

    private fun verdict(
        permission: Boolean = true,
        capture: Boolean = true,
        prompts: Boolean = true,
        received: Int = 5,
        fromBanks: Int = 2,
    ) = smsVerdictOf(permission, capture, prompts, snapshot(received, fromBanks))

    @Test
    fun `a missing permission is named first, before anything downstream`() {
        // Every downstream signal is healthy; the permission must still win, because nothing below it
        // can be trusted when Android isn't letting the app read SMS at all.
        val msg = verdict(permission = false)

        assertTrue(msg.contains("permission"))
        assertTrue(msg.contains("Settings"))
    }

    @Test
    fun `the switch being off is reported as the switch, not as a broken phone`() {
        val msg = verdict(capture = false)

        assertTrue(msg.contains("Detect from bank SMS"))
        assertTrue(msg.contains("off"))
    }

    /**
     * THE verdict this screen was built for. Zero delivered messages, with everything inside the app
     * healthy, means the cause is outside the app — and the sentence has to say so plainly, or the next
     * investigation goes hunting through capture code again.
     */
    @Test
    fun `nothing delivered points outside the app, explicitly`() {
        val msg = verdict(received = 0, fromBanks = 0)

        assertTrue(msg.contains("not delivering"))
        assertTrue("must rule out the app itself", msg.contains("nothing inside Spends"))
    }

    @Test
    fun `messages arriving but no bank recognised points at the sender list`() {
        val msg = verdict(received = 40, fromBanks = 0)

        assertTrue(msg.contains("40"))
        assertTrue(msg.contains("recognises"))
    }

    /** Reassurance matters here: nothing is lost, it goes to the review queue. Say so. */
    @Test
    fun `blocked prompts are reported as blocked prompts and as recoverable`() {
        val msg = verdict(prompts = false, received = 40, fromBanks = 6)

        assertTrue(msg.contains("Transaction detection"))
        assertTrue(msg.contains("review queue"))
        assertTrue(msg.contains("nothing is lost"))
    }

    @Test
    fun `all healthy defers to the per-message list`() {
        val msg = verdict()

        assertTrue(msg.contains("where it stopped"))
    }

    /**
     * A verdict that can't tell two opposite states apart is worse than none: it invites a wrong
     * conclusion with full confidence. Every branch must be distinguishable.
     */
    @Test
    fun `every branch produces a distinct sentence`() {
        val all = listOf(
            verdict(permission = false),
            verdict(capture = false),
            verdict(received = 0, fromBanks = 0),
            verdict(received = 40, fromBanks = 0),
            verdict(prompts = false, received = 40, fromBanks = 6),
            verdict(),
        )

        assertTrue("branches collapsed into the same wording", all.toSet().size == all.size)
    }
}
