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

    @Test
    fun `a non-transaction from a bank has its digits masked`() {
        val log = log()
        log.record(1L, BANK, "Your OTP is 481920. Do not share it with anyone.", SmsDebugLog.Outcome.NOT_A_TRANSACTION)

        val body = log.state.value.entries.single().body!!
        assertTrue("the passcode must not survive", !body.contains("481920"))
        assertTrue("the words that explain the rejection must survive", body.contains("OTP"))
    }

    /**
     * ⭐ THE regression. An earlier version masked ONLY `NOT_A_TRANSACTION`, reasoning that a
     * non-transaction from a bank is overwhelmingly an OTP. The gate deciding that is `SmsParser.isOtp`,
     * which excludes any text containing "spent" or "debited" — so the three commonest Indian passcode
     * formats parse as genuine TRANSACTIONS, reached `PROMPTED`, and had the passcode stored and
     * exported verbatim while the screen promised passcodes never leave the phone.
     *
     * An earlier version of THIS FILE actively pinned that defect in place, with a test asserting a
     * transaction "keeps its numbers - they are the diagnosis". It went red under the correct fix, so
     * anyone attempting the obvious repair would have been told the tests forbade it. Masking is now
     * unconditional, and this test asserts the opposite of the one it replaced.
     */
    @Test
    fun `an OTP that parses as a transaction is masked too`() {
        val log = log()
        log.record(
            1L,
            BANK,
            "Rs.5000.00 has been debited from your a/c XX1234. OTP 481920 to confirm.",
            SmsDebugLog.Outcome.PROMPTED,
        )

        val body = log.state.value.entries.single().body!!
        assertTrue("a passcode must not survive on ANY outcome", !body.contains("481920"))
        assertTrue("nor the balance or account tail", !body.contains("5000"))
        assertTrue("the wording, which is the diagnosis, survives", body.contains("debited"))
    }

    /**
     * Masking every body costs no diagnostic power, because the figures Spends actually parsed are
     * carried in `detail` — which is where they belong, already interpreted, instead of being fished
     * back out of raw text that also holds the balance and the account tail.
     */
    @Test
    fun `the parsed figures survive in detail, not in the body`() {
        val log = log()
        log.record(1L, BANK, ALERT, SmsDebugLog.Outcome.PROMPTED, detail = "expense 50000 paise · SHOP")

        val e = log.state.value.entries.single()
        assertTrue("the body carries no digits", e.body!!.none { it.isDigit() })
        assertTrue("the parsed amount is still reportable", e.detail!!.contains("50000"))
    }

    /** `detail` carries the parsed merchant and amount, so it is gated exactly as the body is. */
    @Test
    fun `detail is withheld for an unrecognised sender`() {
        val log = log()
        log.record(1L, PERSON, null, SmsDebugLog.Outcome.SENDER_NOT_RECOGNISED, detail = "expense 50000 paise · SHOP")

        assertNull(log.state.value.entries.single().detail)
    }

    /**
     * `detail` is gated by BOTH content rules, not just the sender one. Unreachable from today's
     * callers, which is exactly why it needs a test: the class's stance is that it does not trust its
     * callers, and without this the gate could be deleted with the whole suite still green.
     */
    @Test
    fun `detail is withheld on an outcome reached before capture was enabled`() {
        val log = log()
        log.record(1L, BANK, ALERT, SmsDebugLog.Outcome.CAPTURE_OFF, detail = "expense 50000 paise · SHOP")

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

    /**
     * `displayOriginatingAddress` can return an email-to-SMS gateway address. It has letters, so the
     * letters-present rule alone exported it verbatim — and an email identifies a person far more
     * strongly than the phone number the mask was built to hide.
     */
    @Test
    fun `an email-to-SMS sender is masked, not treated as a bank header`() {
        assertEquals(SmsDebugLog.MASKED_EMAIL_SENDER, SmsDebugLog.maskSender("john.smith@gmail.com"))
        assertEquals(SmsDebugLog.MASKED_EMAIL_SENDER, SmsDebugLog.maskSender("john.smith+bank@gmail.com"))
        assertEquals(SmsDebugLog.MASKED_EMAIL_SENDER, SmsDebugLog.maskSender("alerts@somebank.example"))
    }

    /**
     * ⭐ A bare `contains('@')` masked these. GSM 03.38 encodes "@" as septet 0x00, so trailing "@"
     * padding turns up on genuine alphanumeric sender IDs — and `SenderAllowlist` strips non-alphanumerics
     * before matching, so `AD-HDFCBK@` still resolved to HDFC Bank while the screen called it an email
     * address. For an UNRECOGNISED header it was worse: the screen hid the exact string the verdict was
     * telling the owner to report.
     */
    @Test
    fun `an A2P header carrying at-sign padding is not mistaken for an email`() {
        assertEquals("AD-HDFCBK@", SmsDebugLog.maskSender("AD-HDFCBK@"))
        assertEquals("VM-ICICIB@@", SmsDebugLog.maskSender("VM-ICICIB@@"))
    }

    /**
     * ⭐ The rule is an ALLOW-LIST — keep what is header-shaped — not an address detector. Both
     * detector versions failed, in opposite directions: `contains('@')` masked the padded headers above,
     * and the anchored single-`@` email pattern that replaced it was strictly WEAKER than what it
     * replaced, letting `john.smith@gmail.com@` (the same GSM padding, on an address) through verbatim.
     * An allow-list cannot fail that way: a shape it doesn't recognise defaults to withheld.
     */
    @Test
    fun `anything not header-shaped is withheld, however it is spelled`() {
        assertEquals(SmsDebugLog.MASKED_EMAIL_SENDER, SmsDebugLog.maskSender("john.smith@gmail.com@"))
        assertEquals(SmsDebugLog.MASKED_EMAIL_SENDER, SmsDebugLog.maskSender("@john.smith@gmail.com"))
        assertEquals(SmsDebugLog.MASKED_EMAIL_SENDER, SmsDebugLog.maskSender("\"John Smith\" <john@gmail.com>"))
        assertEquals(SmsDebugLog.MASKED_EMAIL_SENDER, SmsDebugLog.maskSender("HDFCBK@AIRTEL"))
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

    /**
     * The cap is this class's own, not a bound borrowed from elsewhere. An earlier version masked via
     * `SmsParser.aiContextFor`, whose 300-char cap silently took ownership of it — leaving this class's
     * `clip()` unreachable dead code that no mutation could kill.
     */
    @Test
    fun `long text is clipped so one pathological message cannot bloat the log`() {
        val log = log()
        log.record(1L, BANK, "Rs 5 debited " + "x".repeat(5_000), SmsDebugLog.Outcome.PROMPTED)

        val body = log.state.value.entries.single().body!!
        // assertEquals, not <=: the loose bound stayed green for a cap of 300 or even 60, so it pinned
        // the existence of a cap but not its value.
        assertEquals(501, body.length)
        assertTrue(body.endsWith("…"))
    }

    /**
     * Masking is done HERE, not by borrowing `SmsParser.aiContextFor`. That function strips the
     * "not you? / SMS BLOCK…" trailer BEFORE masking, so a body that opens with such a phrase was
     * deleted outright and stored as null — indistinguishable on screen from "the privacy gate withheld
     * it". A real transaction losing its whole body to a diagnostic tool is the opposite of the point.
     */
    @Test
    fun `a body opening with report boilerplate is still kept, masked`() {
        val log = log()
        log.record(1L, BANK, "Not you? Rs.500 was debited at SHOP just now", SmsDebugLog.Outcome.PROMPTED)

        val body = log.state.value.entries.single().body!!
        assertTrue("the wording must survive", body.contains("debited"))
        assertTrue("but not the amount", body.none { it.isDigit() })
    }

    /**
     * Indian bank alerts routinely carry a PER-CUSTOMER short link whose path identifies the recipient.
     * Digit-masking alone leaves the letters, so the token partly survived into the clipboard.
     */
    @Test
    fun `a per-customer link is removed, not merely digit-masked`() {
        val log = log()
        log.record(1L, BANK, "Rs.500 debited at SHOP. View: hdfcbk.io/x/aB9cD2e", SmsDebugLog.Outcome.PROMPTED)

        val body = log.state.value.entries.single().body!!
        assertTrue("no fragment of the token may survive", !body.contains("aB"))
        assertTrue(body.contains("(link)"))
    }

    /**
     * Deliberately a host with NO path: the bare-host alternative requires a "/", so only the
     * scheme/www alternative can catch this. The first version used an https URL that the bare-host
     * rule also matched, so deleting the scheme alternative left both link tests green.
     */
    @Test
    fun `a www link with no path is removed by the scheme rule`() {
        val log = log()
        log.record(1L, BANK, "Rs.5 debited. Visit www.hdfcbankKqLm for more", SmsDebugLog.Outcome.PROMPTED)

        val body = log.state.value.entries.single().body!!
        assertTrue(!body.contains("KqLm"))
        assertTrue(body.contains("(link)"))
    }

    /**
     * An Indian POS descriptor reads exactly like a bare host. Case-insensitivity applied to the whole
     * pattern made `[a-z]{2,}` match uppercase, so AMAZON.IN/PAY became "(link)" — destroying the
     * merchant token in the one outcome where it IS the diagnosis.
     */
    @Test
    fun `an uppercase merchant descriptor is not mistaken for a link`() {
        val log = log()
        log.record(1L, BANK, "Rs.500 spent at AMAZON.IN/PAY", SmsDebugLog.Outcome.NOT_A_TRANSACTION)

        assertTrue(log.state.value.entries.single().body!!.contains("AMAZON.IN/PAY"))
    }

    /** A UPI VPA and an email are the same identifier class, and neither is caught by the link rule. */
    @Test
    fun `an address in the body is removed`() {
        val log = log()
        log.record(1L, BANK, "Rs.500 debited and credited to aakashpahuja@okhdfcbank", SmsDebugLog.Outcome.PROMPTED)

        val body = log.state.value.entries.single().body!!
        assertTrue("the VPA must not survive", !body.contains("aakashpahuja"))
        assertTrue(body.contains("(address)"))
    }

    /** `detail` is not masked — it is Spends' own parse, not the bank's words — but it is still capped. */
    @Test
    fun `long detail is clipped`() {
        val log = log()
        log.record(1L, BANK, ALERT, SmsDebugLog.Outcome.PROMPTED, detail = "x".repeat(5_000))

        val detail = log.state.value.entries.single().detail!!
        assertTrue(detail.length <= 501)
        assertTrue(detail.endsWith("…"))
    }
}
