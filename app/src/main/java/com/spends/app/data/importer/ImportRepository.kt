package com.spends.app.data.importer

import androidx.room.withTransaction
import com.spends.app.core.category.ColorAssigner
import com.spends.app.core.category.IconAssigner
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.db.entity.AllocationEntity
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.ExpenseEntity
import com.spends.app.domain.model.Direction
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

class ImportException(message: String) : Exception(message)

@Singleton
class ImportRepository @Inject constructor(
    private val db: SpendsDatabase,
) {
    private val categoryDao = db.categoryDao()
    private val expenseDao = db.expenseDao()

    /** Read + parse a picked file into a previewable [ParsedImport] (no DB writes yet). */
    suspend fun parse(openStream: () -> InputStream, fileName: String): ParsedImport {
        val reader = SpreadsheetReaders.forFileName(fileName)
            ?: throw ImportException("Unsupported file type. Use an Excel file (.xlsx or .xls) or a .csv.")
        val sheets = openStream().use { reader.read(it) }
        if (sheets.isEmpty()) throw ImportException("That file has no readable sheets.")
        return if (MonitoAdapter.looksLikeMonito(sheets)) {
            MonitoAdapter.parse(sheets).copy(isMonito = true)
        } else {
            GenericAdapter.parse(sheets.first())
        }
    }

    /**
     * Commit a parsed import: create every distinct category (preserving names exactly, assigning
     * icon + distinct color, and its inferred usage/excludeFromSpend), then insert transactions in
     * batches, skipping rows already present (dedupe). Reports progress as (processed, total).
     */
    suspend fun commit(parsed: ParsedImport, onProgress: (Int, Int) -> Unit): ImportSummary {
        val now = DateUtils.nowMillis()

        // 1) Categories — reuse by name (case-insensitive) or create with auto icon/distinct color.
        val takenColors = categoryDao.allColors().toMutableSet()
        val nameToId = HashMap<String, Long>()
        var created = 0
        for (cat in parsed.categories) {
            val existing = categoryDao.getByName(cat.name)
            if (existing != null) {
                nameToId[cat.name] = existing.id
                continue
            }
            val color = ColorAssigner.colorFor(cat.name, takenColors).also { takenColors.add(it) }
            val id = categoryDao.insert(
                CategoryEntity(
                    name = cat.name.trim(),
                    iconKey = IconAssigner.keyFor(cat.name),
                    colorHex = color,
                    isCustom = true,
                    excludeFromSpend = cat.excludeFromSpend,
                    usage = cat.usage,
                    sortOrder = 2000,
                ),
            )
            nameToId[cat.name] = id
            created++
        }

        // 2) Transactions — dedupe against existing + within the batch on the COMPOSITE key. A genuine
        //    same-day repeat of an identical row is kept by giving the 2nd+ occurrence an ordinal, while
        //    the 1st keeps the legacy hash so re-importing earlier-imported data still dedupes (#14).
        val seen = expenseDao.allDedupeHashes().toHashSet()
        val occurrence = HashMap<String, Int>()
        var imported = 0
        var duplicates = 0
        var processed = 0
        val total = parsed.transactions.size

        parsed.transactions.chunked(250).forEach { chunk ->
            db.withTransaction {
                for (txn in chunk) {
                    processed++
                    val base = DedupeKey.forImport(txn.occurredAt, txn.amountMinor, txn.categoryName, txn.note, txn.kind)
                    val n = occurrence.getOrDefault(base, 0)
                    occurrence[base] = n + 1
                    val hash = if (n == 0) base
                        else DedupeKey.forImport(txn.occurredAt, txn.amountMinor, txn.categoryName, txn.note, txn.kind, n)
                    if (!seen.add(hash)) {
                        duplicates++
                        continue
                    }
                    val categoryId = nameToId[txn.categoryName] ?: continue
                    val expenseId = expenseDao.insertExpense(
                        ExpenseEntity(
                            amountMinor = txn.amountMinor,
                            occurredAt = txn.occurredAt,
                            merchantRaw = txn.note, // Monito's note/description reads best as the row title
                            note = null,
                            source = TxnSource.IMPORT,
                            kind = txn.kind,
                            direction = if (txn.kind == TxnKind.INCOME) Direction.CREDIT else Direction.DEBIT,
                            dedupeHash = hash,
                            parseConfidence = 100,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    expenseDao.insertAllocations(
                        listOf(AllocationEntity(expenseId = expenseId, categoryId = categoryId, amountMinor = txn.amountMinor)),
                    )
                    imported++
                }
            }
            onProgress(processed, total)
        }

        return ImportSummary(
            imported = imported,
            duplicatesSkipped = duplicates,
            categoriesCreated = created,
            issues = parsed.issues.size,
        )
    }
}
