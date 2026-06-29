package com.spends.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.spends.app.data.db.entity.PaymentMethodEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentMethodDao {

    /** Confirmed cards the user manages (newest-active first). Excludes candidates + dismissed rows. */
    @Query("SELECT * FROM payment_methods WHERE reviewed = 1 AND dismissed = 0 ORDER BY lastActivityAt DESC, id DESC")
    fun observeConfirmed(): Flow<List<PaymentMethodEntity>>

    /** Auto-discovered candidates awaiting review (the "Cards to review" section). */
    @Query("SELECT * FROM payment_methods WHERE reviewed = 0 AND dismissed = 0 ORDER BY lastActivityAt DESC, id DESC")
    fun observeCandidates(): Flow<List<PaymentMethodEntity>>

    @Query("SELECT * FROM payment_methods WHERE id = :id")
    suspend fun getById(id: Long): PaymentMethodEntity?

    /**
     * Any existing row (confirmed, candidate, OR dismissed) for this instrument, so discovery never
     * re-proposes a card the user already has or already rejected. Matched on last4 + institution.
     */
    @Query("SELECT * FROM payment_methods WHERE last4 = :last4 AND institution = :institution LIMIT 1")
    suspend fun findByLast4AndInstitution(last4: String, institution: String): PaymentMethodEntity?

    /** A confirmed card matching this last4 — used to auto-fill "Paid with" on an SMS-detected expense. */
    @Query("SELECT * FROM payment_methods WHERE reviewed = 1 AND dismissed = 0 AND last4 = :last4 ORDER BY lastActivityAt DESC LIMIT 1")
    suspend fun findConfirmedByLast4(last4: String): PaymentMethodEntity?

    @Insert
    suspend fun insert(card: PaymentMethodEntity): Long

    @Update
    suspend fun update(card: PaymentMethodEntity)

    @Query("DELETE FROM payment_methods WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Mark a candidate as "Not a card": keep the row (hidden) so discovery won't re-propose it. */
    @Query("UPDATE payment_methods SET dismissed = 1, reviewed = 1 WHERE id = :id")
    suspend fun dismiss(id: Long)

    /** Touch lastActivityAt when an instrument is used again (keeps the most-used cards on top). */
    @Query("UPDATE payment_methods SET lastActivityAt = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)

    // ---- Backup / restore ----

    @Query("SELECT * FROM payment_methods")
    suspend fun getAllOnce(): List<PaymentMethodEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cards: List<PaymentMethodEntity>)

    @Query("DELETE FROM payment_methods")
    suspend fun deleteAll()
}
