package com.spends.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.spends.app.domain.model.CategoryUsage

/**
 * A spending category. Icon + color are app-assigned (no pickers). [excludeFromSpend] keeps
 * non-consumption categories (Investments, Loan/EMI, …) out of spend analytics while their
 * transactions still affect cash Balance (PRD §3/§4.4). [usage] decides which picker (expense /
 * income) the category appears in.
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
    // Room emits the defaultValue verbatim after DEFAULT, so a string literal must carry its own
    // single quotes ('EXPENSE'); this matches the MIGRATION_1_2 `DEFAULT 'EXPENSE'` exactly so the
    // schema validates on both fresh installs and upgrades.
    @ColumnInfo(defaultValue = "'EXPENSE'") val usage: CategoryUsage = CategoryUsage.EXPENSE,
)
