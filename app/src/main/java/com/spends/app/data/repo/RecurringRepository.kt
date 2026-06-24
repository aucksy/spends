package com.spends.app.data.repo

import com.spends.app.core.time.DateUtils
import com.spends.app.core.time.RecurrenceMath
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.db.entity.RecurringRuleEntity
import com.spends.app.domain.model.RecurrenceFreq
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Everything needed to create or update a recurring rule (start date is an epoch-millis anchor). */
data class RecurringInput(
    val amountMinor: Long,
    val kind: TxnKind,
    val categoryId: Long,
    val merchant: String?,
    val note: String?,
    val frequency: RecurrenceFreq,
    val intervalCount: Int,
    val startDate: Long,
)

@Singleton
class RecurringRepository @Inject constructor(
    db: SpendsDatabase,
    private val expenseRepository: ExpenseRepository,
) {
    private val dao = db.recurringDao()

    // Guards a single materialisation pass from backfilling an unbounded number of rows if a rule's
    // start date is far in the past.
    private val maxCatchUpPerRule = 120

    fun observeAll(): Flow<List<RecurringRuleEntity>> = dao.observeAll()

    suspend fun getById(id: Long): RecurringRuleEntity? = dao.getById(id)

    suspend fun add(input: RecurringInput): Long {
        val now = DateUtils.nowMillis()
        val startLocal = DateUtils.toLocalDate(input.startDate)
        val rule = RecurringRuleEntity(
            amountMinor = input.amountMinor,
            kind = input.kind,
            categoryId = input.categoryId,
            merchant = input.merchant?.ifBlank { null },
            note = input.note?.ifBlank { null },
            frequency = input.frequency,
            intervalCount = input.intervalCount.coerceAtLeast(1),
            anchorDay = RecurrenceMath.anchorFor(input.frequency, startLocal),
            startDate = input.startDate,
            // Fire on/after the start date; a past start backfills (capped) on the next pass.
            nextRunAt = DateUtils.startOfDayMillis(startLocal),
            active = true,
            createdAt = now,
            updatedAt = now,
        )
        return dao.insert(rule)
    }

    suspend fun update(id: Long, input: RecurringInput) {
        val existing = dao.getById(id) ?: return
        val now = DateUtils.nowMillis()
        val startLocal = DateUtils.toLocalDate(input.startDate)
        dao.update(
            existing.copy(
                amountMinor = input.amountMinor,
                kind = input.kind,
                categoryId = input.categoryId,
                merchant = input.merchant?.ifBlank { null },
                note = input.note?.ifBlank { null },
                frequency = input.frequency,
                intervalCount = input.intervalCount.coerceAtLeast(1),
                anchorDay = RecurrenceMath.anchorFor(input.frequency, startLocal),
                startDate = input.startDate,
                nextRunAt = DateUtils.startOfDayMillis(startLocal),
                updatedAt = now,
            ),
        )
    }

    suspend fun setActive(id: Long, active: Boolean) =
        dao.setActive(id, active, DateUtils.nowMillis())

    suspend fun delete(id: Long) = dao.deleteById(id)

    /**
     * Create the real transactions for every active rule that's due (including any missed past
     * dates, capped). Advances each rule's [RecurringRuleEntity.nextRunAt]. Returns how many
     * transactions were created. Best-effort and idempotent per-day: safe to call on every launch.
     */
    suspend fun materializeDue(now: Long): Int {
        val today = DateUtils.toLocalDate(now)
        val cutoff = DateUtils.endOfDayExclusiveMillis(today) // start of tomorrow
        val due = dao.getActiveDueBefore(cutoff)
        var created = 0
        for (rule in due) {
            runCatching {
                var date = DateUtils.toLocalDate(rule.nextRunAt)
                var last = rule.lastRunAt
                var guard = 0
                while (!date.isAfter(today) && guard < maxCatchUpPerRule) {
                    val occurredAt = DateUtils.epochMillisFor(date, 12, 0)
                    expenseRepository.create(
                        TransactionInput(
                            amountMinor = rule.amountMinor,
                            kind = rule.kind,
                            occurredAt = occurredAt,
                            merchantRaw = rule.merchant,
                            note = rule.note,
                            allocations = listOf(AllocationInput(rule.categoryId, rule.amountMinor)),
                            source = TxnSource.RECURRING,
                        ),
                    )
                    last = occurredAt
                    created++
                    date = RecurrenceMath.nextDate(date, rule.frequency, rule.intervalCount, rule.anchorDay)
                    guard++
                }
                dao.update(rule.copy(nextRunAt = DateUtils.startOfDayMillis(date), lastRunAt = last, updatedAt = now))
            }
        }
        return created
    }
}
