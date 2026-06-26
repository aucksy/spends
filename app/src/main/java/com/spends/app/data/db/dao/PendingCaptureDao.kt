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

    // Newest-first by the REAL SMS timestamp (receivedAt has full date+time). occurredAt is the parsed
    // date pinned to noon, so it loses the time and mis-orders same-day rows — sort on receivedAt (#8).
    @Query("SELECT * FROM pending_captures ORDER BY receivedAt DESC, id DESC")
    fun observeAll(): Flow<List<PendingCaptureEntity>>

    @Query("SELECT COUNT(*) FROM pending_captures")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_captures WHERE id = :id")
    suspend fun getById(id: Long): PendingCaptureEntity?

    @Query("SELECT * FROM pending_captures ORDER BY receivedAt DESC, id DESC")
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
