package com.spends.app.ui.capture

import com.spends.app.data.capture.SmsDebugLog
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KFunction6

/**
 * The verdict line is the whole point of the SMS debug screen — it is the one sentence the owner reads
 * and reports back. It must never contradict the counters printed underneath it, and it must never
 * blame the app for something the phone is doing (or the reverse).
 *
 * Order is load-bearing: each branch assumes the ones above it passed.
 *
 * **The snapshot factory takes `lastReceivedAt` independently of `received` on purpose.** The first
 * version of this test derived it (`if (received > 0) 1_000L else null`), which made the post-`clear()`
 * state — count zeroed, timestamp deliberately kept — structurally unreachable, and no test here could
 * ever have caught the verdict lying about it. A factory that cannot express the defect is a blind spot,
 * not a convenience.
 */
class SmsVerdictTest {

    private fun snapshot(received: Int = 0, fromBanks: Int = 0, lastReceivedAt: Long? = null) =
        SmsDebugLog.Snapshot(
            totalReceived = received,
            lastReceivedAt = lastReceivedAt,
            fromKnownBanks = fromBanks,
            entries = emptyList(),
        )

    private fun verdict(
        receive: Boolean = true,
        demo: Boolean = false,
        capture: Boolean = true,
        prompts: Boolean = true,
        graphFailures: Int = 0,
        received: Int = 5,
        fromBanks: Int = 2,
        lastReceivedAt: Long? = 1_000L,
    ) = smsVerdictOf(receive, demo, capture, prompts, graphFailures, snapshot(received, fromBanks, lastReceivedAt))

    /**
     * Demo mode outranks everything, including a missing permission: while it is on, no other line on
     * the screen is a statement about the owner's real setup, so every branch below would name a cause
     * that isn't the cause. Demo mode was a live suspect in the July 2026 investigation.
     */
    @Test
    fun `demo mode wins over every other branch`() {
        val msg = verdict(demo = true, receive = false, capture = false, prompts = false, received = 0)

        assertTrue(msg.contains("Demo mode is on"))
        assertTrue("must say how to get out of it", msg.contains("Settings"))
    }

    @Test
    fun `a missing receive permission is named before anything downstream`() {
        val msg = verdict(receive = false)

        assertTrue(msg.contains("permission"))
        assertTrue(msg.contains("Settings"))
    }

    /**
     * READ_SMS is used only by "Scan past SMS". Live capture needs RECEIVE_SMS alone, and OEM permission
     * managers list the two separately — so a missing READ_SMS must NOT produce "nothing can arrive"
     * beside a counter showing forty messages arriving.
     *
     * There is nothing to assert here, because the input does not exist; the guard is the declared type.
     * Two earlier attempts were both green under their own defect. A plain assertion could not see the
     * parameter at all, and then a function TYPE (`(Boolean, …) -> String`) did not help either: since
     * Kotlin 1.4 a callable reference whose trailing parameters are all defaulted ADAPTS to a shorter
     * function type, so adding `readGranted: Boolean = true` — precisely the defect — still compiled.
     *
     * `KFunction6` is not subject to that adaptation: it can only be satisfied by a declaration of
     * exactly this arity, so any new parameter, defaulted or not, breaks the build here.
     */
    @Test
    fun `the verdict signature cannot take a READ_SMS input`() {
        val pinned: KFunction6<Boolean, Boolean, Boolean, Boolean, Int, SmsDebugLog.Snapshot, String> =
            ::smsVerdictOf

        assertTrue(pinned(true, false, true, true, 0, snapshot(40, 6, 1_000L)).contains("where it stopped"))
    }

    /**
     * A broadcast that reached the receiver but died before anything could be recorded leaves the
     * counter at zero — where the branch below would assert "nothing inside Spends can be the cause",
     * in the one case where Spends IS the cause. This must outrank it.
     */
    @Test
    fun `an app start-up failure is blamed on the app, not on Android`() {
        val msg = verdict(graphFailures = 2, received = 0, fromBanks = 0, lastReceivedAt = null)

        assertTrue("must not blame delivery", !msg.contains("not delivering"))
        assertTrue(msg.contains("fault inside Spends"))
        assertTrue(msg.contains("2"))
    }

    /**
     * ⭐ The counter never decays, so an unscoped branch LATCHED: one transient failure during cold
     * start permanently suppressed the two actionable diagnoses below it — the unrecognised-sender case
     * and the blocked-prompt case — for the rest of the app run, while messages flowed in normally.
     * Same defect class as the un-split `lastReceivedAt`: a latching condition asserting a stale cause.
     */
    @Test
    fun `a past start-up failure does not hijack the verdict once messages are arriving`() {
        val senders = verdict(graphFailures = 1, received = 40, fromBanks = 0)
        val blocked = verdict(graphFailures = 1, prompts = false, received = 40, fromBanks = 6)

        assertTrue("the sender diagnosis must survive", senders.contains("recognises"))
        assertTrue("the blocked-prompt diagnosis must survive", blocked.contains("Transaction detection"))
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
    fun `nothing ever delivered points outside the app, explicitly`() {
        val msg = verdict(received = 0, fromBanks = 0, lastReceivedAt = null)

        assertTrue(msg.contains("not delivering"))
        assertTrue("must rule out the app itself", msg.contains("nothing inside Spends"))
    }

    /**
     * THE regression. `clear()` zeroes the count and deliberately KEEPS the timestamp — so branching on
     * the count alone printed "Android is not delivering them to the app" directly above a row reading
     * "Last one reached the app: 3:00 pm". That is the screen asserting, with maximum confidence, the
     * exact conclusion it exists to establish — immediately after the owner's most natural first tap.
     */
    @Test
    fun `zero count with a kept timestamp must not claim delivery is broken`() {
        val msg = verdict(received = 0, fromBanks = 0, lastReceivedAt = 3_000L)

        assertTrue("must not blame delivery", !msg.contains("not delivering"))
        assertTrue("must not deny the timestamp shown beneath it", !msg.contains("Not one SMS"))
        assertTrue(msg.contains("cleared"))
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
        assertTrue(verdict().contains("where it stopped"))
    }

    /**
     * A verdict that can't tell two opposite states apart is worse than none: it invites a wrong
     * conclusion with full confidence. Every branch must be distinguishable.
     */
    @Test
    fun `every branch produces a distinct sentence`() {
        val all = listOf(
            verdict(demo = true),
            verdict(receive = false),
            verdict(capture = false),
            verdict(graphFailures = 2, received = 0, fromBanks = 0, lastReceivedAt = null),
            verdict(received = 0, fromBanks = 0, lastReceivedAt = null),
            verdict(received = 0, fromBanks = 0, lastReceivedAt = 3_000L),
            verdict(received = 40, fromBanks = 0),
            verdict(prompts = false, received = 40, fromBanks = 6),
            verdict(),
        )

        assertTrue("branches collapsed into the same wording", all.toSet().size == all.size)
    }
}
