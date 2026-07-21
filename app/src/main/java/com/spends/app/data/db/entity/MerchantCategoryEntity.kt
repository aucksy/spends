package com.spends.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Learned merchant→category/note memory (#14): when the user confirms or corrects a captured
 * bank-SMS transaction, we remember the category (and the note they typed, if any) keyed by a
 * normalized merchant name ([com.spends.app.data.capture.MerchantKeys]), so the next SMS from the
 * same merchant pre-fills both. Travels in the backup snapshot (v5) so a new phone keeps the learning.
 */
@Entity(tableName = "merchant_categories")
data class MerchantCategoryEntity(
    @PrimaryKey val merchantKey: String,
    val categoryId: Long,
    val updatedAt: Long,
    // The user's last note for this merchant (DB v15). Nullable String? -> TEXT, no NOT NULL.
    val note: String? = null,
)
