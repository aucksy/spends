package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import com.spends.app.data.db.entity.PendingCaptureEntity
import com.spends.app.domain.model.TxnKind
import org.junit.Test

/**
 * The two flags that decide whether a queued capture may be committed without a human looking at it.
 *
 * [PendingCaptureEntity.isUnconvertedForeign] gates the paths that write straight to the ledger with no
 * editor — quick-confirm and "Add all". If it ever returned false for a row whose amount is still in
 * ringgit, that number would land in a rupee ledger with nobody having seen it. That is the single worst
 * outcome multi-currency can produce, so the states are enumerated exhaustively here.
 */
class PendingCaptureFxTest {

    private fun row(
        amountMinor: Long = 25_000,
        fxCurrency: String? = null,
        fxAmountMinor: Long? = null,
        fxRateMicros: Long? = null,
    ) = PendingCaptureEntity(
        amountMinor = amountMinor,
        kind = TxnKind.EXPENSE,
        occurredAt = 1_754_000_000_000L,
        merchant = "TESCO",
        last4 = "4321",
        institution = "Maybank",
        categoryId = 1,
        parseConfidence = 90,
        dedupeHash = "hash",
        receivedAt = 1_754_000_000_000L,
        createdAt = 1_754_000_000_000L,
        fxCurrency = fxCurrency,
        fxAmountMinor = fxAmountMinor,
        fxRateMicros = fxRateMicros,
    )

    @Test fun an_ordinary_same_currency_row_is_neither_converted_nor_unconverted() {
        // The overwhelmingly common case: no currency token in the message at all. It must be committable.
        val r = row()
        assertThat(r.isUnconvertedForeign).isFalse()
        assertThat(r.isConverted).isFalse()
    }

    @Test fun a_fully_converted_row_is_converted_and_committable() {
        val r = row(amountMinor = 189_000, fxCurrency = "MYR", fxAmountMinor = 10_000, fxRateMicros = 18_900_000)
        assertThat(r.isConverted).isTrue()
        assertThat(r.isUnconvertedForeign).isFalse()
    }

    @Test fun a_foreign_row_with_no_rate_is_unconverted_and_must_not_be_committed() {
        // No key, no network, or a rate that failed the sanity check: the amount is still in ringgit.
        val r = row(fxCurrency = "MYR", fxAmountMinor = 25_000, fxRateMicros = null)
        assertThat(r.isUnconvertedForeign).isTrue()
        assertThat(r.isConverted).isFalse()
    }

    @Test fun the_rate_is_what_decides_it_not_the_original_amount() {
        // A row carrying a currency and a rate but no original amount is an incomplete receipt: it is not
        // "converted" (nothing to show the user), but it is also NOT blocked — the rate proves the stored
        // figure was already converted, so blocking it would strand a correct transaction forever.
        val r = row(fxCurrency = "MYR", fxAmountMinor = null, fxRateMicros = 18_900_000)
        assertThat(r.isUnconvertedForeign).isFalse()
        assertThat(r.isConverted).isFalse()
    }

    @Test fun a_rate_without_a_currency_never_blocks_a_row() {
        // Not a state the app writes, but a defensive read of a hand-edited or partially-restored database
        // must not turn an ordinary transaction into one that can never be added.
        assertThat(row(fxRateMicros = 18_900_000).isUnconvertedForeign).isFalse()
        assertThat(row(fxAmountMinor = 10_000).isUnconvertedForeign).isFalse()
    }
}
