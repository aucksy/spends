package com.spends.app.core.category

/**
 * Assigns a stable icon **key** to a category from its name's keywords (PRD §4.4) — no icon picker.
 * The key is mapped to a concrete Compose ImageVector in [CategoryIcons]; this layer is pure and
 * unit-tested so the keyword rules can evolve safely.
 *
 * Order matters: more specific keywords are matched first ("Dog Food" -> pet, not food).
 */
object IconAssigner {

    const val FALLBACK = "tag"

    // Ordered: first matching rule wins.
    private val rules: List<Pair<List<String>, String>> = listOf(
        // Income-leaning keywords first so they win over generic spend words.
        listOf("salary", "payroll", "wages", "stipend") to "salary",
        listOf("cashback", "reward", "bonus") to "cashback",
        listOf("refund", "reversal", "reimburse") to "refund",
        listOf("interest", "dividend", "savings") to "interest",
        listOf("business", "freelance", "consulting", "client", "invoice") to "business",
        listOf("dog", "cat", "pet", "puppy", "kitten", "paw", "vet") to "pet",
        listOf("guitar", "music", "instrument", "spotify", "song") to "music",
        listOf("grocery", "groceries", "supermarket", "kirana", "bigbasket", "blinkit", "zepto") to "grocery",
        listOf("fuel", "petrol", "diesel", "gas station", "pump") to "fuel",
        listOf("rent", "landlord") to "rent",
        listOf("fitness", "gym", "workout", "yoga") to "fitness",
        listOf("health", "medical", "pharmacy", "doctor", "hospital", "clinic", "medicine", "chemist", "apollo") to "health",
        listOf("invest", "mutual fund", "sip", "stock", "equity", "zerodha", "groww") to "investments",
        listOf("loan", "emi", "debt", "mortgage") to "loan_emi",
        listOf("subscription", "subscriptions", "membership", "prime", "netflix", "hotstar") to "subscriptions",
        listOf("util", "electricity", "water", "broadband", "wifi", "internet", "dth") to "utilities",
        listOf("bill", "recharge", "postpaid", "prepaid") to "bills",
        listOf("transport", "taxi", "uber", "ola", "cab", "metro", "bus", "train", "auto", "ride", "rapido") to "transport",
        listOf("travel", "trip", "flight", "hotel", "vacation", "airbnb", "makemytrip", "irctc") to "travel",
        listOf("education", "course", "tuition", "school", "college", "book", "learn", "udemy") to "education",
        listOf("personal care", "salon", "spa", "grooming", "haircut", "barber") to "personal_care",
        listOf("gift", "present", "donation") to "gifts",
        listOf("coffee", "cafe", "tea", "starbucks", "barista") to "coffee",
        listOf("entertain", "movie", "cinema", "game", "gaming", "concert", "bookmyshow") to "entertainment",
        listOf("shop", "shopping", "mall", "amazon", "myntra", "flipkart", "clothes", "fashion") to "shopping",
        listOf("food", "restaurant", "dining", "swiggy", "zomato", "eat", "lunch", "dinner", "snack", "pizza") to "food",
    )

    fun keyFor(name: String): String {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return FALLBACK
        for ((keywords, key) in rules) {
            if (keywords.any { n.contains(it) }) return key
        }
        return FALLBACK
    }
}
