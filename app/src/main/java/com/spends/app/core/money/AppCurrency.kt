package com.spends.app.core.money

/**
 * How an integer digit part is grouped for display.
 *
 * [INDIAN] is the 2-2-3 lakh/crore convention (12,34,567) the app has always used for rupees;
 * [WESTERN] is the plain 3-digit thousands convention (1,234,567) every other supported currency uses.
 */
enum class Grouping { INDIAN, WESTERN }

/**
 * A currency Spends can keep its books in.
 *
 * Spends stores **one** ledger in **one** currency: every `amountMinor` in the database is minor units
 * (paise / sen / cents) of the currency the user picked here. This enum only decides how those minor
 * units are *rendered* and what text at the parse boundary counts as "this currency" — it never takes
 * part in money math, so switching it can't change a stored figure.
 *
 * A foreign-currency SMS is a separate concern: it is CONVERTED to the base currency at capture time
 * (see `data/ai/CurrencyAi`), and the original amount travels alongside as `fx*` columns for display.
 *
 * [aliases] are the prefixes [Money.parseToMinor] strips and [com.spends.app.data.capture.SmsParser]
 * matches, longest-first so "MYR" wins over "RM" and neither is mistaken for the other.
 */
enum class AppCurrency(
    val code: String,
    val symbol: String,
    val grouping: Grouping,
    val displayName: String,
    val aliases: List<String>,
) {
    INR("INR", "₹", Grouping.INDIAN, "Indian Rupee", listOf("INR", "RS.", "RS", "₹")),
    MYR("MYR", "RM", Grouping.WESTERN, "Malaysian Ringgit", listOf("MYR", "RM")),
    USD("USD", "$", Grouping.WESTERN, "US Dollar", listOf("USD", "US$", "$")),
    ;

    /** "Indian Rupee (₹)" — for pickers and settings rows. */
    val label: String get() = "$displayName ($symbol)"

    companion object {
        val DEFAULT = INR

        /** Resolve a stored/parsed code ("myr", "MYR") to a currency, or null when it isn't one we keep books in. */
        fun fromCode(code: String?): AppCurrency? {
            val c = code?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.code == c }
        }

        /** Like [fromCode] but falls back to [DEFAULT] — for reading a persisted value that may be stale. */
        fun fromCodeOrDefault(code: String?): AppCurrency = fromCode(code) ?: DEFAULT

        /**
         * Every currency token the SMS parser knows, longest-first. Includes currencies Spends does NOT
         * keep books in (SGD, GBP, …) so a foreign alert is *recognised* and can be converted, rather
         * than silently mis-read as a base-currency amount.
         */
        val ALL_TOKENS: List<Pair<String, String>> = buildList {
            // Base currencies first, then other common ones a traveller's card statement can carry.
            add("MYR" to "MYR"); add("RM" to "MYR")
            add("USD" to "USD"); add("US$" to "USD"); add("$" to "USD")
            add("INR" to "INR"); add("RS." to "INR"); add("RS" to "INR"); add("₹" to "INR")
            add("SGD" to "SGD"); add("S$" to "SGD")
            add("AED" to "AED"); add("GBP" to "GBP"); add("£" to "GBP")
            add("EUR" to "EUR"); add("€" to "EUR")
            add("THB" to "THB"); add("AUD" to "AUD"); add("JPY" to "JPY"); add("¥" to "JPY")
            add("IDR" to "IDR"); add("PHP" to "PHP"); add("VND" to "VND"); add("HKD" to "HKD")
        }.sortedByDescending { it.first.length }

        /**
         * The symbol to show for ANY currency code — a base currency's own symbol, else the bare code.
         * Used when displaying the original side of a converted transaction ("SGD 42.00 → ₹2,730.00").
         */
        fun symbolFor(code: String?): String {
            val c = code?.trim()?.uppercase().orEmpty()
            fromCode(c)?.let { return it.symbol }
            return when (c) {
                "SGD" -> "S$"
                "GBP" -> "£"
                "EUR" -> "€"
                "JPY" -> "¥"
                else -> c
            }
        }
    }
}
