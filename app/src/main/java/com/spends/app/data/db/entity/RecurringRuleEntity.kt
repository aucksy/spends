package com.spends.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.spends.app.domain.model.RecurrenceFreq
import com.spends.app.domain.model.TxnKind

/**
 * A rule that auto-creates a transaction on a schedule (PRD §4.8). It is *not* a transaction itself —
 * a worker/launch hook materialises real [ExpenseEntity] rows (source = RECURRING) for each due date
 * and advances [nextRunAt]. No FK on [categoryId] (mirrors [ExpenseEntity.paymentMethodId]) so a
 * rule survives independently; the picker only ever selects an existing category.
 *
 * [anchorDay] is the day-of-month (1..31) for MONTHLY/YEARLY, or day-of-week (1=Mon..7=Sun) for
 * WEEKLY; it is ignored for DAILY. [startDate]/[nextRunAt] are epoch-millis (IST).
 */
@Entity(
    tableName = "recurring_rules",
    indices = [Index("nextRunAt"), Index("active")],
)
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMinor: Long,
    val kind: TxnKind,
    val categoryId: Long,
    val merchant: String? = null,
    val note: String? = null,
    val frequency: RecurrenceFreq,
    val intervalCount: Int = 1,
    val anchorDay: Int = 1,
    val startDate: Long,
    val nextRunAt: Long,
    val lastRunAt: Long? = null,
    val active: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    // 0 = repeats forever; N>0 = stop after N occurrences total (e.g. an EMI for 12 months), counted from
    // [startDate] by cadence (#8). The rule deactivates once the Nth occurrence has been generated.
    @ColumnInfo(defaultValue = "0") val occurrenceLimit: Int = 0,
    // The instrument each generated transaction is paid with (#6). null = Bank (the salary-cycle bucket),
    // exactly like [ExpenseEntity.paymentMethodId]. No FK, so deleting a card never orphans the rule.
    val paymentMethodId: Long? = null,
)
