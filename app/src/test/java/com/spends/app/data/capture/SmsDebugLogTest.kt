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
 *
 * `AD-HDFCBK` and `JD-SBIUPI` are real [SenderAllowlist] headers; the log resolves the sender itself,
 * so a fake one would not be treated as a bank. That is the point of the resolution living inside.
 */
class SmsDebugLogTest {

    private fun log() = SmsDebugLog()

    private companion object {
        const val BANK = "AD-HDFCBK"
        const val PERSON = "+919876543210"
        const val ALERT = "Rs 500 debited from a/c XX1234 at SHOP on 26-07-26"
    }

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
     * must not wipe that too — it would read as "none ever has", which is the opposite of the truth.
     * `SmsVerdictTest.zero count with a kept timestamp…` pins the other half: the verdict must READ this
     * kept timestamp, or the screen contradicts itself the moment Clear is tapped.
     */
    @Test
    fun `clear resets the counters but keeps when an SMS last arrived`() {
        val log = log()
        log.recordReceived(now = 9_000L)
        log.record(1L, BANK, ALERT, SmsDebugLog.Outcome.PROMPTED)

        log.clear()

        val s = log.state.value
        assertEquals(0, s.totalReceived)
        assertEquals(0, s.fromKnownBanks)
        assertTrue(s.entries.isEmpty())
        assertEquals(9_000L, s.lastReceivedAt)
    }

    @Test
    fun `known-bank tally counts only senders the allowlist actually resolves`() {
        val log = log()
        log.record(1L, BANK, ALERT, SmsDebugLog.Outcome.PROMPTED)
        log.record(2L, PERSON, "see you at 8", SmsDebugLog.Outcome.SENDER_NOT_RECOGNISED)
        log.record(3L, "JD-SBIUPI", ALERT, SmsDebugLog.Outcome.NOT_A_TRANSACTION)

        assertEquals(2, log.state.value.fromKnownBanks)
    }

    // ---- privacy: enforced INSIDE the log, so no caller can bypass it ----

    @Test
    fun `a body is stored only when the sender resolves to a bank`() {
        val log = log()
        log.record(1L, BANK, ALERT, SmsDebugLog.Outcome.PROMPTED)
        log.record(2L, PERSON, "see you at 8, bring the thing", SmsDebugLog.Outcome.SENDER_NOT_RECOGNISED)

        val personal = log.state.value.entries.first { it.institution == null }
        val bank = log.state.value.entries.first { it.institution != null }

        assertNull("a personal message body must never be recorded", personal.body)
        assertNotNull("a recognised bank alert is what makes a parse failure diagnosable", bank.body)
    }

    /**
     * The rule that makes the screen safe to paste into a chat window. The log resolves the sender
     * itself, so a caller CANNOT assert that a personal message came from a bank and have the body
     * stored on that word — there is no `institution` parameter to lie in.
     */
    @Test
    fun `an unrecognised sender cannot have its body stored however it is labelled`() {
        val log = log()
        log.record(1L, "AD-NOTABANK", "private", SmsDebugLog.Outcome.NOT_A_TRANSACTION)

        val e = log.state.value.entries.single()
        assertNull(e.institution)
        assertNull(e.body)
    }

    /**
     * An owner who never switched capture on is never having their bank alerts transcribed. The
     * notification log takes the same stance by gating its tally; here the tally stays ungated (a count
     * of zero is the whole point of the screen) and the CONTENT is gated instead.
     */
    @Test
    fun `a bank body is not stored when capture is off`() {
        val log = log()
        log.record(1L, BANK, ALERT, SmsDebugLog.Outcome.CAPTURE_OFF)

        val e = log.state.value.entries.single()
        assertEquals("HDFC Bank", e.institution)
        assertNull("capture was never enabled — nothing to transcribe", e.body)
    }

    /**
     * Bank OTPs are the largest class of NOT_A_TRANSACTION, and this report is built to be pasted into
     * a chat window. The words survive (they explain the rejection); the code does not.
     */
    @Test
    fun `a non-transaction from a bank has its digits masked`() {
        val log = log()
        log.record(1L, BANK, "Your OTP is 481920. Do not share it with anyone.", SmsDebugLog.Outcome.NOT_A_TRANSACTION)

        val body = log.state.value.entries.single().body!!
        assertTrue("the passcode must not survive", !body.contains("481920"))
        assertTrue("the words that explain the rejection must survive", body.contains("OTP"))
    }

    @Test
    fun `a real transaction from a bank keeps its numbers - they are the diagnosis`() {
        val log = log()
        log.record(1L, BANK, ALERT, SmsDebugLog.Outcome.PROMPTED)

        assertTrue(log.state.value.entries.single().body!!.contains("500"))
    }

    /** `detail` carries the parsed merchant and amount, so it is gated exactly as the body is. */
    @Test
    fun `detail is withheld for an unrecognised sender`() {
        val log = log()
        log.record(1L, PERSON, null, SmsDebugLog.Outcome.SENDER_NOT_RECOGNISED, detail = "expense 50000 paise · SHOP")

        assertNull(log.state.value.entries.single().detail)
    }

    @Test
    fun `detail is kept for a recognised bank`() {
        val log = log()
        log.record(1L, BANK, ALERT, SmsDebugLog.Outcome.PROMPTED, detail = "expense 50000 paise · SHOP")

        assertNotNull(log.state.value.entries.single().detail)
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

    /** `Char.isLetter()` is false for every Unicode digit category, so no script bypasses the mask. */
    @Test
    fun `non-latin digits do not bypass the sender mask`() {
        assertEquals(SmsDebugLog.MASKED_SENDER, SmsDebugLog.maskSender("٩٨٧٦٥٤٣٢١٠"))
        assertEquals(SmsDebugLog.MASKED_SENDER, SmsDebugLog.maskSender("९८७६५४३२१०"))
        assertEquals(SmsDebugLog.MASKED_SENDER, SmsDebugLog.maskSender("９８７６５４３２１０"))
    }

    @Test
    fun `a missing sender is named rather than left blank`() {
        assertEquals("(no sender)", SmsDebugLog.maskSender(null))
        assertEquals("(no sender)", SmsDebugLog.maskSender("   "))
    }

    @Test
    fun `the masking rule is applied when recording, not only when asked directly`() {
        val log = log()
        log.record(1L, PERSON, null, SmsDebugLog.Outcome.SENDER_NOT_RECOGNISED)

        assertEquals(SmsDebugLog.MASKED_SENDER, log.state.value.entries.single().sender)
    }

    // ---- the ring ----

    @Test
    fun `entries are newest first`() {
        val log = log()
        log.record(1L, BANK, null, SmsDebugLog.Outcome.PROMPTED)
        log.record(2L, "JD-SBIUPI", null, SmsDebugLog.Outcome.PROMPTED)

        assertEquals(listOf(2L, 1L), log.state.value.entries.map { it.timeMillis })
    }

    @Test
    fun `the ring is capped so a busy phone cannot grow it without bound`() {
        val log = log()
        repeat(200) { log.record(it.toLong(), BANK, null, SmsDebugLog.Outcome.PROMPTED) }

        val entries = log.state.value.entries
        assertEquals(60, entries.size)
        assertEquals(199L, entries.first().timeMillis)
        assertEquals(140L, entries.last().timeMillis)
    }

    @Test
    fun `long text is clipped so one pathological message cannot bloat the log`() {
        val log = log()
        log.record(1L, BANK, "Rs 5 debited " + "x".repeat(5_000), SmsDebugLog.Outcome.PROMPTED)

        val body = log.state.value.entries.single().body!!
        assertTrue("clipped to the cap plus an ellipsis", body.length <= 501)
        assertTrue(body.endsWith("…"))
    }
}
