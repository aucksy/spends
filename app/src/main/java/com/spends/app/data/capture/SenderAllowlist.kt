package com.spends.app.data.capture

/** What kind of instrument a financial SMS sender represents (drives kind classification). */
enum class InstitutionType { BANK, CREDIT_CARD, WALLET, LOAN, PAYMENT_APP }

data class Institution(val name: String, val type: InstitutionType)

/**
 * Maps an Indian bank SMS sender id to a known institution (PRD §4.3, parser-fixtures-and-allowlist).
 * Senders look like `VM-SBICRD`, `JK-IDFCFB`, `AD-AXISBK-S` — a rotating 2-letter telco/DLT prefix,
 * the bank "header", and an optional `-S/-T/-P` DLT suffix. We match on the **header only**.
 * Anything not in the map is treated as non-financial and ignored.
 */
object SenderAllowlist {

    private val byHeader: Map<String, Institution> = buildMap {
        // Bank / UPI accounts
        put("IDFCFB", Institution("IDFC First Bank", InstitutionType.BANK))
        put("AXISBK", Institution("Axis Bank", InstitutionType.BANK))
        put("SBIUPI", Institution("SBI", InstitutionType.BANK))
        // Credit cards
        put("SBICRD", Institution("SBI Card", InstitutionType.CREDIT_CARD))
        put("INDUSB", Institution("IndusInd Bank", InstitutionType.CREDIT_CARD))
        put("ICICIT", Institution("ICICI Bank", InstitutionType.CREDIT_CARD))
        put("ICICIB", Institution("ICICI Bank", InstitutionType.CREDIT_CARD))
        put("ONECRD", Institution("OneCard", InstitutionType.CREDIT_CARD))
        put("BOBONE", Institution("OneCard", InstitutionType.CREDIT_CARD))
        put("YESBNK", Institution("Yes Bank", InstitutionType.CREDIT_CARD))
        put("YESUNI", Institution("Yes Bank", InstitutionType.CREDIT_CARD))
        put("AMEXIN", Institution("American Express", InstitutionType.CREDIT_CARD))
        put("MYAMEX", Institution("American Express", InstitutionType.CREDIT_CARD))
        put("RBLCRD", Institution("RBL Bank", InstitutionType.CREDIT_CARD))
        put("RBLBNK", Institution("RBL Bank", InstitutionType.CREDIT_CARD))
        put("HDFCBK", Institution("HDFC Bank", InstitutionType.CREDIT_CARD))
        put("HDFCBN", Institution("HDFC Bank", InstitutionType.CREDIT_CARD))
        put("PNBCCD", Institution("PNB", InstitutionType.CREDIT_CARD))
        put("PNBRTS", Institution("PNB", InstitutionType.CREDIT_CARD))
        put("PNBSMS", Institution("PNB", InstitutionType.CREDIT_CARD))
        // Loan
        put("LNTFIN", Institution("L&T Finance", InstitutionType.LOAN))
        // Wallets / payment apps
        put("IPAYTM", Institution("Paytm", InstitutionType.WALLET))
        put("PAYTMB", Institution("Paytm", InstitutionType.WALLET))
        put("PYTMBK", Institution("Paytm", InstitutionType.WALLET))
        put("MOBIKW", Institution("MobiKwik", InstitutionType.WALLET))
        put("CREDIN", Institution("CRED", InstitutionType.PAYMENT_APP))
    }

    /**
     * RCS business chats and Truecaller verified senders show a FRIENDLY name ("Axis Bank",
     * "SBI Card") instead of the DLT header ("AX-AXISBK") — this maps those names (normalized:
     * uppercase, alphanumerics only) to the same institutions. Notification capture only; the SMS
     * path never consults it. Institution names/types mirror [byHeader] exactly so both sources of
     * the same alert classify identically (and dedupe hashes line up).
     */
    private val byDisplayName: Map<String, Institution> = buildMap {
        put("AXISBANK", Institution("Axis Bank", InstitutionType.BANK))
        put("IDFCFIRSTBANK", Institution("IDFC First Bank", InstitutionType.BANK))
        put("IDFCFIRST", Institution("IDFC First Bank", InstitutionType.BANK))
        put("SBI", Institution("SBI", InstitutionType.BANK))
        put("STATEBANKOFINDIA", Institution("SBI", InstitutionType.BANK))
        put("SBIBANK", Institution("SBI", InstitutionType.BANK))
        put("SBICARD", Institution("SBI Card", InstitutionType.CREDIT_CARD))
        put("SBICARDS", Institution("SBI Card", InstitutionType.CREDIT_CARD))
        put("INDUSINDBANK", Institution("IndusInd Bank", InstitutionType.CREDIT_CARD))
        put("INDUSIND", Institution("IndusInd Bank", InstitutionType.CREDIT_CARD))
        put("ICICIBANK", Institution("ICICI Bank", InstitutionType.CREDIT_CARD))
        put("ICICI", Institution("ICICI Bank", InstitutionType.CREDIT_CARD))
        put("ONECARD", Institution("OneCard", InstitutionType.CREDIT_CARD))
        put("YESBANK", Institution("Yes Bank", InstitutionType.CREDIT_CARD))
        put("AMERICANEXPRESS", Institution("American Express", InstitutionType.CREDIT_CARD))
        put("AMEX", Institution("American Express", InstitutionType.CREDIT_CARD))
        put("RBLBANK", Institution("RBL Bank", InstitutionType.CREDIT_CARD))
        put("HDFCBANK", Institution("HDFC Bank", InstitutionType.CREDIT_CARD))
        put("HDFC", Institution("HDFC Bank", InstitutionType.CREDIT_CARD))
        put("PNB", Institution("PNB", InstitutionType.CREDIT_CARD))
        put("PUNJABNATIONALBANK", Institution("PNB", InstitutionType.CREDIT_CARD))
        put("LTFINANCE", Institution("L&T Finance", InstitutionType.LOAN))
        put("LANDTFINANCE", Institution("L&T Finance", InstitutionType.LOAN))
        put("PAYTM", Institution("Paytm", InstitutionType.WALLET))
        put("PAYTMBANK", Institution("Paytm", InstitutionType.WALLET))
        put("PAYTMPAYMENTSBANK", Institution("Paytm", InstitutionType.WALLET))
        put("MOBIKWIK", Institution("MobiKwik", InstitutionType.WALLET))
        put("CRED", Institution("CRED", InstitutionType.PAYMENT_APP))
    }

    /** Extract the bank "header" from a sender id, or null for numeric / unrecognisable senders. */
    fun headerOf(sender: String?): String? {
        if (sender.isNullOrBlank()) return null
        var h = sender.trim().uppercase()
        if (h.all { it.isDigit() || it == '+' }) return null // plain phone number
        h = h.replace(Regex("[-_][A-Z]$"), "")                // drop trailing -S/-T/-P/-G DLT suffix
        Regex("^[A-Z]{2}[-_](.+)$").find(h)?.let { h = it.groupValues[1] } // drop telco prefix
        h = h.replace(Regex("[^A-Z0-9]"), "")
        return h.takeIf { it.length >= 3 && !it.all { c -> c.isDigit() } }
    }

    fun lookup(sender: String?): Institution? = headerOf(sender)?.let { byHeader[it] }

    /**
     * Notification-capture lookup: try the SMS header form first (many RCS bank chats still show
     * "AX-AXISBK"), then fall back to the friendly business name. Null → not a tracked financial
     * sender, exactly like [lookup].
     */
    fun lookupSenderOrName(senderOrTitle: String?): Institution? {
        lookup(senderOrTitle)?.let { return it }
        val normalized = senderOrTitle?.trim()?.uppercase()?.replace(Regex("[^A-Z0-9]"), "")
            ?.takeIf { it.isNotBlank() } ?: return null
        byDisplayName[normalized]?.let { return it }
        // Real RBM/Truecaller agent names vary ("Axis Bank Ltd", "HDFC Bank Cards") — retry with up
        // to two common trailing tokens stripped, exact-matching the table after each strip so a
        // random business can never fuzzy-match into a bank.
        var n = normalized
        repeat(2) {
            val suffix = NAME_SUFFIXES.firstOrNull { n.length > it.length && n.endsWith(it) } ?: return null
            n = n.removeSuffix(suffix)
            byDisplayName[n]?.let { return it }
        }
        return null
    }

    /** Trailing tokens RBM agent names append to the bare brand (longest first so LIMITED wins over LTD). */
    private val NAME_SUFFIXES = listOf("LIMITED", "OFFICIAL", "INDIA", "CARDS", "CARD", "BANK", "LTD")

    /**
     * Translate a notification sender/title into a sender string [SmsParser] accepts, or null when
     * it isn't a tracked financial sender. Header-form senders pass through unchanged (so an RCS
     * twin of an SMS produces the IDENTICAL parse and dedupe hash); friendly names map to that
     * institution's canonical header. Everything downstream — parse, prompt intents, the editor's
     * re-parse — then behaves exactly as if the alert were an SMS from that institution.
     */
    fun canonicalSenderFor(senderOrTitle: String?): String? {
        if (lookup(senderOrTitle) != null) return senderOrTitle
        val inst = lookupSenderOrName(senderOrTitle) ?: return null
        // First header registered for this institution = its canonical header.
        return byHeader.entries.firstOrNull { it.value.name == inst.name && it.value.type == inst.type }?.key
    }
}
