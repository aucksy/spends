package com.spends.app.data.importer

import jxl.CellType
import jxl.NumberCell
import jxl.Workbook
import jxl.WorkbookSettings
import java.io.InputStream
import java.util.Locale

/**
 * Reads a legacy BIFF8 .xls (Excel 97-2003) via jxl. Number cells become Double, everything else
 * the cell's text (or null when empty). One [SheetGrid] per worksheet — Monito uses one sheet per
 * month. Read-only: the AWT-only image/write paths in jxl are never touched.
 */
object XlsReader : SpreadsheetReader {
    override fun read(input: InputStream): List<SheetGrid> {
        val settings = WorkbookSettings().apply {
            // Avoid jxl trying to honour an exotic locale; the export is plain text + numbers.
            setLocale(Locale.ENGLISH)
            setSuppressWarnings(true)
        }
        val workbook = Workbook.getWorkbook(input, settings)
        try {
            return workbook.sheets.map { sheet ->
                val rowCount = sheet.rows
                val colCount = sheet.columns
                val rows = (0 until rowCount).map { r ->
                    (0 until colCount).map { c ->
                        val cell = sheet.getCell(c, r)
                        when (cell.type) {
                            CellType.NUMBER -> (cell as NumberCell).value
                            CellType.EMPTY -> null
                            else -> cell.contents.ifEmpty { null }
                        }
                    }
                }
                SheetGrid(sheet.name, rows)
            }
        } finally {
            workbook.close()
        }
    }
}
