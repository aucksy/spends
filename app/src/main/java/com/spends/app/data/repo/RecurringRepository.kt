package com.spends.app.data.repo

import androidx.room.withTransaction
import com.spends.app.core.time.DateUtils
import com.spends.app.core.time.RecurrenceMath
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.db.entity.RecurringRuleEntity
import com.spends.app.data.importer.DedupeKey
import com.spends.app.domain.model.RecurrenceFreq
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
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
    private val db: SpendsDatabase,
    private val expenseRepository: ExpenseRepository,
) {
    private val dao = db.recurringDao()

    // Backfill cap: one pass never creates more than this many catch-up rows for a single rule.
    private val maxCatchUpPerRule = 120

    // Serialises materialisation across its two callers (app-launch hook + daily worker, same
    // process) so they cannot run the read-modify-write concurrently and double-create.
    private val materializeMutex = Mutex()

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
            // A new rule fires on/after its start date; a past start intentionally backfills (capped).
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
        val interval = input.intervalCount.coerceAtLeast(1)
        val anchor = RecurrenceMath.anchorFor(input.frequency, startLocal)
        // Editing must NOT backfill: roll the schedule forward to the first occurrence on/after today
        // so changing the amount/note of an old rule never re-creates its past occurrences.
        val nextRun = firstRunOnOrAfter(startLocal, DateUtils.toLocalDate(now), input.frequency, interval, anchor)
        dao.update(
            existing.copy(
                amountMinor = input.amountMinor,
                kind = input.kind,
                categoryId = input.categoryId,
                merchant = input.merchant?.ifBlank { null },
                note = input.note?.ifBlank { null },
                frequency = input.frequency,
                intervalCount = interval,
                anchorDay = anchor,
                startDate = input.startDate,
                nextRunAt = DateUtils.startOfDayMillis(nextRun),
                updatedAt = now,
                // lastRunAt preserved by copy().
            ),
        )
    }

    suspend fun setActive(id: Long, active: Boolean) =
        dao.setActive(id, active, DateUtils.nowMillis())

    suspend fun delete(id: Long) = dao.deleteById(id)

    /**
     * Create the real transactions for every active rule that's due (including missed past dates,
     * capped). Idempotent and safe to call from both the launch hook and the daily worker:
     *  - a [materializeMutex] serialises the two callers in-process;
     *  - each rule's create-loop + its nextRunAt advance run in one [db].withTransaction (atomic, so a
     *    mid-loop failure rolls back the whole rule and re-runs cleanly);
     *  - every occurrence carries a [DedupeKey.forRecurring] hash and is skipped if that hash already
     *    exists, so even an errant rewind can never double-create. Returns occurrences created.
     */
    suspend fun materializeDue(now: Long): Int = materializeMutex.withLock {
        val today = DateUtils.toLocalDate(now)
        val cutoff = DateUtils.endOfDayExclusiveMillis(today) // start of tomorrow
        val due = dao.getActiveDueBefore(cutoff)
        val seen = expenseRepository.existingDedupeHashes()
        var created = 0
        for (rule in due) {
            runCatching {
                db.withTransaction {
                    var date = DateUtils.toLocalDate(rule.nextRunAt)
                    var last = rule.lastRunAt
                    var guard = 0
                    while (!date.isAfter(today) && guard < maxCatchUpPerRule) {
                        val occurredAt = DateUtils.epochMillisFor(date, 12, 0)
                        val hash = DedupeKey.forRecurring(rule.id, occurredAt, rule.amountMinor, rule.kind)
                        if (seen.add(hash)) {
                            expenseRepository.create(
                                TransactionInput(
                                    amountMinor = rule.amountMinor,
                                    kind = rule.kind,
                                    occurredAt = occurredAt,
                                    merchantRaw = rule.merchant,
                                    note = rule.note,
                                    allocations = listOf(AllocationInput(rule.categoryId, rule.amountMinor)),
                                    source = TxnSource.RECURRING,
                                    dedupeHash = hash,
                                ),
                            )
                            last = occurredAt
                            created++
                        }
                        date = RecurrenceMath.nextDate(date, rule.frequency, rule.intervalCount, rule.anchorDay)
                        guard++
                    }
                    dao.update(rule.copy(nextRunAt = DateUtils.startOfDayMillis(date), lastRunAt = last, updatedAt = now))
                }
            }
        }
        created
    }

    /** Roll [start] forward by the cadence until it is on/after [today] (no DB writes). */
    private fun firstRunOnOrAfter(
        start: LocalDate,
        today: LocalDate,
        freq: RecurrenceFreq,
        interval: Int,
        anchor: Int,
    ): LocalDate {
        var date = start
        var guard = 0
        while (date.isBefore(today) && guard < 20_000) {
            date = RecurrenceMath.nextDate(date, freq, interval, anchor)
            guard++
        }
        return date
    }
}
