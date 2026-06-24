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
}
