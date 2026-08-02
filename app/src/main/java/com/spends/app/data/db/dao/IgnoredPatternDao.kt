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

    /** Most-recently-ignored first, so the alert the owner just silenced by accident is at the top. */
    @Query("SELECT * FROM ignored_patterns ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<IgnoredPatternEntity>>

    /** Live count of patterns that have actually crossed the threshold (see IGNORE_SUPPRESS_THRESHOLD). */
    @Query("SELECT COUNT(*) FROM ignored_patterns WHERE ignoreCount >= :threshold")
    fun observeSilencedCount(threshold: Int): Flow<Int>

    /** Un-silence: drop the row entirely so the count restarts at zero, not at the threshold minus one. */
    @Query("DELETE FROM ignored_patterns WHERE patternKey = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM ignored_patterns")
    suspend fun deleteAll()
}
