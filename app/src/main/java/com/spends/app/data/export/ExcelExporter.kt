package com.spends.app.data.export

import com.spends.app.core.export.XlsxWriter
import com.spends.app.core.export.XlsxWriter.Cell
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.db.entity.AllocationEntity
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
        "Date", "Time", "Type", "Category", "Amount (₹)", "Balance impact (₹)",
        "Merchant / Payee", "Note", "Source", "Split details", "Created",
    )

    suspend fun build(): ByteArray = withContext(Dispatchers.Default) {
        val categoryNames = db.categoryDao().getAllOnce().associateBy({ it.id }, { it.name })
        val allocationsByExpense = db.expenseDao().getAllAllocationsOnce().groupBy { it.expenseId }
        val expenses = db.expenseDao().getAllExpensesOnce()
            .filter { it.deletedAt == null }
            .sortedByDescending { it.occurredAt }

        val rows = expenses.map { e ->
            val allocs = allocationsByExpense[e.id].orEmpty()
            val primaryCategory = allocs.firstOrNull()?.let { categoryNames[it.categoryId] } ?: "Uncategorized"
            val amount = BigDecimal(e.amountMinor).movePointLeft(2)
            val impact = when (e.kind) {
                TxnKind.INCOME -> amount
                TxnKind.EXPENSE -> amount.negate()
                TxnKind.TRANSFER -> BigDecimal.ZERO
            }
            listOf(
                Cell.DateNum(excelDateSerial(e.occurredAt).toString()),
                Cell.Str(timeFmt.format(zoned(e.occurredAt))),
                Cell.Str(prettyKind(e.kind)),
                Cell.Str(primaryCategory),
                Cell.Num(amount.toPlainString()),
                Cell.Num(impact.toPlainString()),
                Cell.Str(e.merchantRaw.orEmpty()),
                Cell.Str(e.note.orEmpty()),
                Cell.Str(prettySource(e.source)),
                Cell.Str(splitDetails(allocs, categoryNames)),
                Cell.DateTimeNum(excelDateTimeSerial(e.createdAt).toString()),
            )
        }
        XlsxWriter.build(sheetName = "Spends", header = header, rows = rows)
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

    private fun prettyKind(kind: TxnKind): String =
        kind.name.lowercase(Locale.ENGLISH).replaceFirstChar { it.uppercase() }

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
