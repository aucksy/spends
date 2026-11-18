package com.spends.app.data.capture

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.spends.app.core.time.DateUtils
import com.spends.app.data.ai.AiClient
import com.spends.app.data.ai.CurrencyAi
import com.spends.app.data.backup.SecureKeyStore
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.repo.AllocationInput
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.repo.TransactionInput
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The owner's case, 18 Nov 2026: Amazon and Blinkit sell a different thing every order, so last order's
 * note ("Phone case") is wrong on the next one. Unticking "Remember this note for Amazon" must stop the
 * note being stored AND pre-filled, keep the CATEGORY memory, stay off on later saves, and come back
 * only when the user ticks it again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RememberNoteTickTest {

    private lateinit var db: SpendsDatabase
    private lateinit var capture: SmsCaptureRepository
    private var shopping = -1L
    private var amazonRow = -1L

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, SpendsDatabase::class.java).allowMainThreadQueries().build()
        val file = File.createTempFile("remember-note-test", ".preferences_pb").also { it.delete() }
        val settings = SettingsRepository(PreferenceDataStoreFactory.create { file })
        val expenses = ExpenseRepository(db)
        val payments = PaymentMethodRepository(db.paymentMethodDao(), db.expenseDao(), db)
        capture = SmsCaptureRepository(context, db, expenses, payments, CurrencyAi(AiClient(SecureKeyStore(context)), settings))
        runBlocking {
            shopping = db.categoryDao().insert(
                CategoryEntity(name = "Shopping", iconKey = "shopping", colorHex = "#3B82F6", usage = CategoryUsage.EXPENSE),
            )
            amazonRow = expenses.create(
                TransactionInput(
                    amountMinor = 49_900,
                    kind = TxnKind.EXPENSE,
                    occurredAt = DateUtils.nowMillis(),
                    merchantRaw = "Amazon",
                    note = null,
                    allocations = listOf(AllocationInput(shopping, 49_900)),
                    source = TxnSource.SMS,
                ),
            )
        }
    }

    @After fun tearDown() {
        if (this::db.isInitialized && db.isOpen) db.close()
    }

    @Test fun a_new_merchant_starts_ticked_and_remembers_its_note() = runBlocking {
        assertThat(capture.noteRememberedFor("Amazon")).isTrue()
        capture.learnFromTransaction(amazonRow, shopping, "Phone case", noteShown = true)
        assertThat(capture.learnedNoteFor("Amazon", allowFuzzy = true)).isEqualTo("Phone case")
        assertThat(capture.noteRememberedFor("Amazon")).isTrue()
    }

    @Test fun unticking_forgets_the_note_but_keeps_the_category() = runBlocking {
        capture.learnFromTransaction(amazonRow, shopping, "Phone case", noteShown = true)

        // Untick only — nothing else changed on the screen.
        capture.learnFromTransaction(amazonRow, shopping, null, categoryDeliberate = false, rememberNote = false)

        assertThat(capture.learnedNoteFor("Amazon")).isNull()
        assertThat(capture.learnedNoteFor("Amazon", allowFuzzy = true)).isNull()
        assertThat(capture.noteRememberedFor("Amazon")).isFalse()
        assertThat(capture.hasLearnedCategory("Amazon")).isTrue()
    }

    @Test fun it_stays_off_on_later_saves_until_ticked_again() = runBlocking {
        capture.learnFromTransaction(amazonRow, shopping, "Phone case", noteShown = true)
        capture.learnFromTransaction(amazonRow, shopping, null, categoryDeliberate = false, rememberNote = false)

        // A later save that types a note but leaves the tick alone (rememberNote = null) must NOT store it.
        capture.learnFromTransaction(amazonRow, shopping, "Shoes", noteShown = true)
        assertThat(capture.learnedNoteFor("Amazon", allowFuzzy = true)).isNull()
        assertThat(capture.noteRememberedFor("Amazon")).isFalse()

        // Ticking it again turns memory back on and stores this save's note.
        capture.learnFromTransaction(amazonRow, shopping, "Shoes", noteShown = true, rememberNote = true)
        assertThat(capture.learnedNoteFor("Amazon", allowFuzzy = true)).isEqualTo("Shoes")
        assertThat(capture.noteRememberedFor("Amazon")).isTrue()
    }

    @Test fun a_similar_shop_keeps_its_own_tick() = runBlocking {
        val prime = createSmsRow("Amazon Prime")
        capture.learnFromTransaction(prime, shopping, null, categoryDeliberate = true)
        capture.learnFromTransaction(amazonRow, shopping, null, categoryDeliberate = false, rememberNote = false)

        // "Amazon Prime" fuzzy-matches "Amazon", but the untick was only for Amazon.
        assertThat(capture.noteRememberedFor("Amazon Prime")).isTrue()
        // A later Prime save must not hide Amazon's own untick either.
        capture.learnFromTransaction(prime, shopping, "Membership", noteShown = true)
        assertThat(capture.noteRememberedFor("Amazon")).isFalse()
        assertThat(capture.learnedNoteFor("Amazon")).isNull()
        assertThat(capture.learnedNoteFor("Amazon Prime")).isEqualTo("Membership")
    }

    @Test fun a_save_that_leaves_the_tick_alone_keeps_the_shop_off() = runBlocking {
        capture.learnFromTransaction(amazonRow, shopping, null, categoryDeliberate = false, rememberNote = false)
        // Review screen saved with the tick untouched (sent as null) and a note typed for this order only.
        capture.learnFromTransaction(amazonRow, shopping, "Charger", noteShown = true, rememberNote = null)
        assertThat(capture.noteRememberedFor("Amazon")).isFalse()
        assertThat(capture.learnedNoteFor("Amazon", allowFuzzy = true)).isNull()
    }

    private fun createSmsRow(merchant: String): Long = runBlocking {
        ExpenseRepository(db).create(
            TransactionInput(
                amountMinor = 19_900,
                kind = TxnKind.EXPENSE,
                occurredAt = DateUtils.nowMillis(),
                merchantRaw = merchant,
                note = null,
                allocations = listOf(AllocationInput(shopping, 19_900)),
                source = TxnSource.SMS,
            ),
        )
    }

    @Test fun another_merchant_is_not_touched() = runBlocking {
        capture.learnFromTransaction(amazonRow, shopping, null, categoryDeliberate = false, rememberNote = false)
        assertThat(capture.noteRememberedFor("Swiggy")).isTrue()
    }
}
