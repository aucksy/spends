package com.spends.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.spends.app.data.db.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY sortOrder ASC, name ASC")
    fun observeActive(): Flow<List<CategoryEntity>>

    /**
     * Active categories ordered by how often they're actually used (most-used first), so the
     * picker keeps the user's frequent categories at the top. Falls back to seed order, then name.
     */
    @Query(
        "SELECT c.* FROM categories c " +
            "LEFT JOIN (" +
            "  SELECT a.categoryId AS cid, COUNT(*) AS cnt FROM allocations a " +
            "  JOIN expenses e ON e.id = a.expenseId AND e.deletedAt IS NULL " +
            "  GROUP BY a.categoryId" +
            ") u ON u.cid = c.id " +
            "WHERE c.isArchived = 0 " +
            "ORDER BY COALESCE(u.cnt, 0) DESC, c.sortOrder ASC, c.name ASC",
    )
    fun observeActiveByUsage(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): CategoryEntity?

    @Query("SELECT colorHex FROM categories")
    suspend fun allColors(): List<String>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM allocations WHERE categoryId = :categoryId")
    suspend fun allocationCount(categoryId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity): Long

    // ---- Backup / restore ----

    @Query("SELECT * FROM categories")
    suspend fun getAllOnce(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE categories SET iconKey = :iconKey WHERE id = :id")
    suspend fun updateIcon(id: Long, iconKey: String)

    /** Set the icon AND mark it customized in one write (#5) — used when the user hand-picks an icon. */
    @Query("UPDATE categories SET iconKey = :iconKey, iconCustomized = :customized WHERE id = :id")
    suspend fun setIconCustom(id: Long, iconKey: String, customized: Boolean)

    @Query("UPDATE categories SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: Long)
}
