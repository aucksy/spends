package com.spends.app.data.capture

import com.spends.app.domain.model.Direction
import com.spends.app.domain.model.TxnKind
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.util.Locale
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
        if (isOtp(low) || isPromo(low) || isEmiConversion(low) || isDeclined(low) || isLimitAlert(low) || isFutureMandate(low)) {
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
        val occurredAt = extractDate(text) ?: receivedAt
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
        // Credit-card bill payment → transfer (NOT income). "payment received on/towards your card",
        // but NOT a purchase ("...at <merchant>...") and NOT a loan payment.
        val billPayment = (low.contains("card")) &&
            (low.contains("received") && low.contains("payment") ||
                low.contains("thank you for your payment") || low.contains("payment received")) &&
            !low.contains(" at ") && !low.contains("spent")
        if (billPayment) return Triple(TxnKind.TRANSFER, Direction.CREDIT, null)

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
                // an unexplained credit on a card that isn't a refund — treat as transfer, never income
                Triple(TxnKind.TRANSFER, Direction.CREDIT, null)
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
        "pre-approved", "preapproved", "pre approved", "card upgrade", "convert it into emi",
        "convert into emi", "loan up to", "credit line", "explore the", "missed call", "lifetimefree",
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
     * EMI-CONVERSION offers / notices / confirmations ("…spent…converted into EMIs", "convert to EMI",
     * "avail EMI", "no-cost EMI", "EMI conversion successful"). These echo the ORIGINAL purchase amount
     * and would double-count it — they are NOT a fresh money movement, even though they usually contain
     * a spend/debit verb. Gated on the literal "emi" PLUS a conversion/offer token so a genuine EMI /
     * NACH installment debit (which never says convert / into-emi / avail) still logs as an expense.
     */
    private fun isEmiConversion(low: String): Boolean {
        // Match the WORD "emi"/"emis" only — a raw substring would also hit "pr[emi]um"/"acad[emi]c" and
        // (with the broad convert tokens) silently drop a genuine premium/installment debit. "emis?" keeps
        // the plural "EMIs" so real conversion notices ("converted into 6 EMIs") still reject.
        if (!emiWordRegex.containsMatchIn(low)) return false
        // "split"/"easy emi" catch IndusInd's "Split INR X … into Easy EMIs" offers (they carry a spend
        // verb but aren't a fresh debit). Genuine installment debits never say split / convert / easy emi.
        return low.containsAny(
            "convert", "conversion", "split", "into emi", "to emis", "easy emi", "avail emi",
            "emi option", "no cost emi", "no-cost emi", "emi offer", "emi facility", "emi plan",
        )
    }

    private val emiWordRegex = Regex("\\bemis?\\b")

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

    private fun extractDate(text: String): Long? {
        val zone = com.spends.app.core.time.DateUtils.ZONE
        for (rx in dateRegexes) {
            val m = rx.regex.find(text) ?: continue
            runCatching {
                val date = rx.build(m) ?: return@runCatching
                return date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
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
