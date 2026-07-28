package com.spends.app.ui.capture

import com.spends.app.data.capture.NotificationDebugLog
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

    private fun snapshot(
        received: Int = 0,
        fromBanks: Int = 0,
        lastReceivedAt: Long? = null,
        appNotReady: Int = 0,
    ) = SmsDebugLog.Snapshot(
        totalReceived = received,
        lastReceivedAt = lastReceivedAt,
        fromKnownBanks = fromBanks,
        // Entries are a verdict INPUT, so the factory has to be able to express them. Hard-coding
        // emptyList() is what let the app-fault branch ship keyed on a counter instead: the state where
        // failures are visible on screen was unreachable from this file.
        entries = List(appNotReady) {
            SmsDebugLog.Entry(
                timeMillis = 1_000L + it,
                sender = "AD-HDFCBK",
                institution = "HDFC Bank",
                body = null,
                outcome = SmsDebugLog.Outcome.APP_NOT_READY,
                detail = null,
            )
        },
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
        appNotReady: Int = 0,
    ) = smsVerdictOf(
        receive, demo, capture, prompts, graphFailures,
        snapshot(received, fromBanks, lastReceivedAt, appNotReady),
    )

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
     * The counter never decays, so an unscoped branch LATCHED: one transient failure during cold start
     * permanently suppressed every diagnosis below it for the rest of the app run. The blocked-prompt
     * case is actionable and independent of the fault, so it must still win.
     */
    @Test
    fun `a past start-up failure does not hijack an actionable diagnosis`() {
        // No APP_NOT_READY rows on screen, so the fault is history and the live diagnosis wins.
        val blocked = verdict(graphFailures = 1, prompts = false, received = 40, fromBanks = 6)

        assertTrue("the blocked-prompt diagnosis must survive", blocked.contains("Transaction detection"))
    }

    /**
     * ⭐ THE round-6 blocker, and the sharpest false claim the screen could make. A corrupt database
     * delivering only personal texts reached the blocked-prompt branch, which asserts four things —
     * bank alerts are arriving, they are being read, they are being queued, nothing is lost — and ALL
     * FOUR were false: nothing resolved to a bank, the graph never built, and every message was dropped.
     * It contradicted the two rows printed directly beneath it ("from a bank we recognise: 0" and
     * "App start-up failures: 40"). No test covered fromBanks == 0 with prompts blocked, which is why
     * it survived two rounds.
     */
    @Test
    fun `a corrupt start-up with prompts blocked never claims nothing is lost`() {
        val msg = verdict(graphFailures = 40, received = 40, fromBanks = 0, prompts = false, appNotReady = 40)

        assertTrue("must never claim recovery it did not perform", !msg.contains("nothing is lost"))
        assertTrue("must not claim alerts were read", !msg.contains("being read"))
        assertTrue(msg.contains("fault inside Spends"))
    }

    /**
     * ⭐ The round-6 sentence, pinned by ORDER alone — and until now nothing pinned the order.
     *
     * The test above sets `appNotReady = 40`, so the app-fault branch fires first and HIDES which branch
     * would otherwise have caught this. Swapping the sender-advice branch below the blocked-prompt one
     * resurrected "Bank alerts are arriving and being read … so nothing is lost" verbatim, with
     * `fromKnownBanks == 0`, and the whole suite stayed green. This is the same state with no fault
     * recorded, so only the ordering can be what answers it.
     */
    @Test
    fun `no bank recognised outranks blocked prompts, on order alone`() {
        val msg = verdict(prompts = false, received = 40, fromBanks = 0, appNotReady = 0)

        assertTrue("must never claim alerts were read when none resolved", !msg.contains("being read"))
        assertTrue("nor claim a recovery it did not perform", !msg.contains("nothing is lost"))
        assertTrue(msg.contains("recognises"))
    }

    /** The permission branch must outrank the switch branch: its sentence asserts the permission IS
     *  granted, which is false when it isn't. No test set both false, so the order was unpinned. */
    @Test
    fun `a missing permission outranks the switch being off`() {
        val msg = verdict(receive = false, capture = false)

        assertTrue(msg.contains("permission to receive SMS"))
        assertTrue("must not assert a grant that isn't there", !msg.contains("permission is granted"))
    }

    /**
     * The other half: while the failures are VISIBLE the fault outranks the sender advice, and once they
     * are gone — cleared, or aged out of the 60-entry ring — the advice comes back. Keying on the
     * counter could not express this, because the counter never decays.
     */
    @Test
    fun `the fault verdict decays with its own evidence`() {
        val visible = verdict(graphFailures = 3, received = 40, fromBanks = 0, appNotReady = 3)
        val aged = verdict(graphFailures = 3, received = 40, fromBanks = 0, appNotReady = 0)

        assertTrue(visible.contains("cannot start up properly"))
        assertTrue("the live diagnosis returns once the evidence is gone", aged.contains("one-line fix"))
    }

    /**
     * ⭐ The other half of that trade, and an earlier version of this file asserted the OPPOSITE.
     *
     * `recordReceived()` runs before the dependency-provisioning guard, so a failure to open the Room
     * database leaves `totalReceived > 0` with `fromKnownBanks == 0` — and the verdict told the owner
     * their bank's sender name had changed and needed "a one-line fix", above an empty list, while their
     * database was corrupt. That advice is only safe when every message was actually inspected, and a
     * recorded fault says they were not. The test that pinned the wrong answer is now inverted.
     */
    @Test
    fun `the sender advice is withheld while a start-up fault is recorded`() {
        val msg = verdict(graphFailures = 1, received = 40, fromBanks = 0, appNotReady = 1)

        assertTrue("must not send the owner to edit the allowlist", !msg.contains("one-line fix"))
        assertTrue(msg.contains("cannot start up properly"))
    }

    /** An all-clear must never be given over a recorded fault. */
    @Test
    fun `a recorded start-up fault suppresses the all-clear`() {
        val msg = verdict(graphFailures = 3, received = 40, fromBanks = 6)

        assertTrue("must not read as healthy", !msg.contains("Each message below"))
        assertTrue(msg.contains("3"))
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

        // The same state WITH a recorded start-up fault. `ReceiverFailures` is deliberately never reset,
        // so this is reachable the moment the owner taps Clear after a fault — and without the
        // `lastReceivedAt == null` term the early branch claims texts arrived that this run never saw.
        val afterClearWithFault = verdict(graphFailures = 2, received = 0, fromBanks = 0, lastReceivedAt = 3_000L)
        assertTrue(afterClearWithFault.contains("cleared"))
        assertTrue(!afterClearWithFault.contains("reached Spends but the app failed"))
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
     * ⭐ The empty-message-list sentence, which lived inline in the screen and therefore had no test at
     * all — and branched on `totalReceived` ALONE, the exact defect corrected in the verdict across four
     * rounds. In two reachable states it asserted the OPPOSITE of the card printed a few dp above it.
     */
    @Test
    fun `the empty list never contradicts the verdict above it`() {
        // A graph failure records nothing and never calls recordReceived(), so the verdict says "a fault
        // inside Spends" while this line used to say "Android isn't delivering SMS to Spends at all" —
        // the precise claim ReceiverFailures exists to prevent.
        val fault = smsEmptyStateOf(totalReceived = 0, graphFailures = 40, lastReceivedAt = null)
        assertTrue("must not blame delivery over a recorded fault", !fault.contains("isn't delivering"))
        assertTrue(fault.contains("couldn't start up"))

        // After Clear the verdict says "delivery was working" beside a kept timestamp.
        val cleared = smsEmptyStateOf(totalReceived = 0, graphFailures = 0, lastReceivedAt = 3_000L)
        assertTrue("must not deny the timestamp shown above", !cleared.contains("isn't delivering"))
        assertTrue(cleared.contains("cleared"))

        // The genuine case survives untouched — this is the whole point of the screen.
        val nothing = smsEmptyStateOf(totalReceived = 0, graphFailures = 0, lastReceivedAt = null)
        assertTrue(nothing.contains("isn't delivering"))

        val counted = smsEmptyStateOf(totalReceived = 7, graphFailures = 0, lastReceivedAt = 1_000L)
        assertTrue(counted.contains("7"))

        // ⭐ The state the first version of this test did not cover, and the one where the two lines
        // could still disagree: a fault recorded, THEN Clear tapped. `graphFailures` is never reset, so
        // this is reachable in one tap. The verdict says "delivery was working"; this must agree.
        // At the BOUNDARY too: `graphFailures > 0` could be weakened to `> 1` with the suite green, and
        // ONE cold-start hiccup is the commonest case — there the empty line would read "Android isn't
        // delivering" while the verdict reads "1 text reached Spends but the app failed to start up".
        val oneFault = smsEmptyStateOf(totalReceived = 0, graphFailures = 1, lastReceivedAt = null)
        assertTrue(oneFault.contains("couldn't start up"))
        assertTrue("must not blame delivery at the boundary", !oneFault.contains("isn't delivering"))

        val faultThenCleared = smsEmptyStateOf(totalReceived = 0, graphFailures = 2, lastReceivedAt = 3_000L)
        assertTrue(faultThenCleared.contains("cleared"))
        assertTrue("must not point at a verdict that names no fault", !faultThenCleared.contains("couldn't start up"))

        assertTrue("all four states distinct", setOf(fault, cleared, nothing, counted).size == 4)
    }

    /**
     * `plainSmsOutcome` had no test of any kind, so the per-message line could name any cause at all.
     * The closed list "(OTP / promo / statement)" was false for a real SBI UPI debit — a genuine money
     * movement the parser cannot read — which is why the arm must stay open-ended.
     */
    @Test
    fun `every outcome has a distinct plain-English line, and the parse failure stays open-ended`() {
        val all = SmsDebugLog.Outcome.entries.map { plainSmsOutcome(it) }

        assertTrue("no blank lines", all.none { it.isBlank() })
        assertTrue("no two outcomes read the same", all.toSet().size == all.size)

        val notTxn = plainSmsOutcome(SmsDebugLog.Outcome.NOT_A_TRANSACTION)
        assertTrue("must not close the list", !notTxn.trimEnd().endsWith("statement)"))
        assertTrue(notTxn.contains("can't parse"))

        // The SMS screen's twin line had the mirror-image defect and no ban at all. With notification
        // capture OFF — the default — an OEM re-delivering one SMS twice reaches this with no
        // notification involved and no listener running.
        val twin = plainSmsOutcome(SmsDebugLog.Outcome.TWIN_ALREADY_PROMPTED)
        assertTrue("must not name a channel it cannot know", !twin.contains("notification twin"))
        assertTrue(twin.contains("A twin of this alert"))
    }

    /**
     * The NOTIFICATION screen's twin, which had no test at all. Every claim fixed on the SMS screen was
     * still false here one round later — the closed parse-failure list, "this is the RCS limit" naming a
     * cause the code cannot know, and `DUPLICATE` asserting "a transaction we already have" at the twin
     * race, where nothing is held: the winner posted a heads-up the owner may dismiss and wrote nothing.
     * Fixing the instance and leaving the class is the mistake this feature keeps repeating.
     */
    @Test
    fun `the notification screen makes no claim it cannot know either`() {
        val all = NotificationDebugLog.Outcome.entries.map { plainOutcome(it) }

        assertTrue("no blank lines", all.none { it.isBlank() })
        assertTrue("no two outcomes read the same", all.toSet().size == all.size)

        val notTxn = plainOutcome(NotificationDebugLog.Outcome.NOT_A_TRANSACTION)
        assertTrue("must not close the list", !notTxn.trimEnd().endsWith("statement)"))

        val noText = plainOutcome(NotificationDebugLog.Outcome.NO_READABLE_TEXT)
        assertTrue("must not assert RCS as THE cause", !noText.contains("this is the RCS limit"))

        val dup = plainOutcome(NotificationDebugLog.Outcome.DUPLICATE)
        assertTrue("must not claim a holding it may not have", !dup.contains("we already have"))
        // Banning the removed WORDS is not the same as pinning the claim: the round-11 blocker could be
        // restored word-for-word-in-meaning with only that assertion. It must also not name the CHANNEL —
        // on an RCS-only alert, the case notification capture exists for, the twin is another
        // notification and no SMS exists at all.
        assertTrue("must not name a channel it cannot know", !dup.contains("SMS twin"))
        assertTrue(dup.contains("another copy of the same alert"))

        val queued = plainOutcome(NotificationDebugLog.Outcome.QUEUED)
        assertTrue("no green tick for a prompt the owner never saw", !queued.contains("✅"))
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
            verdict(graphFailures = 2, received = 40, fromBanks = 6),
            verdict(graphFailures = 2, received = 40, fromBanks = 6, appNotReady = 2),
            verdict(),
        )

        assertTrue("branches collapsed into the same wording", all.toSet().size == all.size)
    }
}
