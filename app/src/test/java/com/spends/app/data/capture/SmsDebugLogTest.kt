package com.spends.app.data.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.spends.app.domain.model.TxnKind
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
        // A BODY_BEARING outcome on purpose: paired with SENDER_NOT_RECOGNISED this test stayed GREEN
        // when rule 1 was deleted, because the outcome gate already excluded it. It now pins its name.
        log.record(2L, PERSON, "see you at 8, bring the thing", SmsDebugLog.Outcome.PROMPTED)

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
        log.record(1L, BANK, ALERT, SmsDebugLog.Outcome.PROMPTED, amountMinor = 50_000L, kind = TxnKind.EXPENSE, note = "SHOP")

        val e = log.state.value.entries.single()
        assertTrue("the body carries no digits", e.body!!.none { it.isDigit() })
        assertTrue("the parsed amount is still reportable", e.detail!!.contains("50000"))
    }

    /** `detail` carries the parsed merchant and amount, so it is gated exactly as the body is. */
    @Test
    fun `detail is withheld for an unrecognised sender`() {
        val log = log()
        // A BODY_BEARING outcome on purpose. Paired with SENDER_NOT_RECOGNISED this test stayed GREEN
        // when the institution gate was deleted, because the outcome gate already excluded it — the same
        // blind spot this file already found and fixed for the BODY test, never applied to this one.
        log.record(1L, PERSON, null, SmsDebugLog.Outcome.PROMPTED, amountMinor = 50_000L, kind = TxnKind.EXPENSE, note = "SHOP")

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
        log.record(1L, BANK, ALERT, SmsDebugLog.Outcome.CAPTURE_OFF, amountMinor = 50_000L, kind = TxnKind.EXPENSE, note = "SHOP")

        assertNull(log.state.value.entries.single().detail)
    }

    @Test
    fun `detail is kept for a recognised bank`() {
        val log = log()
        log.record(1L, BANK, ALERT, SmsDebugLog.Outcome.PROMPTED, amountMinor = 50_000L, kind = TxnKind.EXPENSE, note = "SHOP")

        assertNotNull(log.state.value.entries.single().detail)
    }

    @Test
    fun `an alphanumeric sender is kept in full - it is the whole point when a bank header changes`() {
        assertEquals("AD-HDFCBK", SmsDebugLog.maskSender("AD-HDFCBK"))
        assertEquals("JD-SBIINB", SmsDebugLog.maskSender("JD-SBIINB"))
        assertEquals("VM-ICICIB", SmsDebugLog.maskSender("VM-ICICIB"))
    }

    /**
     * A positive test for the whitespace arm. Without one, deleting that clause left the whole suite
     * GREEN — the only test touching whitespace was the phone-number case, which the no-letters arm
     * answers. A privacy clause no test can kill is a privacy clause waiting to be removed.
     */
    @Test
    fun `a sender with letters and whitespace is withheld, not exported`() {
        assertEquals(SmsDebugLog.MASKED_EMAIL_SENDER, SmsDebugLog.maskSender("AD HDFCBK"))
        assertEquals(SmsDebugLog.MASKED_EMAIL_SENDER, SmsDebugLog.maskSender("John Smith"))
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
        // Blank only AFTER the padding is trimmed — a separate arm, and the two cases above hit the
        // first null/blank check instead, leaving it deletable with the suite green.
        assertEquals("(no sender)", SmsDebugLog.maskSender("@@@"))
    }

    /** Without this, a digits-only body was stored as "#" instead of withheld, and the letter check
     *  could be deleted with the whole suite green. */
    @Test
    fun `a body with nothing but digits is not stored`() {
        val log = log()
        log.record(1L, BANK, "9876543210", SmsDebugLog.Outcome.PROMPTED)

        assertNull(log.state.value.entries.single().body)
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

        // Uppercase too — all-caps A2P traffic is the Indian norm, and without this the (?i:) scoping
        // could be dropped with the whole suite green.
        val upper = log()
        upper.record(1L, BANK, "Rs.5 debited. Visit WWW.HDFCBANKKQLM for more", SmsDebugLog.Outcome.PROMPTED)
        val upperBody = upper.state.value.entries.single().body!!
        assertTrue(!upperBody.contains("KQLM"))
        assertTrue(upperBody.contains("(link)"))
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

        // The HOST survives (it is the merchant); only the PATH is replaced.
        assertTrue(log.state.value.entries.single().body!!.contains("AMAZON.IN"))
    }

    /**
     * ⭐ The body rule stripped the VPA and `detail` reprinted it one line above, undoing the rule
     * entirely — `detail` carries the PARSED merchant, which is a verbatim substring of the bank's text.
     * The reasoning that hid it was "detail is Spends' own parse, not the bank's words"; the parse is
     * made OF the bank's words.
     */
    @Test
    fun `an address cannot re-enter through detail`() {
        val log = log()
        log.record(
            1L, BANK, "INR 250 spent at coffeeday@ybl Ref 998877",
            SmsDebugLog.Outcome.PROMPTED,
            amountMinor = 25_000L, kind = TxnKind.EXPENSE, note = "coffeeday@ybl",
        )

        val e = log.state.value.entries.single()
        assertTrue("stripped from the body", !e.body!!.contains("coffeeday"))
        assertTrue("and from detail, which reprinted it", !e.detail!!.contains("coffeeday"))
    }

    /**
     * ⭐ Uppercase hosts are the norm in Indian promotional SMS, and scoping case-insensitivity to the
     * scheme (to stop the rule eating AMAZON.IN/PAY) left them leaking the per-customer path token —
     * this file's own worked example of what must not survive. Host and path are now handled separately.
     */
    @Test
    fun `an uppercase link still loses its per-customer path`() {
        val log = log()
        log.record(1L, BANK, "Rs.500 debited. View HDFCBK.IO/x/aB9cD2e now", SmsDebugLog.Outcome.PROMPTED)

        val body = log.state.value.entries.single().body!!
        assertTrue("the token must not survive in any case", !body.contains("aB"))
        assertTrue("the host is not the identifier, so it stays", body.contains("HDFCBK.IO"))

        // A per-customer token can hang off "?" or "#" as easily as "/". Reverting the separator class
        // to "/" alone left the whole suite green.
        for (sep in listOf("?ref=", "#tok=")) {
            val l = log()
            l.record(1L, BANK, "Rs.5 debited. See HDFCBK.IO${sep}aB9cD2e", SmsDebugLog.Outcome.PROMPTED)
            val b = l.state.value.entries.single().body!!
            assertTrue("separator '$sep' must be treated as a path", !b.contains("aB"))
        }

        // …but a sentence-final "?" is punctuation, not a query string.
        val q = log()
        q.record(1L, BANK, "Was this you at BOOKMYSHOW.IN? Reply NO", SmsDebugLog.Outcome.NOT_A_TRANSACTION)
        assertTrue(q.state.value.entries.single().body!!.contains("Reply NO"))
    }

    /**
     * Hole A. The scan-bound test above proves the BOUND; this proves the CUT-BACK, which is a separate
     * mechanism and was left unpinned when the earlier version's email was replaced by a plain token.
     * `ADDRESS` needs a letter AFTER the "@", so a cut landing just past the "@" yields a fragment the
     * masker can no longer match — the exact hazard the code comment describes.
     */
    @Test
    fun `the scan cut never splits an address and leaves a fragment`() {
        val log = log()
        // 179 is exact, not approximate, and must not be "tidied". It places the "@" at index 2003 —
        // just PAST the 2 000-char window — so the window ends mid-local-part with no "@" in it at all,
        // and `ADDRESS` (which needs a letter after the "@") cannot match the fragment. At 178 the whole
        // address falls inside the window and the mask catches it anyway; at 180 the local part starts
        // past the bound and never appears. Only this value makes the cut-back the thing under test:
        // deleting it leaves "…aakashpa" in the report.
        val filler = "1234567890 ".repeat(179)
        log.record(1L, BANK, "Rs.5 debited. Sent to ${filler}aakashpahuja@gmail.com tail", SmsDebugLog.Outcome.PROMPTED)

        val body = log.state.value.entries.single().body!!
        assertTrue("no fragment of the local part may survive", !body.contains("aakash"))
        assertTrue("nor a dangling at-sign", !body.contains("@"))
    }

    /**
     * Hole C. Dropping the `amountMinor != null && kind != null` guard rendered "null null paise" into
     * `detail` — reachable on EVERY bank non-transaction, which passes a note and no figures. No test
     * asserted detail's value for that call, so the guard could be deleted with the suite green.
     */
    @Test
    fun `a note with no figures is reported alone, not beside a null amount`() {
        val log = log()
        log.record(1L, BANK, ALERT, SmsDebugLog.Outcome.NOT_A_TRANSACTION, note = "read as ignored")

        assertEquals("read as ignored", log.state.value.entries.single().detail)
    }

    /**
     * The commonest Indian VPA form puts a phone number in the local part, and the address rule must
     * still catch it.
     *
     * It does NOT pin rule ORDER, and an earlier version of this comment claimed it did. Running NUMERAL
     * first is an EQUIVALENT mutation: it only ever replaces digit runs with "#", which is still
     * `[^\s@]`, so it can neither remove the letter the ADDRESS domain requires nor cross an "@" —
     * `#@ybl` matches exactly as `9876543210@ybl` does. Verified over 200 000 inputs with zero
     * divergence. A comment claiming a guarantee no assertion can hold is worse than no comment.
     */
    @Test
    fun `a VPA with a numeric local part is removed, not digit-masked`() {
        val log = log()
        log.record(1L, BANK, "Rs.5 debited, UPI to 9876543210@ybl", SmsDebugLog.Outcome.PROMPTED)

        val body = log.state.value.entries.single().body!!
        assertTrue(body.contains("(address)"))
        assertTrue("must not survive as a masked fragment", !body.contains("@ybl"))
    }

    /**
     * The scan bound is this class's own and must be pinned: deleting `take(MAX_SCAN_CHARS)` left the
     * suite green, because the 501 assertion is governed by the output clip, not by the input cut.
     * Cutting on a whitespace boundary matters too — a cut landing inside an address would leave the
     * local part exposed, since the masker can no longer match it.
     */
    @Test
    fun `input beyond the scan bound is dropped, and the cut never splits an address`() {
        val log = log()
        // The past-bound token must be one NO other rule catches. The first version used an email
        // address, which ADDRESS masks anyway — so the assertion held with or without the bound and the
        // test proved the address rule, not the thing it is named for. The filler is amounts, so masking
        // SHRINKS the prefix well under the 500-char clip; otherwise the clip hides the tail regardless.
        val filler = "1,23,456.78 ".repeat(200) // ~2400 chars, each collapsing to a single "#"
        log.record(1L, BANK, "Rs.5 debited $filler PASTBOUND", SmsDebugLog.Outcome.PROMPTED)

        val body = log.state.value.entries.single().body!!
        assertTrue("content past the bound is never scanned, so it cannot appear", !body.contains("PASTBOUND"))
        assertTrue("and the clip is not what hid it", body.length < 500)
        assertTrue(body.startsWith("Rs.#"))
    }

    @Test
    fun `an address in the body is removed`() {
        val log = log()
        log.record(1L, BANK, "Rs.500 debited and credited to aakashpahuja@okhdfcbank", SmsDebugLog.Outcome.PROMPTED)

        val body = log.state.value.entries.single().body!!
        assertTrue("the VPA must not survive", !body.contains("aakashpahuja"))
        assertTrue(body.contains("(address)"))
    }

    /**
     * `detail` keeps its NUMBERS — the parsed amount is the whole reason masking the body costs nothing —
     * but it is address-masked and capped.
     *
     * This comment used to read "`detail` is not masked: it is Spends' own parse, not the bank's words".
     * That sentence was the entire defect: the parse is made OF the bank's words, so `detail` reprinted
     * the UPI VPA that the body rule had just stripped. Left in place it would have talked the next
     * reader back into it, seventy lines from the test that now disproves it.
     */
    @Test
    fun `long detail is clipped`() {
        val log = log()
        log.record(1L, BANK, ALERT, SmsDebugLog.Outcome.PROMPTED, amountMinor = 5L, kind = TxnKind.EXPENSE, note = "x".repeat(5_000))

        val detail = log.state.value.entries.single().detail!!
        assertTrue(detail.length <= 501)
        assertTrue(detail.endsWith("…"))
    }
}
