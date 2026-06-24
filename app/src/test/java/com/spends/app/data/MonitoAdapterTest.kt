package com.spends.app.data

import com.google.common.truth.Truth.assertThat
import com.spends.app.core.time.DateUtils
import com.spends.app.data.importer.MonitoAdapter
import com.spends.app.data.importer.SheetGrid
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import org.junit.Test
import java.time.LocalDate

class MonitoAdapterTest {

    // Synthetic, masked grid mirroring the real Monito layout — no real personal data.
    private fun grid() = SheetGrid(
        name = "January 2020",
        rows = listOf(
            listOf(null, null, null, null, null, null, null),
            listOf(null, null, "Monito Expense Manager", null, null, null, null),
            listOf(null, null, "Version 8.3", null, null, null, null),
            listOf(null, null, "Created on 01 Jan 2020", null, null, null, null),
            listOf(null, null, null, null, null, null, null),
            listOf(null, null, null, null, "January 2020", null, null),
            listOf(null, null, null, null, null, null, null),
            listOf(null, null, "Date", "Category type", "Category name", "Note", "Amount"),
            listOf(null, null, "5 Jan 2020", "Income", "Salary", "", 50000.0),
            listOf(null, null, "6 Jan 2020", "Expense", "Food", "Lunch", 250.0),
            listOf(null, null, "7 Jan 2020", "Expense", "Investment", "SIP", 10000.0),
            listOf(null, null, "8 Jan 2020", "Expense", "Food", "Tea", 199.99),
            listOf(null, null, "bad date", "Expense", "Food", "x", 100.0),
            listOf(null, null, null, null, null, null, null),
        ),
    )

    @Test fun detects_monito() {
        assertThat(MonitoAdapter.looksLikeMonito(listOf(grid()))).isTrue()
        val notMonito = SheetGrid("x", listOf(listOf("a", "b", "c")))
        assertThat(MonitoAdapter.looksLikeMonito(listOf(notMonito))).isFalse()
    }

    @Test fun parses_rows_with_correct_kind_and_paise() {
        val r = MonitoAdapter.parse(listOf(grid()))
        assertThat(r.count).isEqualTo(4) // Salary, Food x2, Investment; bad-date is an issue
        assertThat(r.issues).hasSize(1)

        val salary = r.transactions.first { it.categoryName == "Salary" }
        assertThat(salary.kind).isEqualTo(TxnKind.INCOME)
        assertThat(salary.amountMinor).isEqualTo(5_000_000)
        assertThat(salary.occurredAt)
            .isEqualTo(DateUtils.epochMillisFor(LocalDate.of(2020, 1, 5), 12, 0))

        val tea = r.transactions.first { it.note == "Tea" }
        assertThat(tea.amountMinor).isEqualTo(19_999) // 199.99 -> paise
    }

    @Test fun infers_category_usage_and_exclude_flags() {
        val cats = MonitoAdapter.parse(listOf(grid())).categories.associateBy { it.name }
        assertThat(cats["Salary"]!!.usage).isEqualTo(CategoryUsage.INCOME)
        assertThat(cats["Food"]!!.usage).isEqualTo(CategoryUsage.EXPENSE)
        assertThat(cats["Investment"]!!.usage).isEqualTo(CategoryUsage.EXPENSE)
        assertThat(cats["Investment"]!!.excludeFromSpend).isTrue()
        assertThat(cats["Food"]!!.excludeFromSpend).isFalse()
    }

    @Test fun string_amount_with_grouping_parses() {
        val g = SheetGrid(
            "Feb 2020",
            listOf(
                listOf("Date", "Category type", "Category name", "Note", "Amount"),
                listOf("3 Feb 2020", "Expense", "Bills", "Electric", "1,250.50"),
            ),
        )
        val r = MonitoAdapter.parse(listOf(g))
        assertThat(r.transactions.single().amountMinor).isEqualTo(125_050)
    }
}
