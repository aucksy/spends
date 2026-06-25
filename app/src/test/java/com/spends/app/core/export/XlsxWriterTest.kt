package com.spends.app.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class XlsxWriterTest {

    @Test fun producesValidZipWithExpectedParts() {
        val bytes = XlsxWriter.build(
            sheetName = "Spends",
            header = listOf("Date", "Amount"),
            rows = listOf(
                listOf(XlsxWriter.Cell.Str("2026-06-25"), XlsxWriter.Cell.Num("1550.00")),
            ),
        )
        val names = mutableListOf<String>()
        var sheet = ""
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names.add(entry.name)
                val content = zip.readBytes().toString(Charsets.UTF_8)
                if (entry.name == "xl/worksheets/sheet1.xml") sheet = content
                entry = zip.nextEntry
            }
        }
        assertTrue("[Content_Types].xml" in names)
        assertTrue("xl/workbook.xml" in names)
        assertTrue("xl/worksheets/sheet1.xml" in names)
        assertTrue("xl/styles.xml" in names)
        assertTrue(sheet.contains("<v>1550.00</v>"))
        assertTrue(sheet.contains("Date"))
    }

    @Test fun escapesXmlSpecials() {
        val bytes = XlsxWriter.build("S", listOf("H"), listOf(listOf(XlsxWriter.Cell.Str("Tom & <Jerry>"))))
        val sheet = readSheet(bytes)
        assertTrue(sheet.contains("Tom &amp; &lt;Jerry&gt;"))
    }

    @Test fun columnLettersBeyondZ() {
        // 28 columns -> last header should land in column AB.
        val header = (1..28).map { "C$it" }
        val bytes = XlsxWriter.build("S", header, emptyList())
        val sheet = readSheet(bytes)
        assertTrue(sheet.contains("r=\"AB1\""))
        assertEquals(true, sheet.contains("r=\"Z1\""))
    }

    private fun readSheet(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "xl/worksheets/sheet1.xml") return zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return ""
    }
}
