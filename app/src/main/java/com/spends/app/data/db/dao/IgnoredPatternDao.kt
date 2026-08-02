package com.spends.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spends.app.data.db.entity.IgnoredPatternEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IgnoredPatternDao {

    @Query("SELECT ignoreCount FROM ignored_patterns WHERE patternKey = :key")
    suspend fun countFor(key: String): Int?

    // REPLACE upsert (avoids SQLite UPSERT, which needs API 30+); the repo reads-then-writes the new count.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: IgnoredPatternEntity)

    /**
     * Most-recently-ignored first, so the alert the owner just silenced by accident is at the top.
     *
     * Legacy four-field keys are excluded. Until v1.69.0 the key carried the AMOUNT, which meant every
     * alert made its own row and none could ever reach the threshold; those rows can never match a lookup
     * again, so showing them would just be clutter the owner cannot act on meaningfully. A current key has
     * exactly two separators (the merchant has its own stripped out), so three is unambiguously the old
     * shape. [deleteLegacy] removes them for real.
     */
    @Query("SELECT * FROM ignored_patterns WHERE patternKey NOT LIKE '%|%|%|%' ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<IgnoredPatternEntity>>

    /** Live count of patterns that have actually crossed the threshold (see IGNORE_SUPPRESS_THRESHOLD). */
    @Query(
        "SELECT COUNT(*) FROM ignored_patterns " +
            "WHERE ignoreCount >= :threshold AND patternKey NOT LIKE '%|%|%|%'",
    )
    fun observeSilencedCount(threshold: Int): Flow<Int>

    /** Drop the pre-v1.69.0 amount-bearing keys. Idempotent, so it needs no "have I run yet" flag. */
    @Query("DELETE FROM ignored_patterns WHERE patternKey LIKE '%|%|%|%'")
    suspend fun deleteLegacy(): Int

    /** Un-silence: drop the row entirely so the count restarts at zero, not at the threshold minus one. */
    @Query("DELETE FROM ignored_patterns WHERE patternKey = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM ignored_patterns")
    suspend fun deleteAll()
}
