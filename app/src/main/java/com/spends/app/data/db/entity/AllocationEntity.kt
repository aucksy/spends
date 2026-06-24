package com.spends.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One slice of an expense assigned to a category. Every expense has 1..n allocations (a
 * single-category expense is one row) so analytics is uniform (PRD §3/§4.6). Deleting an expense
 * cascades its allocations.
 */
@Entity(
    tableName = "allocations",
    foreignKeys = [
        ForeignKey(
            entity = ExpenseEntity::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("expenseId"), Index("categoryId")],
)
data class AllocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long,
    val categoryId: Long,
    val amountMinor: Long,
)
