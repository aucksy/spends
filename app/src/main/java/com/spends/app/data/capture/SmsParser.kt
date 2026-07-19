package com.spends.app.data.capture

import com.spends.app.domain.model.Direction
import com.spends.app.domain.model.TxnKind
import java.time.LocalDate
import java.time.Month
import kotlin.math.roundToLong

/**
 * Rules-based parser for Indian bank/card/wallet SMS (PRD §4.3 + parser-fixtures-and-allowlist.md).
 * Pure and side-effect free so it's exhaustively unit-tested against the golden fixtures. It never
 * throws on malformed input — worst case it returns [Result.IGNORED].
 */
object SmsParser {

    enum class Result { TRANSACTION, STATEMENT, IGNORED }

    data class Parsed(
        val result: Result,
        val amountMinor: Long? = null,
        val kind: TxnKind? = null,
        val direction: Direction? = null,
        val merchant: String? = null,
        val last4: String? = null,
        val institution: String? = null,
        val occurredAt: Long? = null,
        val confidence: Int = 0,
        val categoryHint: String? = null, // "Loan/EMI" / "Investments" or null
        val refNumber: String? = null,    // RRN / UPI ref / txn id — disambiguates same-day repeats
    )

    private val ignored = Parsed(Result.IGNORED)

    fun parse(sender: String?, body: String?, receivedAt: Long): Parsed {
        if (body.isNullOrBlank()) return ignored
        val inst = SenderAllowlist.lookup(sender) ?: return ignored // only tracked financial senders
        val text = body.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        val low = text.lowercase()

        // 1) Hard rejects — not a money movement.
        if (isOtp(low) || isPromo(low) || isEmiConversion(low, text) || isDeclined(low) || isLimitAlert(low) || isFutureMandate(low)) {
            return ignored
        }
        // 2) Statements / bill-due alerts — extract nothing to log (cycle config is a later phase).
        if (isStatement(low)) return Parsed(Result.STATEMENT, institution = inst.name)

        val amount = extractAmount(text) ?: return ignored
        val last4 = extractLast4(text)
        val isCard = low.contains("credit card") || low.contains("card") && !low.contains("a/c") ||
            inst.type == InstitutionType.CREDIT_CARD

        // 3) Classify kind.
        val (kind, direction, categoryHint) = classify(low, inst, isCard) ?: return ignored
        val merchant = extractMerchant(text)
        val occurredAt = resolveOccurredAt(text, receivedAt)
        val confidence = confidenceFor(amount, kind, last4, merchant, inst)

        return Parsed(
            result = Result.TRANSACTION,
            amountMinor = amount,
            kind = kind,
            direction = direction,
            merchant = merchant,
            last4 = last4,
            institution = inst.name,
            occurredAt = occurredAt,
            confidence = confidence,
            categoryHint = categoryHint,
            refNumber = extractRef(text),
        )
    }

    // ---- classification ----

    private fun classify(low: String, inst: Institution, isCard: Boolean): Triple<TxnKind, Direction, String?>? {
        val hint = categoryHint(low)
        val debitWord = low.containsAny("spent", "debited", "debit ", "withdrawn", "purchase", "paid ", "deducted", "spend")
        val creditWord = low.containsAny("credited", "deposited", "received", "credit ")

        // Refund / reversal → income.
        if (low.containsAny("refund", "reversed", "reversal", "refunded")) {
            return Triple(TxnKind.INCOME, Direction.CREDIT, null)
        }
        // Credit-card bill payment → NOT logged. It's money moving between your own accounts (bank → card),
        // never real income or spending, so it must not land in the ledger. "payment received on/towards
        // your card", but NOT a purchase ("...at <merchant>...") and NOT a loan payment.
        val billPayment = (low.contains("card")) &&
            (low.contains("received") && low.contains("payment") ||
                low.contains("thank you for your payment") || low.contains("payment received")) &&
            !low.contains(" at ") && !low.contains("spent")
        if (billPayment) return null

        // A card "payment of Rs X at <merchant>" is a purchase (has " at "), not a bill payment.
        if (isCard && low.contains("payment") && low.contains(" at ")) {
            return Triple(TxnKind.EXPENSE, Direction.DEBIT, hint)
        }

        // Loan/EMI or investment debit → expense in an excludeFromSpend category.
        if (hint != null && (debitWord || low.contains("nach") || low.contains("emi") || low.contains("payment"))) {
            return Triple(TxnKind.EXPENSE, Direction.DEBIT, hint)
        }
        // Plain debit / spend → expense.
        if (debitWord) return Triple(TxnKind.EXPENSE, Direction.DEBIT, hint)
        // Credit on a bank/UPI account → income.
        if (creditWord) {
            return if (isCard && !low.contains("a/c") && !low.contains("account")) {
                // an unexplained credit on a card that isn't a refund — a card top-up / self-move, never
                // income and no longer a "transfer" kind, so don't log it.
                null
            } else {
                Triple(TxnKind.INCOME, Direction.CREDIT, null)
            }
        }
        return null // no clear direction → don't log (avoid false positives)
    }

    private fun categoryHint(low: String): String? = when {
        low.containsAny("loan account", "nach debit", "loan emi", "emi debit", " emi ", "loan") -> "Loan/EMI"
        low.containsAny("sip", "mutual fund", "policy", "premium", "investment") -> "Investments"
        else -> null
    }

    // ---- ignore / statement detection ----

    private fun isOtp(low: String) =
        low.containsAny("otp", "one-time password", "one time password", "do not share", "verification code") &&
            !low.contains("spent") && !low.contains("debited")

    private fun isPromo(low: String) = low.containsAny(
        // NB: "convert into emi" is intentionally NOT here — EMI conversion isn't a generic promo and isPromo
        // has no fresh-spend guard, so it would silently drop a genuine purchase whose footer offers EMI.
        // isEmiConversion handles EMI wording (guarded by hasFreshPointOfSaleSpend). "convert it into emi"
        // ("…you can convert IT into EMI") is a distinct pre-approved-offer phrase and stays.
        "pre-approved", "preapproved", "pre approved", "card upgrade", "convert it into emi",
        "loan up to", "credit line", "explore the", "missed call", "lifetimefree",
        "can be approved", "assured cashback of up to", "download ",
    )

    private fun isDeclined(low: String) =
        low.containsAny("declined your txn", "we declined", "transaction failed", "txn failed", "has failed", "was declined")

    private fun isLimitAlert(low: String) =
        low.contains("credit limit") && low.containsAny("consumed", "% of", "balance limit") && !low.contains("spent")

    /** Future-dated mandate/auto-pay notices ("will be debited/processed") are reminders, not txns. */
    private fun isFutureMandate(low: String) =
        low.containsAny("will be debited", "will be processed", "will be deducted", "maintain sufficient balance", "ensure sufficient balance")

    /**
     * EMI-CONVERSION notices / offers ("…has been converted into EMIs", "Convert your txn to EMI", "Split
     * INR X … into Easy EMIs", "avail no-cost EMI"). These echo a purchase that was ALREADY captured, so
     * logging them double-counts — yet they carry a spend verb.
     *
     * The trap: a GENUINE card purchase can carry a promotional "convert to EMI / split into easy EMIs"
     * FOOTER, and a blunt token check dropped those real spends silently (#5 — the missed SBI purchases).
     * So we bias to NEVER miss a real spend (the user's explicit choice): a message with a fresh
     * point-of-sale line ("Rs.X spent/debited … at <merchant>") is KEPT; we reject only when the conversion
     * is the message's MAIN subject. Always gated on the WORD "emi" (so "pr[emi]um"/"acad[emi]c" never trip).
     */
    private fun isEmiConversion(low: String, text: String): Boolean {
        if (!emiWordRegex.containsMatchIn(low)) return false

        // 1) An explicit COMPLETED/requested conversion of an existing amount — phrasing that never appears
        //    on a fresh point-of-sale alert, so reject even when it echoes "…spent … at <merchant>". Note
        //    "convert into emi" is NOT here: the SBI/HDFC purchase footer literally says "Convert into EMI",
        //    so it must stay in the fresh-spend-guarded step 3 (#5).
        if (low.containsAny(
                "has been converted", "converted into", "emi conversion",
                "conversion request", "conversion successful",
            )
        ) {
            return true
        }

        // 2) An OFFER whose MAIN action is converting: the message LEADS with Convert / Split / Avail EMI,
        //    before any rupee amount ("Split your INR X spend… into EMIs", "Convert your txn to EMI"). A
        //    genuine purchase leads with the amount, mentioning EMI only in a trailing footer.
        val firstAmount = amountRegex.find(low)?.range?.first ?: Int.MAX_VALUE
        val firstConvertCue = listOf("convert", "split", "avail emi")
            .mapNotNull { cue -> low.indexOf(cue).takeIf { it >= 0 } }
            .minOrNull()
        if (firstConvertCue != null && firstConvertCue < firstAmount) return true

        // 3) Remaining EMI-offer phrasings — rejected ONLY when there is no fresh point-of-sale spend, so a
        //    genuine purchase that merely carries a "Convert to/into EMI / split your transaction into easy
        //    EMIs" footer is still captured (#5 — the missed SBI/HDFC/ICICI purchases). The "convert to emi"
        //    and "split your transaction" phrasings were added this round (#9) for bank EMI offers about an
        //    already-made purchase; the fresh-spend guard keeps real spends safe.
        return low.containsAny(
            "convert into emi", "convert to emi", "into emis", "into easy emis", "no cost emi", "no-cost emi",
            "emi offer", "emi facility", "emi plan", "emi option",
            "split your transaction", "split your txn", "split this transaction",
        ) && !hasFreshPointOfSaleSpend(low, text)
    }

    /**
     * A genuine point-of-sale alert: a spend verb plus a named merchant. The merchant may be introduced by
     * "at" (most banks) OR "on" (ICICI: "…spent using ICICI Bank Card XX on <date> on <MERCHANT>"), so the
     * regex runs on the ORIGINAL-case text and requires an uppercase/masked merchant initial — which also
     * skips the date ("on 21-Jun-26" starts with a digit).
     */
    private fun hasFreshPointOfSaleSpend(low: String, text: String): Boolean =
        low.containsAny("spent", "debited", "purchase of", "spend at") && posMerchantRegex.containsMatchIn(text)

    private val emiWordRegex = Regex("\\bemis?\\b")
    private val posMerchantRegex = Regex("\\b(?:at|on)\\s+(?:[A-Z]|<)")

    private fun isStatement(low: String) = low.containsAny(
        "stmt alert", "statement for", "statement has been", "e-statement", "estatement",
        "total amount due", "minimum amount due", "min amount due", "min due", "total due",
        "bill has been generated", "bill generated", "bill is ready", "bill of rs", "amt due",
        "is due on", "is due", "due on",
    ) && !low.containsAny("spent", "debited by", "is debited", "has been debited", "received as payment", "received on your")

    // ---- field extraction ----

    private val amountRegex = Regex("(?:rs\\.?|inr)\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)

    private fun extractAmount(text: String): Long? {
        val m = amountRegex.find(text) ?: return null
        val raw = m.groupValues[1].replace(",", "")
        val value = raw.toBigDecimalOrNull() ?: return null
        return value.movePointRight(2).toDouble().roundToLong()
    }

    private val last4Patterns = listOf(
        Regex("ending\\s+(?:in\\s+)?(?:xx)?(\\d{4,5})", RegexOption.IGNORE_CASE),
        Regex("card\\s*(?:no\\.?\\s*)?(?:xx+|x|\\*+|\\*\\*\\s?)(\\d{4,5})", RegexOption.IGNORE_CASE),
        Regex("a/c\\s*(?:no\\.?\\s*)?x+(\\d{4,5})", RegexOption.IGNORE_CASE),
        Regex("card\\s*\\((\\d{4,5})\\)", RegexOption.IGNORE_CASE),
        Regex("\\*{2,}\\s?(\\d{4,5})"),
        Regex("xx+(\\d{4,5})", RegexOption.IGNORE_CASE),
        Regex("card\\s+(\\d{4,5})\\b", RegexOption.IGNORE_CASE),
    )

    private fun extractLast4(text: String): String? {
        for (p in last4Patterns) p.find(text)?.let { return it.groupValues[1] }
        return null
    }

    private val refPatterns = listOf(
        Regex("\\bUPI[ /:]?Ref(?:erence)?(?:\\s*(?:no\\.?|number))?[ :/]*([A-Za-z0-9]{4,})", RegexOption.IGNORE_CASE),
        Regex("\\bRef(?:erence)?\\s*(?:no\\.?|number)?[ :/]*([A-Za-z0-9]{4,})", RegexOption.IGNORE_CASE),
        Regex("\\bRRN[ :/]*([A-Za-z0-9]{4,})", RegexOption.IGNORE_CASE),
        Regex("\\bTxn\\s*(?:id|no)\\.?[ :/]*([A-Za-z0-9]{4,})", RegexOption.IGNORE_CASE),
        Regex("UPI/[A-Z0-9]+/([0-9]{6,})/", RegexOption.IGNORE_CASE),
    )

    /** A transaction reference (RRN / UPI ref / txn id), stable across the multiple SMS for one
     *  payment but distinct between different payments — used to dedupe without collapsing genuine
     *  same-day repeats. */
    private fun extractRef(text: String): String? {
        for (p in refPatterns) p.find(text)?.let { return it.groupValues[1].uppercase() }
        return null
    }

    private fun extractMerchant(text: String): String? {
        // Prefer "at <X>" / "@<X>" / " to <X>"; stop at common trailing tokens.
        val patterns = listOf(
            Regex("\\bat\\s+(.+?)(?:\\s+on\\b|\\.|\\bAvl\\b|\\bUPI\\b|\\bRef\\b|$)", RegexOption.IGNORE_CASE),
            Regex("@\\s*([^\\s].*?)(?:\\s+on\\b|\\d{2}[-/]|\\.|\\bAvl\\b|$)", RegexOption.IGNORE_CASE),
            Regex("\\bto\\s+(.+?)(?:\\s+from\\b|\\.|\\bUPI\\b|$)", RegexOption.IGNORE_CASE),
            Regex("UPI/[A-Z0-9]+/[0-9]+/([^/\\s]+)", RegexOption.IGNORE_CASE),
            Regex("\\bon\\s+([A-Z][A-Za-z0-9 .*&'-]{2,}?)(?:\\.|\\bAvl\\b|$)"), // ICICI "...on <MERCHANT>."
        )
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val raw = m.groupValues[1].trim().trim('.', ',', '-', ' ')
            if (raw.isNotBlank() && raw.length in 2..40 && !raw.matches(Regex("\\d{2}[-/].*"))) return raw
        }
        return null
    }

    // ---- date parsing (handles every format in the fixtures) ----

    /**
     * The transaction time. Bank/card SMS almost never carry a time-of-day — only a date — so stamping a
     * fixed noon collapsed a whole day's spends onto 12:00 and scrambled their order in the timeline (#6).
     * The alert lands within seconds of the swipe, so [receivedAt] (the SMS's own received time — the
     * historical time for a past-SMS scan) IS the real moment. Use it whenever the body's date is the same
     * calendar day, which is the overwhelmingly common case. Only when the body explicitly names an EARLIER
     * day (a delayed/forwarded alert) do we fall back to that day, carrying [receivedAt]'s clock time so it
     * still orders sensibly rather than snapping back to noon.
     */
    private fun resolveOccurredAt(text: String, receivedAt: Long): Long {
        val zone = com.spends.app.core.time.DateUtils.ZONE
        val received = java.time.Instant.ofEpochMilli(receivedAt).atZone(zone)
        val parsed = extractDateOnly(text) ?: return receivedAt
        // Same day (or a parsed date at/after the SMS — almost always a mis-parsed due/expiry date): trust
        // the SMS clock. Only a genuinely earlier body-date carries forward, at the SMS's time-of-day.
        if (!parsed.isBefore(received.toLocalDate())) return receivedAt
        return parsed.atTime(received.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
    }

    private fun extractDateOnly(text: String): LocalDate? {
        for (rx in dateRegexes) {
            val m = rx.regex.find(text) ?: continue
            runCatching {
                return rx.build(m) ?: return@runCatching
            }
        }
        return null
    }

    private class DateRule(val regex: Regex, val build: (MatchResult) -> LocalDate?)

    private fun yr(y: String): Int { val n = y.toInt(); return if (n < 100) 2000 + n else n }
    private fun mon(s: String): Int? = runCatching {
        Month.valueOf(s.uppercase().take(3).let { mmm -> monthMap[mmm] ?: return null }).value
    }.getOrNull()
    private val monthMap = mapOf(
        "JAN" to "JANUARY", "FEB" to "FEBRUARY", "MAR" to "MARCH", "APR" to "APRIL", "MAY" to "MAY",
        "JUN" to "JUNE", "JUL" to "JULY", "AUG" to "AUGUST", "SEP" to "SEPTEMBER", "OCT" to "OCTOBER",
        "NOV" to "NOVEMBER", "DEC" to "DECEMBER",
    )

    private val dateRegexes = listOf(
        // 2026-06-21 (HDFC)
        DateRule(Regex("(20\\d{2})-(\\d{2})-(\\d{2})")) { m -> LocalDate.of(m.g(1).toInt(), m.g(2).toInt(), m.g(3).toInt()) },
        // 21/06/2026 or 21/06/26 or 21-06-2026 or 21-06-26
        DateRule(Regex("\\b(\\d{1,2})[-/](\\d{1,2})[-/](\\d{2,4})\\b")) { m -> LocalDate.of(yr(m.g(3)), m.g(2).toInt(), m.g(1).toInt()) },
        // 21-Jun-26 / 21-JUN-2026
        DateRule(Regex("\\b(\\d{1,2})[-\\s]([A-Za-z]{3,9})[-\\s](\\d{2,4})\\b")) { m -> mon(m.g(2))?.let { LocalDate.of(yr(m.g(3)), it, m.g(1).toInt()) } },
        // 21 June 26 / 21 December, 25 (Amex)
        DateRule(Regex("\\b(\\d{1,2})\\s+([A-Za-z]{3,9}),?\\s+(\\d{2,4})\\b")) { m -> mon(m.g(2))?.let { LocalDate.of(yr(m.g(3)), it, m.g(1).toInt()) } },
        // 12th Sep 26 (Paytm)
        DateRule(Regex("\\b(\\d{1,2})(?:st|nd|rd|th)\\s+([A-Za-z]{3,9})\\s+(\\d{2,4})\\b")) { m -> mon(m.g(2))?.let { LocalDate.of(yr(m.g(3)), it, m.g(1).toInt()) } },
        // 21Jun26 (SBI UPI, no separators)
        DateRule(Regex("\\b(\\d{1,2})([A-Za-z]{3})(\\d{2})\\b")) { m -> mon(m.g(2))?.let { LocalDate.of(yr(m.g(3)), it, m.g(1).toInt()) } },
    )

    private fun confidenceFor(amount: Long, kind: TxnKind, last4: String?, merchant: String?, inst: Institution): Int {
        var c = 55
        if (amount > 0) c += 20
        c += 10 // matched a clear direction keyword to get here
        if (last4 != null) c += 8
        if (merchant != null) c += 7
        return c.coerceIn(0, 100)
    }

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { contains(it) }
    private fun MatchResult.g(i: Int): String = groupValues[i]
}
