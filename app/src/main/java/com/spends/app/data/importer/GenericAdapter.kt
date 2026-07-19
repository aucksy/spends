package com.spends.app.data.importer

import com.spends.app.core.money.Money
import com.spends.app.core.time.DateUtils
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Best-effort adapter for an arbitrary single-sheet CSV/spreadsheet: auto-detects the Date / Amount
 * / Category / Type / Note columns from header keywords (PRD §4.13). The detected [Mapping] is shown
 * for the user to confirm before commit. A full manual column-remap UI is a follow-up.
 */
object GenericAdapter {

    data class Mapping(
        val headerRow: Int,
        val date: Int,
        val amount: Int,
        val category: Int,
        val type: Int,
        val note: Int,
        val headers: List<String>,
    ) {
        val usable: Boolean get() = date >= 0 && amount >= 0
    }

    private val DATE_FORMATS = listOf(
        "d MMM yyyy", "d MMMM yyyy", "yyyy-MM-dd", "dd/MM/yyyy", "d/M/yyyy",
        "dd-MM-yyyy", "d-M-yyyy", "MM/dd/yyyy", "dd-MMM-yyyy", "dd.MM.yyyy",
    ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    fun detect(sheet: SheetGrid): Mapping {
        val headerRow = sheet.rows.indexOfFirst { row ->
            row.count { it is String && it.isNotBlank() } >= 2
        }.coerceAtLeast(0)
        val headers = (sheet.rows.getOrNull(headerRow) ?: emptyList()).map { (it as? String)?.trim().orEmpty() }
        val lower = headers.map { it.lowercase() }
        fun find(vararg keys: String, avoid: String? = null) = lower.indexOfFirst { h ->
            keys.any { h.contains(it) } && (avoid == null || !h.contains(avoid))
        }
        return Mapping(
            headerRow = headerRow,
            date = find("date", "when"),
            amount = find("amount", "amt", "value"),
            category = find("category", "tag", avoid = "type").let { if (it >= 0) it else find("category") },
            type = find("type", "kind", "direction"),
            note = find("note", "description", "payee", "merchant", "remark", "detail"),
            headers = headers,
        )
    }

    fun parse(sheet: SheetGrid, mapping: Mapping = detect(sheet)): ParsedImport {
        if (!mapping.usable) {
            return ParsedImport(emptyList(), emptyList(), listOf(ImportIssue(sheet.name, "Could not find Date and Amount columns")))
        }
        val transactions = mutableListOf<ParsedTransaction>()
        val issues = mutableListOf<ImportIssue>()
        val kindsByCategory = mutableMapOf<String, MutableSet<TxnKind>>()

        for (i in (mapping.headerRow + 1) until sheet.rows.size) {
            val row = sheet.rows[i]
            val label = "${sheet.name} r${i + 1}"
            if (row.all { it == null || (it is String && it.isBlank()) }) continue
            val occurredAt = parseDate(str(row.getOrNull(mapping.date)))
            if (occurredAt == null) {
                issues.add(ImportIssue(label, "Unreadable date")); continue
            }
            val amount = amountToMinor(row.getOrNull(mapping.amount))
            if (amount == null || amount == 0L) {
                issues.add(ImportIssue(label, "Unreadable amount")); continue
            }
            val kind = kindFor(str(row.getOrNull(mapping.type)), amount)
            val category = str(row.getOrNull(mapping.category))?.takeIf { it.isNotBlank() } ?: "Uncategorized"
            kindsByCategory.getOrPut(category) { mutableSetOf() }.add(kind)
            transactions.add(
                ParsedTransaction(
                    occurredAt = occurredAt,
                    kind = kind,
                    categoryName = category,
                    note = if (mapping.note >= 0) str(row.getOrNull(mapping.note)) else null,
                    amountMinor = kotlin.math.abs(amount),
                    sourceLabel = label,
                ),
            )
        }
        val categories = kindsByCategory.entries.map { (name, kinds) ->
            ImportedCategory(
                name = name,
                usage = if (kinds == setOf(TxnKind.INCOME)) CategoryUsage.INCOME
                else if (kinds.contains(TxnKind.INCOME)) CategoryUsage.BOTH else CategoryUsage.EXPENSE,
                excludeFromSpend = listOf("invest", "emi", "loan", "sip").any { name.lowercase().contains(it) },
            )
        }.sortedBy { it.name.lowercase() }
        return ParsedImport(transactions, categories, issues)
    }

    private fun str(cell: Any?): String? = when (cell) {
        null -> null
        is String -> cell.trim().ifEmpty { null }
        is Number -> { val d = cell.toDouble(); if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString() }
        else -> cell.toString().trim().ifEmpty { null }
    }

    private fun amountToMinor(cell: Any?): Long? = when (cell) {
        is Number -> Math.round(cell.toDouble() * 100.0)
        is String -> Money.parseRupeesToMinor(cell)
        else -> null
    }

    private fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        for (fmt in DATE_FORMATS) {
            try {
                return DateUtils.epochMillisFor(LocalDate.parse(value.trim(), fmt), 12, 0)
            } catch (e: Exception) {
                // try next format
            }
        }
        return null
    }

    private fun kindFor(type: String?, amount: Long): TxnKind {
        val t = type?.lowercase().orEmpty()
        // The app no longer has a "transfer" kind; any imported "transfer" row is treated as an expense.
        return when {
            t.contains("income") || t.contains("credit") -> TxnKind.INCOME
            else -> TxnKind.EXPENSE
        }
    }
}
