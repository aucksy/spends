package com.spends.app.data.export

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.spends.app.core.money.AppCurrency
import com.spends.app.core.money.Money
import com.spends.app.data.db.SpendsDatabase
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The exported sheet's column headings must name the currency the book is in **now**.
 *
 * `ExcelExporter` is a `@Singleton`, and in v1.70.0 `header` was a stored `val` — so it captured whatever
 * currency happened to be set the first time Hilt built the exporter, and never moved again. The
 * split-details column reads the currency per export and did move. One file could therefore go out headed
 * "Income (INR)" with rows inside it printing "RM": a spreadsheet contradicting itself, which is worse
 * than either label alone, because nothing on the page says which one is lying.
 *
 * The database is empty and never queried here — the whole question is whether the header is derived when
 * it is READ or when the object was BUILT.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExcelHeaderCurrencyTest {

    private val db: SpendsDatabase by lazy {
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            SpendsDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        Money.displayCurrency = AppCurrency.DEFAULT
        if (db.isOpen) db.close()
    }

    @Test fun the_header_follows_a_currency_change_after_construction() {
        // Built while the ledger is in rupees — the state every existing user's first export happens in.
        Money.displayCurrency = AppCurrency.INR
        val exporter = ExcelExporter(db)
        assertThat(exporter.header).contains("Income (INR)")

        // The user switches to ringgit. The SAME instance must now head its columns MYR.
        Money.displayCurrency = AppCurrency.MYR
        assertThat(exporter.header).contains("Income (MYR)")
        assertThat(exporter.header).contains("Expenses (MYR)")
        assertThat(exporter.header).contains("Balance (MYR)")
        assertThat(exporter.header).doesNotContain("Income (INR)")
    }

    @Test fun all_three_money_columns_agree_with_each_other() {
        Money.displayCurrency = AppCurrency.USD
        val header = ExcelExporter(db).header

        val labelled = header.filter { it.contains("(") }
        assertThat(labelled).hasSize(3)
        assertThat(labelled.all { it.contains("(USD)") }).isTrue()
    }

    @Test fun the_non_money_columns_are_untouched_by_currency() {
        Money.displayCurrency = AppCurrency.MYR
        val header = ExcelExporter(db).header

        assertThat(header).containsAtLeast("Date", "Time", "Category", "Merchant / Payee", "Note")
        assertThat(header).hasSize(11)
    }
}
