package com.spends.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.spends.app.domain.model.TxnKind

/**
 * A bank-SMS transaction parsed from a **historical inbox scan** — or, since Phase 4, from a watched
 * app's **notification** — held for the user to review before it ever enters the ledger (PRD §4.1,
 * review-only capture). Nothing here affects balance/analytics until the user confirms it (→ a real
 * [ExpenseEntity]) or rejects it (→ deleted). The original SMS/notification [rawBody]/[sender] are
 * stored (local-only, never networked) so the review card can show the source text (#10) and search
 * can match any value (#12); rows scanned before DB v8 have null.
 *
 * [sourceApp] = the watched app's package name for a notification capture (DB v16), null for SMS
 * rows — drives the review card's "From notification" origin badge.
 *
 * `dedupeHash` is uniquely indexed so a re-scan can't queue the same payment twice.
 */
@Entity(
    tableName = "pending_captures",
    indices = [Index(value = ["dedupeHash"], unique = true)],
)
data class PendingCaptureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMinor: Long,
    val kind: TxnKind,
    val occurredAt: Long,
    val merchant: String?,
    val last4: String?,
    val institution: String?,
    val categoryId: Long,
    val parseConfidence: Int,
    val dedupeHash: String,
    val receivedAt: Long,
    val createdAt: Long,
    val rawBody: String? = null,
    val sender: String? = null,
    val sourceApp: String? = null,
)
