package com.spends.app.data.importer

import com.spends.app.core.money.Money
import com.spends.app.core.time.DateUtils
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Parses a Monito Expense Manager export (see docs/MONITO_FORMAT.md) into transactions + categories.
 * Pure over a reader-agnostic [SheetGrid] list, so the byte-level .xls reading is a separate, thin
 * layer and this logic is fully unit-tested. Preserves every category exactly (PRD §4.13).
 */
object MonitoAdapter {

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val EXCLUDE_KEYWORDS = listOf("invest", "emi", "loan", "sip", "mutual fund")

    fun looksLikeMonito(sheets: List<SheetGrid>): Boolean {
        for (sheet in sheets) {
            for (row in sheet.rows.take(12)) {
                if (row.any { (it as? String)?.contains("Monito", ignoreCase = true) == true }) return true
                if (headerColumns(row) != null) return true
            }
        }
        return false
    }

    fun parse(sheets: List<SheetGrid>): ParsedImport {
        val transactions = mutableListOf<ParsedTransaction>()
        val issues = mutableListOf<ImportIssue>()
        // name -> set of kinds it's used with, to infer usage
        val kindsByCategory = mutableMapOf<String, MutableSet<TxnKind>>()

        for (sheet in sheets) {
            val headerIdx = sheet.rows.indexOfFirst { headerColumns(it) != null }
            if (headerIdx < 0) continue
            val cols = headerColumns(sheet.rows[headerIdx]) ?: continue

            for (i in (headerIdx + 1) until sheet.rows.size) {
                val row = sheet.rows[i]
                val label = "${sheet.name} r${i + 1}"
                val dateStr = cellString(row.getOrNull(cols.date))
                val typeStr = cellString(row.getOrNull(cols.type))
                val nameStr = cellString(row.getOrNull(cols.name))
                val amountCell = row.getOrNull(cols.amount)
                // Skip fully-blank rows silently.
                if (dateStr == null && typeStr == null && nameStr == null && amountCell == null) continue

                val occurredAt = parseDate(dateStr)
                if (occurredAt == null) {
                    issues.add(ImportIssue(label, "Unreadable date: ${dateStr ?: "(blank)"}"))
                    continue
                }
                val amountMinor = amountToMinor(amountCell)
                if (amountMinor == null || amountMinor <= 0) {
                    issues.add(ImportIssue(label, "Unreadable amount"))
                    continue
                }
                val kind = kindFor(typeStr)
                val category = nameStr?.takeIf { it.isNotBlank() } ?: "Other"
                kindsByCategory.getOrPut(category) { mutableSetOf() }.add(kind)
                transactions.add(
                    ParsedTransaction(
                        occurredAt = occurredAt,
                        kind = kind,
                        categoryName = category,
                        note = cellString(row.getOrNull(cols.note)),
                        amountMinor = amountMinor,
                        sourceLabel = label,
                    ),
                )
            }
        }

        val categories = kindsByCategory.entries.map { (name, kinds) ->
            ImportedCategory(name = name, usage = usageFor(kinds), excludeFromSpend = isNonConsumption(name))
        }.sortedBy { it.name.lowercase() }

        return ParsedImport(transactions, categories, issues)
    }

    // ---- column resolution ----

    private data class Cols(val date: Int, val type: Int, val name: Int, val note: Int, val amount: Int)

    /** Detects a Monito header row by its labels and returns the resolved column indices. */
    private fun headerColumns(row: List<Any?>): Cols? {
        val lower = row.map { (it as? String)?.trim()?.lowercase() }
        val date = lower.indexOf("date")
        val type = lower.indexOf("category type")
        val name = lower.indexOf("category name")
        if (date < 0 || type < 0 || name < 0) return null
        val note = lower.indexOf("note")
        val amount = lower.indexOf("amount")
        if (amount < 0) return null
        return Cols(date = date, type = type, name = name, note = note.coerceAtLeast(0), amount = amount)
    }

    // ---- cell coercion ----

    private fun cellString(cell: Any?): String? = when (cell) {
        null -> null
        is String -> cell.trim().ifEmpty { null }
        is Number -> trimNumber(cell)
        else -> cell.toString().trim().ifEmpty { null }
    }

    private fun trimNumber(n: Number): String {
        val d = n.toDouble()
        return if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }

    private fun amountToMinor(cell: Any?): Long? = when (cell) {
        is Number -> Math.round(cell.toDouble() * 100.0)
        is String -> Money.parseRupeesToMinor(cell)
        else -> null
    }

    private fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            DateUtils.epochMillisFor(LocalDate.parse(value.trim(), DATE_FMT), hour = 12, minute = 0)
        } catch (e: Exception) {
            null
        }
    }

    private fun kindFor(type: String?): TxnKind {
        val t = type?.lowercase().orEmpty()
        // The app no longer has a "transfer" kind; any imported "transfer" row is treated as an expense.
        return when {
            t.contains("income") -> TxnKind.INCOME
            else -> TxnKind.EXPENSE
        }
    }

    private fun usageFor(kinds: Set<TxnKind>): CategoryUsage = when {
        kinds == setOf(TxnKind.INCOME) -> CategoryUsage.INCOME
        kinds.contains(TxnKind.INCOME) -> CategoryUsage.BOTH
        else -> CategoryUsage.EXPENSE
    }

    private fun isNonConsumption(name: String): Boolean {
        val n = name.lowercase()
        return EXCLUDE_KEYWORDS.any { n.contains(it) }
    }
}
