package com.spends.app.data.capture

/**
 * Curated candidate apps for notification capture (Phase 4). The listener only ever reads
 * notifications from packages the user has ticked; this list is what the Settings checklist offers.
 *
 * Launch scope (owner-chosen): Google Messages + Truecaller — both carry RCS/business-chat bank
 * alerts that SMS capture can't see, and those messages parse with the existing [SmsParser] rules
 * because the sender/text look exactly like a bank SMS. Payment apps (GPay/PhonePe/Paytm) use their
 * own notification wording that the parser has no rules for yet — they join the checklist in a
 * later round WITH their parsing rules, so a ticked app never silently captures nothing.
 */
object NotificationCaptureApps {

    data class CandidateApp(val packageName: String, val displayName: String)

    const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"
    const val TRUECALLER = "com.truecaller"

    // Future round (needs per-app parsing rules before being offered):
    const val GPAY = "com.google.android.apps.nbu.paisa.user"
    const val PHONEPE = "com.phonepe.app"
    const val PAYTM = "net.one97.paytm"

    /** Everything the Settings checklist offers, in display order. */
    val CANDIDATES: List<CandidateApp> = listOf(
        CandidateApp(GOOGLE_MESSAGES, "Google Messages"),
        CandidateApp(TRUECALLER, "Truecaller"),
    )

    /** Pre-ticked when capture is first enabled. */
    val DEFAULT_PACKAGES: Set<String> = setOf(GOOGLE_MESSAGES, TRUECALLER)

    /** Short human name for a watched package (review-card origin badge). Null when unknown. */
    fun displayName(packageName: String?): String? =
        CANDIDATES.firstOrNull { it.packageName == packageName }?.displayName
            ?: when (packageName) {
                GPAY -> "Google Pay"
                PHONEPE -> "PhonePe"
                PAYTM -> "Paytm"
                else -> null
            }
}
