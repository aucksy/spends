package com.spends.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.spends.app.data.db.entity.AllocationEntity
import com.spends.app.data.db.entity.CategorySpend
import com.spends.app.data.db.entity.ExpenseEntity
import com.spends.app.data.db.entity.ExpenseWithAllocations
import com.spends.app.data.db.entity.KindSum
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {

    // ---- Timeline (active, soft-deleted excluded) ----

    @Transaction
    @Query(
        "SELECT * FROM expenses WHERE deletedAt IS NULL " +
            "AND occurredAt >= :start AND occurredAt < :end ORDER BY occurredAt DESC, id DESC",
    )
    fun observeActiveBetween(start: Long, end: Long): Flow<List<ExpenseWithAllocations>>

    @Transaction
    @Query(
        "SELECT * FROM expenses WHERE deletedAt IS NULL " +
            "AND (:query = '' OR merchantRaw LIKE '%' || :query || '%' OR note LIKE '%' || :query || '%') " +
            "ORDER BY occurredAt DESC, id DESC",
    )
    fun observeActiveSearch(query: String): Flow<List<ExpenseWithAllocations>>

    /**
     * Active transactions in [start, end) that have at least one allocation to [categoryId], newest
     * first. Backs the per-category drill-down from Analytics. EXISTS keeps a transaction listed once
     * even when it splits across categories (no row fan-out from the allocation join).
     */
    @Transaction
    @Query(
        "SELECT * FROM expenses WHERE deletedAt IS NULL " +
            "AND occurredAt >= :start AND occurredAt < :end " +
            "AND EXISTS (SELECT 1 FROM allocations a WHERE a.expenseId = expenses.id AND a.categoryId = :categoryId) " +
            "ORDER BY occurredAt DESC, id DESC",
    )
    fun observeByCategoryBetween(categoryId: Long, start: Long, end: Long): Flow<List<ExpenseWithAllocations>>

    @Transaction
    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getByIdWithAllocations(id: Long): ExpenseWithAllocations?

    /** Low-confidence SMS captures awaiting a quick confirm (PRD §4.3 review queue). */
    @Transaction
    @Query(
        "SELECT * FROM expenses WHERE deletedAt IS NULL AND source = 'SMS' AND parseConfidence < :threshold " +
            "ORDER BY occurredAt DESC, id DESC",
    )
    fun observeNeedsReview(threshold: Int): Flow<List<ExpenseWithAllocations>>

    @Query("UPDATE expenses SET parseConfidence = :value, updatedAt = :ts WHERE id = :id")
    suspend fun setParseConfidence(id: Long, value: Int, ts: Long)

    /** Untag every expense paid with [id] (→ Bank bucket) — used when a card is deleted. */
    @Query("UPDATE expenses SET paymentMethodId = NULL, updatedAt = :ts WHERE paymentMethodId = :id")
    suspend fun clearPaymentMethod(id: Long, ts: Long)

    /** Re-point every expense from card [from] to card [to] — used when two cards are merged. */
    @Query("UPDATE expenses SET paymentMethodId = :to, updatedAt = :ts WHERE paymentMethodId = :from")
    suspend fun reassignPaymentMethod(from: Long, to: Long, ts: Long)

    // ---- Trash ----

    @Transaction
    @Query("SELECT * FROM expenses WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC, id DESC")
    fun observeTrashed(): Flow<List<ExpenseWithAllocations>>

    @Query("SELECT COUNT(*) FROM expenses WHERE deletedAt IS NOT NULL")
    fun observeTrashCount(): Flow<Int>

    // ---- Aggregations (SQL, period-bounded) ----

    @Query(
        "SELECT kind AS kind, SUM(amountMinor) AS total FROM expenses " +
            "WHERE deletedAt IS NULL AND occurredAt >= :start AND occurredAt < :end GROUP BY kind",
    )
    fun observeKindSums(start: Long, end: Long): Flow<List<KindSum>>

    /** One-shot kind sums for [start, end) — for the home-screen summary widget (RemoteViews, no Flow). */
    @Query(
        "SELECT kind AS kind, SUM(amountMinor) AS total FROM expenses " +
            "WHERE deletedAt IS NULL AND occurredAt >= :start AND occurredAt < :end GROUP BY kind",
    )
    suspend fun kindSumsOnce(start: Long, end: Long): List<KindSum>

    /**
     * Spend by category for the donut/legend. Transfers stay out (they're neutral money movement),
     * but EMIs/loans/investments ARE included now — the user wants every expense category to count as
     * spending, so the donut total reconciles with the Expense tile.
     */
    @Query(
        "SELECT c.id AS categoryId, c.name AS name, c.colorHex AS colorHex, c.iconKey AS iconKey, " +
            "SUM(a.amountMinor) AS total " +
            "FROM allocations a " +
            "JOIN expenses e ON e.id = a.expenseId " +
            "JOIN categories c ON c.id = a.categoryId " +
            "WHERE e.deletedAt IS NULL AND e.kind = 'EXPENSE' " +
            "AND e.occurredAt >= :start AND e.occurredAt < :end " +
            "GROUP BY c.id ORDER BY total DESC",
    )
    fun observeCategorySpend(start: Long, end: Long): Flow<List<CategorySpend>>

    /** Running balance (income − expense) for everything strictly before [before] — for Carry Forward. */
    @Query(
        "SELECT COALESCE(SUM(CASE kind WHEN 'INCOME' THEN amountMinor WHEN 'EXPENSE' THEN -amountMinor ELSE 0 END), 0) " +
            "FROM expenses WHERE deletedAt IS NULL AND occurredAt < :before",
    )
    fun observeBalanceBefore(before: Long): Flow<Long>

    /** Earliest active transaction time — the lower bound for the cycle selector's "All" range. */
    @Query("SELECT MIN(occurredAt) FROM expenses WHERE deletedAt IS NULL")
    fun observeEarliestOccurredAt(): Flow<Long?>

    /** Income timestamps — used to auto-detect the salary day for the Smart cycle. */
    @Query("SELECT occurredAt FROM expenses WHERE deletedAt IS NULL AND kind = 'INCOME'")
    fun observeIncomeOccurredAt(): Flow<List<Long>>

    /**
     * Active, card-tagged EXPENSE rows since [since] (the Cards feature). Each card has its OWN billing
     * cycle (different window per card), so the ViewModel slices these into each card's window in memory
     * rather than one GROUP-BY query. [since] should cover the longest possible cycle (~2 months back).
     */
    @Query(
        "SELECT * FROM expenses WHERE deletedAt IS NULL AND kind = 'EXPENSE' " +
            "AND paymentMethodId IS NOT NULL AND occurredAt >= :since",
    )
    fun observeCardExpensesSince(since: Long): Flow<List<ExpenseEntity>>

    // ---- Mutations ----

    @Insert
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Insert
    suspend fun insertAllocations(allocations: List<AllocationEntity>)

    @Update
    suspend fun updateExpense(expense: ExpenseEntity)

    @Query("DELETE FROM allocations WHERE expenseId = :expenseId")
    suspend fun deleteAllocationsFor(expenseId: Long)

    @Query("UPDATE expenses SET deletedAt = :ts, updatedAt = :ts WHERE id = :id")
    suspend fun softDelete(id: Long, ts: Long)

    @Query("UPDATE expenses SET deletedAt = NULL, updatedAt = :ts WHERE id = :id")
    suspend fun restore(id: Long, ts: Long)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteForever(id: Long)

    @Query("DELETE FROM expenses WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeTrashOlderThan(cutoff: Long): Int

    // ---- Capture cleanup (#5: delete all SMS-captured transactions) ----

    @Query("SELECT COUNT(*) FROM expenses WHERE source = 'SMS'")
    suspend fun countCaptured(): Int

    /** Live (non-trashed) SMS-captured transactions — drives whether the "delete scanned data" control is
     *  reachable even after the review queue has drained (#7). */
    @Query("SELECT COUNT(*) FROM expenses WHERE deletedAt IS NULL AND source = 'SMS'")
    fun observeCapturedCount(): Flow<Int>

    @Query("DELETE FROM allocations WHERE expenseId IN (SELECT id FROM expenses WHERE source = 'SMS')")
    suspend fun deleteCapturedAllocations()

    @Query("DELETE FROM expenses WHERE source = 'SMS'")
    suspend fun deleteCapturedExpenses()

    // ---- Import support ----

    @Query("SELECT dedupeHash FROM expenses WHERE dedupeHash IS NOT NULL")
    suspend fun allDedupeHashes(): List<String>

    @Query("SELECT COUNT(*) FROM expenses")
    suspend fun totalCount(): Int

    // ---- Backup / restore ----

    @Query("SELECT * FROM expenses")
    suspend fun getAllExpensesOnce(): List<ExpenseEntity>

    @Query("SELECT * FROM allocations")
    suspend fun getAllAllocationsOnce(): List<AllocationEntity>

    @Insert
    suspend fun insertExpenses(expenses: List<ExpenseEntity>)

    @Query("DELETE FROM allocations")
    suspend fun deleteAllAllocations()

    @Query("DELETE FROM expenses")
    suspend fun deleteAllExpenses()
}
