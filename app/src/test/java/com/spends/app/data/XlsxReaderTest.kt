package com.spends.app.data

import com.google.common.truth.Truth.assertThat
import com.spends.app.core.export.XlsxWriter
import com.spends.app.core.export.XlsxWriter.Cell
import com.spends.app.data.importer.GenericAdapter
import com.spends.app.data.importer.XlsxReader
import com.spends.app.domain.model.TxnKind
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Validates that the .xlsx Spends exports can be read straight back in (#7) — the exact round-trip a
 * user relies on to re-import their data. Builds a workbook with [XlsxWriter] and parses it with
 * [XlsxReader], then through [GenericAdapter] to confirm the transactions come out correctly.
 */
class XlsxReaderTest {

    private fun sampleWorkbook(): ByteArray = XlsxWriter.build(
        sheetName = "Spends",
        header = listOf("Date", "Time", "Type", "Category", "Amount (₹)", "Merchant / Payee", "Note"),
        rows = listOf(
            listOf(
                Cell.Str("2020-01-05"), Cell.Str("12:00"), Cell.Str("Expense"),
                Cell.Str("Food"), Cell.Num("250.00"), Cell.Str("Cafe"), Cell.Str("Lunch"),
            ),
            listOf(
                Cell.Str("2020-01-06"), Cell.Str("09:30"), Cell.Str("Income"),
                Cell.Str("Salary"), Cell.Num("50000.00"), Cell.Str("ACME"), Cell.Str(""),
            ),
        ),
    )

    @Test fun reads_back_strings_and_numbers() {
        val sheets = XlsxReader.read(ByteArrayInputStream(sampleWorkbook()))
        assertThat(sheets).hasSize(1)
        val rows = sheets[0].rows
        assertThat(rows).hasSize(3) // header + 2 data rows
        assertThat(rows[0][0]).isEqualTo("Date")
        assertThat(rows[0][4]).isEqualTo("Amount (₹)")
        assertThat(rows[1][3]).isEqualTo("Food")
        assertThat((rows[1][4] as Number).toDouble()).isEqualTo(250.0)
        // An empty cell comes back as null, not "".
        assertThat(rows[2][6]).isNull()
    }

    @Test fun round_trips_through_generic_adapter() {
        val sheets = XlsxReader.read(ByteArrayInputStream(sampleWorkbook()))
        val parsed = GenericAdapter.parse(sheets[0])
        assertThat(parsed.count).isEqualTo(2)
        val food = parsed.transactions.first { it.categoryName == "Food" }
        assertThat(food.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(food.amountMinor).isEqualTo(25_000)
        val salary = parsed.transactions.first { it.categoryName == "Salary" }
        assertThat(salary.kind).isEqualTo(TxnKind.INCOME)
        assertThat(salary.amountMinor).isEqualTo(5_000_000)
    }
}
