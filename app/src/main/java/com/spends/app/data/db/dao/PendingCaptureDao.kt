package com.spends.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spends.app.data.db.entity.PendingCaptureEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingCaptureDao {

    /** Queue a parsed historical capture. IGNORE on the unique dedupeHash so a re-scan never doubles. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(capture: PendingCaptureEntity): Long

    @Query("SELECT * FROM pending_captures ORDER BY occurredAt DESC, id DESC")
    fun observeAll(): Flow<List<PendingCaptureEntity>>

    @Query("SELECT COUNT(*) FROM pending_captures")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_captures WHERE id = :id")
    suspend fun getById(id: Long): PendingCaptureEntity?

    @Query("SELECT * FROM pending_captures ORDER BY occurredAt DESC, id DESC")
    suspend fun getAllOnce(): List<PendingCaptureEntity>

    @Query("SELECT dedupeHash FROM pending_captures")
    suspend fun allHashes(): List<String>

    @Query("DELETE FROM pending_captures WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_captures")
    suspend fun deleteAll()
}
