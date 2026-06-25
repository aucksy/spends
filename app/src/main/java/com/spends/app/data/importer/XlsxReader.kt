package com.spends.app.data.importer

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads a modern .xlsx (OOXML/SpreadsheetML) workbook into [SheetGrid]s — notably the file produced
 * by this app's own "Export to Excel" so it round-trips back through Import (#7). Dependency-free: an
 * .xlsx is a ZIP of XML parts, parsed here with the platform DOM parser (works on both Android and the
 * JVM unit tests). String cells (inline or shared-strings) become String; numeric cells become Double.
 *
 * Note: Excel/Sheets store dates as numeric serials with a number-format; those arrive here as numbers,
 * so a generic .xlsx with serial dates won't parse its date column. The app's own export writes dates
 * as text, so it round-trips cleanly; for arbitrary spreadsheets, .csv is the most reliable path.
 */
object XlsxReader : SpreadsheetReader {

    override fun read(input: InputStream): List<SheetGrid> {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) parts[entry.name] = zis.readBytes()
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val shared = parts["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val sheetParts = parts.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sortedBy { sheetIndex(it) }
        if (sheetParts.isEmpty()) return emptyList()
        return sheetParts.mapIndexed { idx, name ->
            SheetGrid("Sheet${idx + 1}", parseSheet(parts[name]!!, shared))
        }
    }

    private fun sheetIndex(path: String): Int =
        path.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE

    private fun docOf(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // The parts are local & trusted, but disabling DOCTYPE/external entities is cheap defence.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val doc = docOf(bytes)
        val siList = doc.getElementsByTagName("si")
        return (0 until siList.length).map { i ->
            val si = siList.item(i) as Element
            val tList = si.getElementsByTagName("t")
            buildString { for (j in 0 until tList.length) append(tList.item(j).textContent) }
        }
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<Any?>> {
        val doc = docOf(bytes)
        val rowNodes = doc.getElementsByTagName("row")
        val grid = ArrayList<List<Any?>>(rowNodes.length)
        for (i in 0 until rowNodes.length) {
            val rowEl = rowNodes.item(i) as Element
            val byCol = HashMap<Int, Any?>()
            var maxCol = -1
            for (c in childElements(rowEl, "c")) {
                val col = columnIndex(c.getAttribute("r"))
                byCol[col] = cellValue(c, shared)
                if (col > maxCol) maxCol = col
            }
            grid.add((0..maxCol).map { byCol[it] })
        }
        return grid
    }

    private fun cellValue(c: Element, shared: List<String>): Any? = when (c.getAttribute("t")) {
        "s" -> firstChildText(c, "v")?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }
        "inlineStr" -> {
            val isEl = childElements(c, "is").firstOrNull()
            isEl?.let {
                val tList = it.getElementsByTagName("t")
                buildString { for (j in 0 until tList.length) append(tList.item(j).textContent) }.ifEmpty { null }
            }
        }
        "str" -> firstChildText(c, "v")?.ifEmpty { null }
        "b" -> firstChildText(c, "v")?.let { if (it.trim() == "1") "TRUE" else "FALSE" }
        else -> {
            // numeric (t absent or "n") — Double so GenericAdapter treats it as a number.
            val v = firstChildText(c, "v")?.trim()
            if (v.isNullOrEmpty()) null else (v.toDoubleOrNull() ?: v)
        }
    }

    private fun childElements(parent: Element, tag: String): List<Element> {
        val out = ArrayList<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName == tag) out.add(n)
        }
        return out
    }

    private fun firstChildText(parent: Element, tag: String): String? =
        childElements(parent, tag).firstOrNull()?.textContent

    /** "B7" -> 1, "AA3" -> 26 (0-based). Ignores the trailing row digits. */
    private fun columnIndex(ref: String): Int {
        var col = 0
        var sawLetter = false
        for (ch in ref) {
            val up = ch.uppercaseChar()
            if (up in 'A'..'Z') {
                col = col * 26 + (up - 'A' + 1)
                sawLetter = true
            } else {
                break
            }
        }
        return if (sawLetter) col - 1 else 0
    }
}
