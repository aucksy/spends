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

    // occurredAt is the SMS date pinned to noon, so it can't separate same-day rows; tie-break by the
    // real message timestamp (receivedAt) then id so the queue reads newest-first by date AND time (#11).
    @Query("SELECT * FROM pending_captures ORDER BY occurredAt DESC, receivedAt DESC, id DESC")
    fun observeAll(): Flow<List<PendingCaptureEntity>>

    @Query("SELECT COUNT(*) FROM pending_captures")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_captures WHERE id = :id")
    suspend fun getById(id: Long): PendingCaptureEntity?

    @Query("SELECT * FROM pending_captures ORDER BY occurredAt DESC, receivedAt DESC, id DESC")
    suspend fun getAllOnce(): List<PendingCaptureEntity>

    /** Re-categorise a queued capture in place — review-only, NEVER writes to the ledger (#9). */
    @Query("UPDATE pending_captures SET categoryId = :categoryId WHERE id = :id")
    suspend fun setCategory(id: Long, categoryId: Long)

    @Query("SELECT dedupeHash FROM pending_captures")
    suspend fun allHashes(): List<String>

    @Query("DELETE FROM pending_captures WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_captures")
    suspend fun deleteAll()
}
