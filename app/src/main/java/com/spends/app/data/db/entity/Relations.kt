package com.spends.app.data.db.entity

import androidx.room.Embedded
import androidx.room.Relation
import com.spends.app.domain.model.TxnKind

/** An allocation joined to its category (for display: name, icon, color). */
data class AllocationWithCategory(
    @Embedded val allocation: AllocationEntity,
    @Relation(parentColumn = "categoryId", entityColumn = "id")
    val category: CategoryEntity,
)

/** An expense joined to all of its allocations (each with its category). */
data class ExpenseWithAllocations(
    @Embedded val expense: ExpenseEntity,
    @Relation(
        entity = AllocationEntity::class,
        parentColumn = "id",
        entityColumn = "expenseId",
    )
    val allocations: List<AllocationWithCategory>,
)

/** Aggregation row: total amount for a given transaction kind in a period. */
data class KindSum(
    val kind: TxnKind,
    val total: Long,
)

/**
 * One charge's slice of a category — the per-transaction input to the AI insight engine (outlier and
 * duplicate detection). [merchantRaw] is used only to tell a genuine double-billing from two unrelated
 * charges of the same size, on the device; it is never carried into a finding and never leaves the phone.
 */
data class CategorySliceRow(
    /** The parent transaction. A split produces several rows sharing this id — the duplicate-charge
     *  detector groups on it so one split expense is never mistaken for two identical charges. */
    val expenseId: Long,
    val name: String,
    val amountMinor: Long,
    val occurredAt: Long,
    val merchantRaw: String?,
)

/** Aggregation row: total spend per category (excludes transfers / excludeFromSpend in the query). */
data class CategorySpend(
    val categoryId: Long,
    val name: String,
    val colorHex: String,
    val iconKey: String,
    val total: Long,
)
