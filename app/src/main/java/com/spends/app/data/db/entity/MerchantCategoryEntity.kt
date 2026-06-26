package com.spends.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Learned merchant→category mapping (#14): when the user confirms or corrects the category of a
 * captured bank-SMS transaction, we remember it here keyed by a normalized merchant name, so the next
 * SMS from the same merchant pre-fills that category. Device-local (not part of the backup snapshot).
 */
@Entity(tableName = "merchant_categories")
data class MerchantCategoryEntity(
    @PrimaryKey val merchantKey: String,
    val categoryId: Long,
    val updatedAt: Long,
)
