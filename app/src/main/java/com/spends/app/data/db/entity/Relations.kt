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

/** Aggregation row: total spend per category (excludes transfers / excludeFromSpend in the query). */
data class CategorySpend(
    val categoryId: Long,
    val name: String,
    val colorHex: String,
    val iconKey: String,
    val total: Long,
)
