package com.spends.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A spending category. Icon + color are app-assigned (no pickers). [excludeFromSpend] keeps
 * non-consumption categories (Investments, Loan/EMI, …) out of spend analytics while their
 * transactions still affect cash Balance (PRD §3/§4.4).
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val isCustom: Boolean = true,
    val isArchived: Boolean = false,
    val excludeFromSpend: Boolean = false,
    val sortOrder: Int = 0,
)
