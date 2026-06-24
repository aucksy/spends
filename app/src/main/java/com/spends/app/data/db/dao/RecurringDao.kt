package com.spends.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.spends.app.data.db.entity.RecurringRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringDao {

    @Query("SELECT * FROM recurring_rules ORDER BY active DESC, nextRunAt ASC, id DESC")
    fun observeAll(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules WHERE id = :id")
    suspend fun getById(id: Long): RecurringRuleEntity?

    /** Active rules whose next occurrence is before [cutoff] (typically start-of-tomorrow). */
    @Query("SELECT * FROM recurring_rules WHERE active = 1 AND nextRunAt < :cutoff")
    suspend fun getActiveDueBefore(cutoff: Long): List<RecurringRuleEntity>

    @Insert
    suspend fun insert(rule: RecurringRuleEntity): Long

    @Update
    suspend fun update(rule: RecurringRuleEntity)

    @Query("UPDATE recurring_rules SET active = :active, updatedAt = :ts WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean, ts: Long)

    @Query("DELETE FROM recurring_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ---- Backup / restore ----

    @Query("SELECT * FROM recurring_rules")
    suspend fun getAllOnce(): List<RecurringRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<RecurringRuleEntity>)

    @Query("DELETE FROM recurring_rules")
    suspend fun deleteAll()
}
