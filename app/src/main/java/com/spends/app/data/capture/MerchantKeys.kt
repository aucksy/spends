package com.spends.app.data.capture

/**
 * Normalises raw bank-SMS merchant strings into stable keys and decides when two keys refer to the
 * same shop — so "RAZ*Amul", "Amul India Pvt Ltd" and "AMUL RETAIL" all hit the same learned
 * merchant→category/note memory. The old exact-string key almost never repeated (gateway prefixes,
 * order numbers, company suffixes vary per alert), which is why learning rarely fired.
 *
 * Pure JVM (no Android deps) so the rules are pinned by unit tests like the SMS parser fixtures.
 */
object MerchantKeys {

    /** Payment-gateway / rails tokens banks prepend before the real merchant name ("RAZ*", "PAYU"). */
    private val GATEWAY_TOKENS = setOf(
        "raz", "razp", "razorpay", "payu", "payubiz", "billdesk", "bdsk", "ccavenue", "ccav",
        "eazypay", "easebuzz", "cashfree", "instamojo", "mswipe", "pinelabs", "paytm", "gpay",
        "googlepay", "phonepe", "bharatpe", "upi", "pos", "ecom", "vps", "neft", "imps", "ach",
    )

    /** Trailing company-form / geography tokens that vary between alerts for the same merchant. */
    private val SUFFIX_TOKENS = setOf(
        "pvt", "ltd", "limited", "private", "llp", "inc", "co", "company", "india", "ind", "in",
    )

    /** Gateways that appear GLUED to the name ("RAZFurlenco") — stripped when enough name remains. */
    private val GLUED_PREFIXES = listOf("razorpay", "razp", "raz", "payu")

    /**
     * Generic transaction words that identify NO merchant. A key that is just one of these (e.g.
     * "PAYTM ORDER" → "order") must not become a learning key, and such a word alone must never
     * fuzzy-match a real key ("order" ⊄ "swiggy order") — either would smear one merchant's
     * category/note onto unrelated payments.
     */
    private val STOP_TOKENS = setOf(
        "order", "orders", "payment", "payments", "pay", "purchase", "recharge", "store", "stores",
        "online", "service", "services", "retail", "shop", "shopping", "txn", "transaction",
        "bill", "bills", "card", "bank",
    )

    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /**
     * Canonical lowercase key for a raw merchant string, or null when nothing identifying remains.
     * Steps: lowercase → keep the handle before '@' for UPI VPAs → strip punctuation → drop leading
     * gateway tokens + trailing company/number tokens (never the last remaining token) → un-glue a
     * gateway prefix fused onto the first word.
     */
    fun normalize(raw: String?): String? {
        var s = raw?.lowercase()?.trim() ?: return null
        if (s.isBlank()) return null
        // A UPI handle: the part before '@' identifies the payee; the bank suffix varies by app.
        if ('@' in s) s = s.substringBefore('@')
        s = s.replace(NON_ALNUM, " ").trim()
        var tokens = s.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        while (tokens.size > 1 && tokens.first() in GATEWAY_TOKENS) tokens = tokens.drop(1)
        while (tokens.size > 1 && (tokens.last() in SUFFIX_TOKENS || tokens.last().all(Char::isDigit))) {
            tokens = tokens.dropLast(1)
        }
        val first = tokens.first()
        // Never un-glue a token that IS a gateway name itself ("razorpay" must not become "orpay").
        if (first !in GATEWAY_TOKENS) {
            for (prefix in GLUED_PREFIXES) {
                // Only when ≥4 chars of real name remain, so short brands ("razor…") are never mangled.
                if (first.startsWith(prefix) && first.length >= prefix.length + 4) {
                    tokens = listOf(first.removePrefix(prefix)) + tokens.drop(1)
                    break
                }
            }
        }
        val key = tokens.joinToString(" ").ifBlank { return null }
        // A key with no letters (an order/reference number) or made ENTIRELY of generic transaction
        // words ("order", "card payment") identifies no merchant — refuse it rather than learn garbage.
        if (key.none { it.isLetter() }) return null
        if (tokens.all { it in STOP_TOKENS }) return null
        return key
    }

    /**
     * Whether two NORMALIZED keys refer to the same merchant. Deliberately conservative — a wrong
     * match would pre-fill a wrong category/note, so only two shapes beyond exact equality count:
     *  - word containment: every word of the shorter key appears in the longer ("amul" ⊂ "amul retail");
     *  - glued prefix: single-word keys where the longer starts with the shorter ("swiggy" → "swiggyinstamart").
     */
    fun sameMerchant(a: String, b: String): Boolean {
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        // A key made entirely of generic words ("order", "card payment") must never claim a longer
        // key it appears in.
        if (short.split(' ').all { it in STOP_TOKENS }) return false
        if (short.length >= 4 && long.split(' ').containsAll(short.split(' '))) return true
        return ' ' !in short && ' ' !in long && short.length >= 5 && long.startsWith(short)
    }
}
