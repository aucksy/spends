package com.spends.app.data.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * A converted transaction must still be able to explain itself after a restore.
 *
 * v1.70.0 bumped the snapshot to v6 for the `baseCurrency` SETTING but never added the three `fx*`
 * columns to [SnapshotExpense]. Amounts were safe — they always were — but every conversion receipt was
 * dropped on the way out, so after restoring onto a new phone a transaction captured as
 * "RM250.00 → ₹4,725.00 · 1 MYR = ₹18.90" came back as a bare ₹4,725.00 with nothing to say where the
 * figure came from. On a ledger built from travel alerts that is most of the useful detail.
 *
 * It also destroyed the record that an UNCONVERTED row had ever been foreign, which is the one fact
 * marking such a row as still needing a human.
 *
 * The DTO is exercised directly rather than through [BackupCodec], because the DTO is what changed and a
 * full [Snapshot] needs a seventeen-field settings block that has nothing to do with this.
 */
class SnapshotFxTest {

    // Same configuration BackupCodec uses, for the same reasons: unknown keys are tolerated so a file
    // from a newer app still reads, and defaults are written so an app-made backup is always explicit.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun expense(
        fxCurrency: String? = null,
        fxAmountMinor: Long? = null,
        fxRateMicros: Long? = null,
    ) = SnapshotExpense(
        id = 1,
        amountMinor = 472_500,
        occurredAt = 1_754_000_000_000L,
        merchantRaw = "TESCO",
        source = "SMS",
        kind = "EXPENSE",
        direction = "DEBIT",
        parseConfidence = 90,
        createdAt = 1_754_000_000_000L,
        updatedAt = 1_754_000_000_000L,
        fxCurrency = fxCurrency,
        fxAmountMinor = fxAmountMinor,
        fxRateMicros = fxRateMicros,
    )

    private fun roundTrip(e: SnapshotExpense): SnapshotExpense =
        json.decodeFromString(SnapshotExpense.serializer(), json.encodeToString(SnapshotExpense.serializer(), e))

    @Test fun a_converted_transaction_keeps_its_whole_receipt() {
        val restored = roundTrip(expense("MYR", 25_000, 18_900_000))

        assertThat(restored.amountMinor).isEqualTo(472_500)
        assertThat(restored.fxCurrency).isEqualTo("MYR")
        assertThat(restored.fxAmountMinor).isEqualTo(25_000)
        assertThat(restored.fxRateMicros).isEqualTo(18_900_000)
    }

    @Test fun an_unconverted_foreign_row_keeps_the_fact_that_it_was_foreign() {
        // No rate, but the origin survives — that is what marks the row as still needing a human.
        val restored = roundTrip(expense("MYR", 25_000, null))

        assertThat(restored.fxCurrency).isEqualTo("MYR")
        assertThat(restored.fxAmountMinor).isEqualTo(25_000)
        assertThat(restored.fxRateMicros).isNull()
    }

    @Test fun an_ordinary_rupee_transaction_carries_no_receipt() {
        val restored = roundTrip(expense())

        assertThat(restored.fxCurrency).isNull()
        assertThat(restored.fxAmountMinor).isNull()
        assertThat(restored.fxRateMicros).isNull()
    }

    @Test fun the_receipt_is_actually_written_into_the_file() {
        // Guards against the fields existing on the class but being left out of the mapper — which is the
        // exact shape of the original bug: the columns were on the ENTITY the whole time.
        val text = json.encodeToString(SnapshotExpense.serializer(), expense("MYR", 25_000, 18_900_000))

        assertThat(text).contains("fxCurrency")
        assertThat(text).contains("MYR")
        assertThat(text).contains("18900000")
    }

    @Test fun a_backup_written_before_these_fields_existed_still_decodes() {
        // The compatibility half. Every backup the owner already holds was written without these keys; if
        // they were required rather than defaulted, restoring any of them would throw instead of decoding.
        val olderJson = """
            {"id":1,"amountMinor":472500,"occurredAt":1754000000000,"merchantRaw":"TESCO",
             "source":"SMS","kind":"EXPENSE","direction":"DEBIT","parseConfidence":90,
             "createdAt":1754000000000,"updatedAt":1754000000000}
        """.trimIndent()

        val restored = json.decodeFromString(SnapshotExpense.serializer(), olderJson)

        assertThat(restored.amountMinor).isEqualTo(472_500)
        assertThat(restored.fxCurrency).isNull()
        assertThat(restored.fxAmountMinor).isNull()
        assertThat(restored.fxRateMicros).isNull()
    }
}
