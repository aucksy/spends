package com.spends.app.data.capture

import com.spends.app.domain.model.TxnKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A parsed-but-UNSAVED capture, produced when the user taps a live capture notification's "Edit" (or
 * the notification body). It seeds the normal Add/Edit editor so NOTHING enters the ledger until the
 * user explicitly Saves (#4). [dedupeHash] is carried so the committed transaction records the same
 * dedupe key the parser computed — a later historical scan then won't re-queue the same SMS.
 */
data class CaptureDraft(
    val amountMinor: Long,
    val kind: TxnKind,
    val categoryId: Long,
    val merchant: String?,
    val occurredAt: Long,
    val dedupeHash: String,
    // Instrument auto-matched from the SMS (last4, then bank name) so the editor pre-fills "Paid with" (#3).
    val paymentMethodId: Long? = null,
    // The note the user last gave this merchant (learned memory), pre-filled for review.
    val note: String? = null,
)

/**
 * Hands a [CaptureDraft] from [MainViewModel] (notification-Edit tap) to the editor's ViewModel without
 * persisting it. In-memory and one-shot: survives config changes (it's a @Singleton) but is lost on full
 * process death — acceptable, since nothing was written and the SMS is still in the inbox to re-scan.
 */
@Singleton
class CaptureDraftStore @Inject constructor() {
    @Volatile private var current: CaptureDraft? = null

    fun set(draft: CaptureDraft) { current = draft }

    /** Take the pending draft (clearing it) so it can only be consumed once. */
    fun consume(): CaptureDraft? {
        val d = current
        current = null
        return d
    }
}
