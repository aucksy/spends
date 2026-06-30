package com.spends.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spends.app.data.db.entity.IgnoredPatternEntity

@Dao
interface IgnoredPatternDao {

    @Query("SELECT ignoreCount FROM ignored_patterns WHERE patternKey = :key")
    suspend fun countFor(key: String): Int?

    // REPLACE upsert (avoids SQLite UPSERT, which needs API 30+); the repo reads-then-writes the new count.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: IgnoredPatternEntity)
}
