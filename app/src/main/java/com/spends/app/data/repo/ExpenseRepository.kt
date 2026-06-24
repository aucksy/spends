package com.spends.app.data.repo

import androidx.room.withTransaction
import com.spends.app.core.time.CycleWindow
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.db.entity.AllocationEntity
import com.spends.app.data.db.entity.CategorySpend
import com.spends.app.data.db.entity.ExpenseEntity
import com.spends.app.data.db.entity.ExpenseWithAllocations
import com.spends.app.data.db.entity.KindSum
import com.spends.app.domain.model.Direction
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** One category slice of a transaction being saved. */
data class AllocationInput(val categoryId: Long, val amountMinor: Long)

/** Everything needed to create or update a transaction. */
data class TransactionInput(
    val amountMinor: Long,
    val kind: TxnKind,
    val occurredAt: Long,
    val merchantRaw: String?,
    val note: String?,
    val allocations: List<AllocationInput>,
    val paymentMethodId: Long? = null,
    val source: TxnSource = TxnSource.MANUAL,
    val dedupeHash: String? = null,
    val parseConfidence: Int = 100,
)

@Singleton
class ExpenseRepository @Inject constructor(
    private val db: SpendsDatabase,
) {
    private val dao = db.expenseDao()

    // ---- Reads ----

    fun observeBetween(window: CycleWindow): Flow<List<ExpenseWithAllocations>> =
        dao.observeActiveBetween(window.startMillis(), window.endExclusiveMillis())

    fun observeSearch(query: String): Flow<List<ExpenseWithAllocations>> =
        dao.observeActiveSearch(query.trim())

    fun observeTrashed(): Flow<List<ExpenseWithAllocations>> = dao.observeTrashed()

    /** Low-confidence SMS captures needing a quick confirm. */
    fun observeNeedsReview(): Flow<List<ExpenseWithAllocations>> = dao.observeNeedsReview(SMS_REVIEW_THRESHOLD)

    /** Mark a captured transaction as confirmed (full confidence → leaves the review queue). */
    suspend fun markReviewed(id: Long) = dao.setParseConfidence(id, 100, DateUtils.nowMillis())

    fun observeTrashCount(): Flow<Int> = dao.observeTrashCount()

    fun observeKindSums(window: CycleWindow): Flow<List<KindSum>> =
        dao.observeKindSums(window.startMillis(), window.endExclusiveMillis())

    fun observeCategorySpend(window: CycleWindow): Flow<List<CategorySpend>> =
        dao.observeCategorySpend(window.startMillis(), window.endExclusiveMillis())

    fun observeCarryForwardInto(window: CycleWindow): Flow<Long> =
        dao.observeBalanceBefore(window.startMillis())

    suspend fun getById(id: Long): ExpenseWithAllocations? = dao.getByIdWithAllocations(id)

    /** All non-null dedupe hashes already in the DB — used to make recurring/import idempotent. */
    suspend fun existingDedupeHashes(): HashSet<String> = dao.allDedupeHashes().toHashSet()

    // ---- Writes ----

    /** `direction` is derived from `kind` for manual entry: income = credit, otherwise debit. */
    private fun directionFor(kind: TxnKind): Direction =
        if (kind == TxnKind.INCOME) Direction.CREDIT else Direction.DEBIT

    suspend fun create(input: TransactionInput): Long = db.withTransaction {
        val now = DateUtils.nowMillis()
        val expenseId = dao.insertExpense(
            ExpenseEntity(
                amountMinor = input.amountMinor,
                occurredAt = input.occurredAt,
                merchantRaw = input.merchantRaw?.ifBlank { null },
                note = input.note?.ifBlank { null },
                paymentMethodId = input.paymentMethodId,
                source = input.source,
                kind = input.kind,
                direction = directionFor(input.kind),
                parseConfidence = input.parseConfidence,
                dedupeHash = input.dedupeHash,
                createdAt = now,
                updatedAt = now,
            ),
        )
        dao.insertAllocations(input.allocations.toEntities(expenseId))
        expenseId
    }

    suspend fun update(id: Long, input: TransactionInput) = db.withTransaction {
        val existing = dao.getByIdWithAllocations(id)?.expense ?: return@withTransaction
        val now = DateUtils.nowMillis()
        dao.updateExpense(
            existing.copy(
                amountMinor = input.amountMinor,
                occurredAt = input.occurredAt,
                merchantRaw = input.merchantRaw?.ifBlank { null },
                note = input.note?.ifBlank { null },
                paymentMethodId = input.paymentMethodId,
                kind = input.kind,
                direction = directionFor(input.kind),
                updatedAt = now,
            ),
        )
        dao.deleteAllocationsFor(id)
        dao.insertAllocations(input.allocations.toEntities(id))
    }

    /**
     * Replace a transaction's category with a single allocation for [categoryId] over the full
     * amount (quick category fix from the timeline). Collapses a split into one category by design.
     */
    suspend fun reassignCategory(expenseId: Long, categoryId: Long) = db.withTransaction {
        val existing = dao.getByIdWithAllocations(expenseId) ?: return@withTransaction
        dao.deleteAllocationsFor(expenseId)
        dao.insertAllocations(
            listOf(AllocationEntity(expenseId = expenseId, categoryId = categoryId, amountMinor = existing.expense.amountMinor)),
        )
        dao.updateExpense(existing.expense.copy(updatedAt = DateUtils.nowMillis()))
    }

    suspend fun moveToTrash(id: Long) = dao.softDelete(id, DateUtils.nowMillis())

    suspend fun restore(id: Long) = dao.restore(id, DateUtils.nowMillis())

    suspend fun deleteForever(id: Long) = dao.deleteForever(id)

    /** Permanently remove trashed rows older than [retentionDays] (auto-purge, PRD §4.19). */
    suspend fun purgeTrashOlderThan(retentionDays: Int): Int {
        val cutoff = DateUtils.nowMillis() - retentionDays.toLong() * 24 * 60 * 60 * 1000
        return dao.purgeTrashOlderThan(cutoff)
    }

    private fun List<AllocationInput>.toEntities(expenseId: Long): List<AllocationEntity> =
        map { AllocationEntity(expenseId = expenseId, categoryId = it.categoryId, amountMinor = it.amountMinor) }

    companion object {
        /** SMS captures below this parse confidence surface in the review queue. */
        const val SMS_REVIEW_THRESHOLD = 70
    }
}
