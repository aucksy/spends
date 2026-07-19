package com.spends.app.data.export

import com.spends.app.core.export.XlsxWriter
import com.spends.app.core.export.XlsxWriter.Cell
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.db.entity.AllocationEntity
import com.spends.app.data.db.entity.ExpenseEntity
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a single-sheet .xlsx of every (non-trashed) transaction with all the information we hold,
 * for opening in Excel/Sheets. This is a readable export — distinct from the encrypted backup.
 */
@Singleton
class ExcelExporter @Inject constructor(
    private val db: SpendsDatabase,
) {
    // Date + Created are written as real Excel date / date-time cells (numeric serials) so they sort &
    // filter properly; only the short Time label stays text (#6).
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    private val header = listOf(
        "Date", "Time", "Category", "Income (₹)", "Expenses (₹)", "Balance (₹)",
        "Merchant / Payee", "Note", "Source", "Split details", "Created",
    )

    /** Export every non-trashed transaction (all time). */
    suspend fun build(): ByteArray = build(Long.MIN_VALUE, Long.MAX_VALUE)

    /**
     * Export non-trashed transactions whose [occurredAt] falls in `[startMillis, endExclusiveMillis)` (#4).
     * Pass `Long.MIN_VALUE .. Long.MAX_VALUE` for "all time". An empty window yields a header-only sheet.
     *
     * Money is laid out like a bank passbook (three columns): the amount goes in **Income** or **Expenses**
     * by its kind, and **Balance** is the running total (money left after each row). The running balance is
     * accumulated in date order and opens at the balance of everything before this window — so exporting a
     * single cycle still starts from what was carried into it, not from zero.
     */
    suspend fun build(startMillis: Long, endExclusiveMillis: Long): ByteArray = withContext(Dispatchers.Default) {
        val categoryNames = db.categoryDao().getAllOnce().associateBy({ it.id }, { it.name })
        val allocationsByExpense = db.expenseDao().getAllAllocationsOnce().groupBy { it.expenseId }
        val active = db.expenseDao().getAllExpensesOnce().filter { it.deletedAt == null }

        // Opening balance = income − expense of everything strictly before the window start.
        val opening = active.filter { it.occurredAt < startMillis }.sumOf { impactMinor(it) }

        val windowRows = active.filter { it.occurredAt >= startMillis && it.occurredAt < endExclusiveMillis }

        // Running balance is accumulated OLDEST → NEWEST (a passbook adds up top-to-bottom in time),
        // then each row is displayed NEWEST → oldest carrying the balance it settled at.
        val balanceAfter = HashMap<Long, Long>(windowRows.size)
        var running = opening
        windowRows.sortedWith(compareBy({ it.occurredAt }, { it.id })).forEach { e ->
            running += impactMinor(e)
            balanceAfter[e.id] = running
        }

        val display = windowRows.sortedWith(
            compareByDescending<ExpenseEntity> { it.occurredAt }.thenByDescending { it.id },
        )

        val rows = display.map { e ->
            val allocs = allocationsByExpense[e.id].orEmpty()
            val primaryCategory = allocs.firstOrNull()?.let { categoryNames[it.categoryId] } ?: "Uncategorized"
            val amount = BigDecimal(e.amountMinor).movePointLeft(2).toPlainString()
            val balance = BigDecimal(balanceAfter[e.id] ?: opening).movePointLeft(2).toPlainString()
            listOf(
                Cell.DateNum(excelDateSerial(e.occurredAt).toString()),
                Cell.Str(timeFmt.format(zoned(e.occurredAt))),
                Cell.Str(primaryCategory),
                if (e.kind == TxnKind.INCOME) Cell.Num(amount) else Cell.Str(""),
                if (e.kind == TxnKind.EXPENSE) Cell.Num(amount) else Cell.Str(""),
                Cell.Num(balance),
                Cell.Str(e.merchantRaw.orEmpty()),
                Cell.Str(e.note.orEmpty()),
                Cell.Str(prettySource(e.source)),
                Cell.Str(splitDetails(allocs, categoryNames)),
                Cell.DateTimeNum(excelDateTimeSerial(e.createdAt).toString()),
            )
        }
        XlsxWriter.build(sheetName = "Spends", header = header, rows = rows)
    }

    /** A row's effect on the running balance: income adds, expense subtracts. */
    private fun impactMinor(e: ExpenseEntity): Long = when (e.kind) {
        TxnKind.INCOME -> e.amountMinor
        TxnKind.EXPENSE -> -e.amountMinor
    }

    private fun zoned(millis: Long) = Instant.ofEpochMilli(millis).atZone(DateUtils.ZONE)

    // Excel's date epoch is 1899-12-30 (serial 0): a date's serial = its epoch-day + 25569; a date-time
    // adds the fraction of the day. Emitted as numbers so Excel/Sheets sort & filter them as real dates (#6).
    private fun excelDateSerial(millis: Long): Long =
        zoned(millis).toLocalDate().toEpochDay() + 25569

    private fun excelDateTimeSerial(millis: Long): Double {
        val z = zoned(millis)
        return (z.toLocalDate().toEpochDay() + 25569).toDouble() + z.toLocalTime().toSecondOfDay() / 86400.0
    }

    private fun prettySource(source: TxnSource): String = when (source) {
        TxnSource.SMS -> "SMS"
        else -> source.name.lowercase(Locale.ENGLISH).replaceFirstChar { it.uppercase() }
    }

    /** "Groceries: ₹400.00; Fuel: ₹600.00" — only when the transaction splits across categories. */
    private fun splitDetails(allocs: List<AllocationEntity>, names: Map<Long, String>): String {
        if (allocs.size <= 1) return ""
        return allocs.joinToString("; ") { a ->
            val name = names[a.categoryId] ?: "?"
            "$name: ₹${BigDecimal(a.amountMinor).movePointLeft(2).toPlainString()}"
        }
    }
}
