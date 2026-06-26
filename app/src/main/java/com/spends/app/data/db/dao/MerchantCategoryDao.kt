package com.spends.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spends.app.data.db.entity.MerchantCategoryEntity

@Dao
interface MerchantCategoryDao {

    /** Upsert the learned category for a merchant key. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MerchantCategoryEntity)

    /**
     * The learned category id for a merchant key, or null if we've never learned one OR the learned
     * category no longer exists. The JOIN guards against a stale id pointing at a deleted category,
     * which would otherwise violate the allocation FK on insert.
     */
    @Query(
        "SELECT m.categoryId FROM merchant_categories m " +
            "JOIN categories c ON c.id = m.categoryId " +
            "WHERE m.merchantKey = :merchantKey LIMIT 1",
    )
    suspend fun categoryFor(merchantKey: String): Long?

    /** Drop mappings that point at a category that no longer exists (e.g. after a restore/delete). */
    @Query("DELETE FROM merchant_categories WHERE categoryId NOT IN (SELECT id FROM categories)")
    suspend fun pruneOrphans()
}
