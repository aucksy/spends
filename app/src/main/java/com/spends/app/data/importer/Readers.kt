package com.spends.app.data.importer

import java.io.InputStream

/** Reads a spreadsheet file into reader-agnostic [SheetGrid]s. */
interface SpreadsheetReader {
    fun read(input: InputStream): List<SheetGrid>
}

object SpreadsheetReaders {
    /** Picks a reader by file name. Returns null for unsupported types. */
    fun forFileName(name: String): SpreadsheetReader? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".xlsx") -> XlsxReader
            lower.endsWith(".xls") -> XlsReader
            lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".txt") -> CsvReader
            else -> null
        }
    }

    fun isSupported(name: String): Boolean = forFileName(name) != null
}

/** Hand-rolled RFC-4180 CSV reader (zero-dep, unit-tested). Produces a single sheet of String cells. */
object CsvReader : SpreadsheetReader {
    override fun read(input: InputStream): List<SheetGrid> {
        val text = input.readBytes().toString(Charsets.UTF_8).removePrefix("﻿")
        return listOf(SheetGrid("CSV", parse(text)))
    }

    /** RFC-4180: handles quoted fields, escaped quotes (""), embedded commas/newlines, CR/CRLF/LF. */
    fun parse(text: String): List<List<Any?>> {
        val rows = mutableListOf<List<Any?>>()
        var row = mutableListOf<Any?>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        var sawAny = false
        fun endField() { row.add(field.toString()); field.setLength(0) }
        fun endRow() { endField(); rows.add(row); row = mutableListOf() }
        while (i < text.length) {
            val c = text[i]
            sawAny = true
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ }
                    else inQuotes = false
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> endField()
                    '\r' -> { /* swallow; handle with following \n or as line end */
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                        endRow()
                    }
                    '\n' -> endRow()
                    else -> field.append(c)
                }
            }
            i++
        }
        // flush trailing field/row if any content was seen
        if (sawAny && (field.isNotEmpty() || row.isNotEmpty())) endRow()
        return rows.map { cells -> cells.map { (it as String).ifEmpty { null } } }
    }
}
