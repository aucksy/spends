package com.spends.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.spends.app.domain.model.Direction
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource

/**
 * The core transaction record. Despite the legacy "Expense" name it stores **all** transaction
 * kinds; [kind] distinguishes income / expense / transfer and drives the money math (PRD §3).
 *
 * Soft delete: [deletedAt] is null for active rows and a timestamp once moved to Trash (§4.19).
 * [paymentMethodId] is a nullable id with no FK yet — the PaymentMethod table arrives with capture.
 */
@Entity(
    tableName = "expenses",
    indices = [
        Index("occurredAt"),
        Index("deletedAt"),
        Index("dedupeHash"),
        Index("paymentMethodId"),
    ],
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMinor: Long,
    val occurredAt: Long,
    val merchantRaw: String? = null,
    val note: String? = null,
    val paymentMethodId: Long? = null,
    val source: TxnSource = TxnSource.MANUAL,
    val kind: TxnKind = TxnKind.EXPENSE,
    val direction: Direction = Direction.DEBIT,
    @ColumnInfo(defaultValue = "100") val parseConfidence: Int = 100,
    val dedupeHash: String? = null,
    val rawCaptureId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    // Set when this row was auto-created by a recurring rule — links it back to the rule so an edit can
    // optionally update all past occurrences (#5) and the occurrence cap (#8) can count them. Null otherwise.
    val recurringRuleId: Long? = null,
    // ---- Foreign-currency origin (DB v17) ----
    // Set ONLY when this transaction arrived in a currency other than the one the ledger is kept in, and was
    // converted on the way in. [amountMinor] above is ALWAYS base-currency minor units — these three columns
    // are a receipt for how that figure was reached, never an input to any sum:
    //   fxCurrency     the original ISO code ("MYR")
    //   fxAmountMinor  the original amount in ITS OWN minor units (sen)
    //   fxRateMicros   base units per one foreign unit × 1_000_000 (see core/money/FxMath)
    // All three are null together for an ordinary same-currency transaction, so every existing row and every
    // manual entry is unaffected. Nullable Long?/String? → INTEGER/TEXT with no NOT NULL and no default,
    // exactly matching Room's generated DDL in MIGRATION_16_17.
    val fxCurrency: String? = null,
    val fxAmountMinor: Long? = null,
    val fxRateMicros: Long? = null,
)
