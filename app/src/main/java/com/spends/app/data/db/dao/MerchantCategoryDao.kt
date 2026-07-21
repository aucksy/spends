package com.spends.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spends.app.data.db.entity.MerchantCategoryEntity

@Dao
interface MerchantCategoryDao {

    /** Upsert the learned category/note for a merchant key. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MerchantCategoryEntity)

    /** The learned entry for an exact (normalized) merchant key, or null. */
    @Query("SELECT * FROM merchant_categories WHERE merchantKey = :merchantKey LIMIT 1")
    suspend fun getByKey(merchantKey: String): MerchantCategoryEntity?

    /** Every learned entry — the table is small, so fuzzy matching runs over it in memory. */
    @Query("SELECT * FROM merchant_categories")
    suspend fun getAllOnce(): List<MerchantCategoryEntity>

    /** Drop mappings that point at a category that no longer exists (e.g. after a restore/delete). */
    @Query("DELETE FROM merchant_categories WHERE categoryId NOT IN (SELECT id FROM categories)")
    suspend fun pruneOrphans()

    // ---- Backup / restore ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<MerchantCategoryEntity>)

    @Query("DELETE FROM merchant_categories")
    suspend fun deleteAll()
}
