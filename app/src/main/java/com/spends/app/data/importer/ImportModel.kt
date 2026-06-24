package com.spends.app.data.importer

import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind

/** A parsed spreadsheet sheet as a reader-agnostic grid. Cells are String / Number / null. */
data class SheetGrid(val name: String, val rows: List<List<Any?>>)

/** One transaction parsed from a source file (before its category is resolved to a DB id). */
data class ParsedTransaction(
    val occurredAt: Long,
    val kind: TxnKind,
    val categoryName: String,
    val note: String?,
    val amountMinor: Long,
    val sourceLabel: String,
)

/** A category discovered during import: name + inferred usage + excludeFromSpend flag. */
data class ImportedCategory(
    val name: String,
    val usage: CategoryUsage,
    val excludeFromSpend: Boolean,
)

/** A row that couldn't be parsed, surfaced in the import summary. */
data class ImportIssue(val sourceLabel: String, val reason: String)

/** The full result of parsing a file, ready for the preview/confirm step. */
data class ParsedImport(
    val transactions: List<ParsedTransaction>,
    val categories: List<ImportedCategory>,
    val issues: List<ImportIssue>,
    val isMonito: Boolean = false,
) {
    val count: Int get() = transactions.size
}

/** Outcome of committing an import to the database. */
data class ImportSummary(
    val imported: Int,
    val duplicatesSkipped: Int,
    val categoriesCreated: Int,
    val issues: Int,
)
