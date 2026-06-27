package com.spends.app.core.export

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A minimal, dependency-free writer for a single-sheet .xlsx workbook (OOXML/SpreadsheetML).
 *
 * Apache POI would add ~10–15 MB to the APK; this hand-rolls the handful of XML parts a one-sheet
 * workbook needs. Strings are written inline (no shared-strings table); numbers go in as numeric
 * cells so Excel/Sheets can sum them. The header row is bold.
 */
object XlsxWriter {

    sealed interface Cell {
        data class Str(val value: String) : Cell
        /** [literal] must be a valid number token (e.g. from BigDecimal.toPlainString()). */
        data class Num(val literal: String) : Cell
        /** An Excel date serial (whole-day number) — formatted yyyy-mm-dd, so Excel/Sheets sort & filter it
         *  as a real date instead of text (#6). */
        data class DateNum(val literal: String) : Cell
        /** An Excel date-time serial (day + fraction-of-day) — formatted yyyy-mm-dd hh:mm. */
        data class DateTimeNum(val literal: String) : Cell
    }

    fun build(sheetName: String, header: List<String>, rows: List<List<Cell>>): ByteArray {
        val safeSheet = sheetName.replace(Regex("[\\[\\]:*?/\\\\]"), " ").take(31).ifBlank { "Sheet1" }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.put("[Content_Types].xml", CONTENT_TYPES)
            zip.put("_rels/.rels", ROOT_RELS)
            zip.put("xl/workbook.xml", workbookXml(safeSheet))
            zip.put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            zip.put("xl/styles.xml", STYLES)
            zip.put("xl/worksheets/sheet1.xml", sheetXml(header, rows))
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun sheetXml(header: List<String>, rows: List<List<Cell>>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        // Header row (bold style s="1").
        sb.append("<row r=\"1\">")
        header.forEachIndexed { c, text ->
            val ref = colLetter(c) + "1"
            sb.append("<c r=\"$ref\" t=\"inlineStr\" s=\"1\"><is><t xml:space=\"preserve\">").append(escape(text)).append("</t></is></c>")
        }
        sb.append("</row>")
        rows.forEachIndexed { i, cells ->
            val rowNum = i + 2
            sb.append("<row r=\"$rowNum\">")
            cells.forEachIndexed { c, cell ->
                val ref = colLetter(c) + rowNum
                when (cell) {
                    is Cell.Str -> sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(escape(cell.value)).append("</t></is></c>")
                    is Cell.Num -> sb.append("<c r=\"$ref\"><v>").append(cell.literal).append("</v></c>")
                    is Cell.DateNum -> sb.append("<c r=\"$ref\" s=\"2\"><v>").append(cell.literal).append("</v></c>")
                    is Cell.DateTimeNum -> sb.append("<c r=\"$ref\" s=\"3\"><v>").append(cell.literal).append("</v></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun colLetter(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + (n % 26)))
            n = n / 26 - 1
        }
        return sb.toString()
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                // Strip control chars that are illegal in XML 1.0 (keep tab/newline/cr).
                else -> if (ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') sb.append(' ') else sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun workbookXml(sheetName: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"${escape(sheetName)}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

    private const val CONTENT_TYPES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "</Types>"

    private const val ROOT_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

    private const val WORKBOOK_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "</Relationships>"

    // Style indices used by [sheetXml]: 0 = default, 1 = bold header, 2 = date (numFmt 164),
    // 3 = date-time (numFmt 165). numFmts MUST precede fonts per the OOXML styleSheet element order.
    private const val STYLES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<numFmts count=\"2\">" +
            "<numFmt numFmtId=\"164\" formatCode=\"yyyy-mm-dd\"/>" +
            "<numFmt numFmtId=\"165\" formatCode=\"yyyy-mm-dd hh:mm\"/>" +
            "</numFmts>" +
            "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
            "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border/></borders>" +
            "<cellStyleXfs count=\"1\"><xf/></cellStyleXfs>" +
            "<cellXfs count=\"4\"><xf/><xf fontId=\"1\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"164\" applyNumberFormat=\"1\"/>" +
            "<xf numFmtId=\"165\" applyNumberFormat=\"1\"/></cellXfs>" +
            "</styleSheet>"
}
