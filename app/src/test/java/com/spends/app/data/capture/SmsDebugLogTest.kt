package com.spends.app.data.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SMS diagnostic exists to answer one question the phone cannot: is Android delivering bank texts
 * to Spends at all? These pin the two things that would make it lie — a counter that drifts, and the
 * privacy rules that decide what may be recorded.
 */
class SmsDebugLogTest {

    private fun log() = SmsDebugLog()

    // ---- the load-bearing counter ----

    @Test
    fun `every received message is counted, before any other check`() {
        val log = log()
        repeat(5) { log.recordReceived(now = 1_000L + it) }

        assertEquals(5, log.state.value.totalReceived)
        assertEquals(1_004L, log.state.value.lastReceivedAt)
    }

    @Test
    fun `no message received leaves the counter at zero and no last-received time`() {
        val s = log().state.value
        assertEquals(0, s.totalReceived)
        assertNull(s.lastReceivedAt)
    }

    /**
     * The single most useful line on the screen is "when did an SMS last reach the app". Wiping the list
     * must not wipe that too — it would read as "none ever has", which is the opposite of the truth and
     * the exact conclusion the screen exists to prevent.
     */
    @Test
    fun `clear resets the counters but keeps when an SMS last arrived`() {
        val log = log()
        log.recordReceived(now = 9_000L)
        log.record(1L, "AD-HDFCBK", "HDFC", "Rs 100 debited", SmsDebugLog.Outcome.PROMPTED)

        log.clear()

        val s = log.state.value
        assertEquals(0, s.totalReceived)
        assertEquals(0, s.fromKnownBanks)
        assertTrue(s.entries.isEmpty())
        assertEquals(9_000L, s.lastReceivedAt)
    }

    @Test
    fun `known-bank tally counts only entries whose sender resolved`() {
        val log = log()
        log.record(1L, "AD-HDFCBK", "HDFC", "body", SmsDebugLog.Outcome.PROMPTED)
        log.record(2L, "+919876543210", null, "body", SmsDebugLog.Outcome.SENDER_NOT_RECOGNISED)
        log.record(3L, "JD-SBIINB", "SBI", "body", SmsDebugLog.Outcome.NOT_A_TRANSACTION)

        assertEquals(2, log.state.value.fromKnownBanks)
    }

    // ---- privacy: enforced INSIDE the log, so no caller can bypass it ----

    @Test
    fun `a body is stored only when the sender resolved to a bank`() {
        val log = log()
        log.record(1L, "AD-HDFCBK", "HDFC", "Rs 500 debited at SHOP", SmsDebugLog.Outcome.PROMPTED)
        log.record(2L, "+919876543210", null, "see you at 8, bring the thing", SmsDebugLog.Outcome.SENDER_NOT_RECOGNISED)

        val personal = log.state.value.entries.first { it.institution == null }
        val bank = log.state.value.entries.first { it.institution != null }

        assertNull("a personal message body must never be recorded", personal.body)
        assertNotNull("a recognised bank alert is what makes a parse failure diagnosable", bank.body)
    }

    /**
     * The rule that makes the screen safe to paste into a chat window. A caller passing a body for an
     * unrecognised sender is not a bug to be found in review — it is impossible for it to leak.
     */
    @Test
    fun `passing a body for an unrecognised sender cannot leak it`() {
        val log = log()
        log.record(1L, "SOMEONE", null, "private", SmsDebugLog.Outcome.NOT_A_TRANSACTION)

        assertNull(log.state.value.entries.single().body)
    }

    @Test
    fun `an alphanumeric sender is kept in full - it is the whole point when a bank header changes`() {
        assertEquals("AD-HDFCBK", SmsDebugLog.maskSender("AD-HDFCBK"))
        assertEquals("JD-SBIINB", SmsDebugLog.maskSender("JD-SBIINB"))
        assertEquals("VM-ICICIB", SmsDebugLog.maskSender("VM-ICICIB"))
    }

    @Test
    fun `a sender with no letters is a person and is masked`() {
        assertEquals(SmsDebugLog.MASKED_SENDER, SmsDebugLog.maskSender("+919876543210"))
        assertEquals(SmsDebugLog.MASKED_SENDER, SmsDebugLog.maskSender("9876543210"))
        assertEquals(SmsDebugLog.MASKED_SENDER, SmsDebugLog.maskSender("+91 98765 43210"))
    }

    @Test
    fun `a missing sender is named rather than left blank`() {
        assertEquals("(no sender)", SmsDebugLog.maskSender(null))
        assertEquals("(no sender)", SmsDebugLog.maskSender("   "))
    }

    @Test
    fun `the masking rule is applied when recording, not only when asked directly`() {
        val log = log()
        log.record(1L, "+919876543210", null, null, SmsDebugLog.Outcome.SENDER_NOT_RECOGNISED)

        assertEquals(SmsDebugLog.MASKED_SENDER, log.state.value.entries.single().sender)
    }

    // ---- the ring ----

    @Test
    fun `entries are newest first`() {
        val log = log()
        log.record(1L, "A-BANK", "HDFC", null, SmsDebugLog.Outcome.PROMPTED)
        log.record(2L, "B-BANK", "SBI", null, SmsDebugLog.Outcome.PROMPTED)

        assertEquals(listOf(2L, 1L), log.state.value.entries.map { it.timeMillis })
    }

    @Test
    fun `the ring is capped so a busy phone cannot grow it without bound`() {
        val log = log()
        repeat(200) { log.record(it.toLong(), "A-BANK", "HDFC", null, SmsDebugLog.Outcome.PROMPTED) }

        val entries = log.state.value.entries
        assertEquals(60, entries.size)
        // Newest survive, oldest are evicted.
        assertEquals(199L, entries.first().timeMillis)
        assertEquals(140L, entries.last().timeMillis)
    }

    @Test
    fun `long text is clipped so one pathological message cannot bloat the log`() {
        val log = log()
        log.record(1L, "A-BANK", "HDFC", "x".repeat(5_000), SmsDebugLog.Outcome.PROMPTED)

        val body = log.state.value.entries.single().body!!
        assertTrue("clipped to the cap plus an ellipsis", body.length <= 501)
        assertTrue(body.endsWith("…"))
    }
}
