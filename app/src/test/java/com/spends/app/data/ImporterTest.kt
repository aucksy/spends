package com.spends.app.data

import com.google.common.truth.Truth.assertThat
import com.spends.app.core.time.DateUtils
import com.spends.app.data.importer.CsvReader
import com.spends.app.data.importer.DedupeKey
import com.spends.app.data.importer.GenericAdapter
import com.spends.app.data.importer.SheetGrid
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import org.junit.Test
import java.time.LocalDate

class CsvReaderTest {
    @Test fun parses_simple_rows() {
        val rows = CsvReader.parse("a,b,c\n1,2,3")
        assertThat(rows).hasSize(2)
        assertThat(rows[0]).containsExactly("a", "b", "c").inOrder()
        assertThat(rows[1]).containsExactly("1", "2", "3").inOrder()
    }

    @Test fun handles_quotes_commas_and_escaped_quotes() {
        val rows = CsvReader.parse("\"a,b\",c\n\"x\"\"y\",z")
        assertThat(rows[0]).containsExactly("a,b", "c").inOrder()
        assertThat(rows[1]).containsExactly("x\"y", "z").inOrder()
    }

    @Test fun handles_embedded_newline_and_crlf() {
        val rows = CsvReader.parse("\"line1\nline2\",b\r\nc,d")
        assertThat(rows).hasSize(2)
        assertThat(rows[0]).containsExactly("line1\nline2", "b").inOrder()
        assertThat(rows[1]).containsExactly("c", "d").inOrder()
    }

    @Test fun empty_cells_become_null() {
        val rows = CsvReader.parse("a,,c")
        assertThat(rows[0]).containsExactly("a", null, "c").inOrder()
    }
}

class GenericAdapterTest {
    @Test fun detects_columns_and_parses() {
        val grid = SheetGrid(
            "CSV",
            listOf(
                listOf("Date", "Category", "Amount", "Type", "Note"),
                listOf("2020-01-05", "Food", "250", "Expense", "Lunch"),
                listOf("2020-01-06", "Salary", "50000", "Income", ""),
            ),
        )
        val r = GenericAdapter.parse(grid)
        assertThat(r.count).isEqualTo(2)
        val food = r.transactions.first { it.categoryName == "Food" }
        assertThat(food.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(food.amountMinor).isEqualTo(25_000)
        val salary = r.transactions.first { it.categoryName == "Salary" }
        assertThat(salary.kind).isEqualTo(TxnKind.INCOME)
        assertThat(r.categories.first { it.name == "Salary" }.usage).isEqualTo(CategoryUsage.INCOME)
    }

    @Test fun unusable_without_date_or_amount() {
        val grid = SheetGrid("CSV", listOf(listOf("Foo", "Bar"), listOf("1", "2")))
        assertThat(GenericAdapter.parse(grid).count).isEqualTo(0)
    }
}

class DedupeKeyTest {
    private val t = DateUtils.epochMillisFor(LocalDate.of(2020, 1, 5), 12, 0)

    @Test fun deterministic_and_distinct() {
        val a = DedupeKey.forImport(t, 25_000, "Food", "Lunch", TxnKind.EXPENSE)
        val b = DedupeKey.forImport(t, 25_000, "Food", "Lunch", TxnKind.EXPENSE)
        val c = DedupeKey.forImport(t, 25_001, "Food", "Lunch", TxnKind.EXPENSE)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
        assertThat(a).hasLength(64) // sha-256 hex
    }

    @Test fun case_and_whitespace_insensitive_on_text() {
        val a = DedupeKey.forImport(t, 25_000, "Food", "Lunch", TxnKind.EXPENSE)
        val b = DedupeKey.forImport(t, 25_000, " food ", " lunch ", TxnKind.EXPENSE)
        assertThat(a).isEqualTo(b)
    }
}
