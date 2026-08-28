package com.spends.app.ui.addedit

import com.google.common.truth.Truth.assertThat
import com.spends.app.core.money.AppCurrency
import com.spends.app.core.money.Money
import org.junit.After
import org.junit.Test

/**
 * The editor's guard on an alert that arrived in another currency and could NOT be converted.
 *
 * v1.70.0 refused such a row on three of the four paths that can put money in the ledger — the live
 * silent add, quick-confirm, and "Add all". The fourth, the full editor, did not. It opened with the
 * FOREIGN figure already in the amount box, Save enabled, and on save discarded every column recording
 * that the row had ever been ringgit. One tap on the most likely button on the screen filed RM250.00 as
 * ₹250.00, and the result was byte-identical to a genuine rupee entry — nothing left to find it by.
 *
 * That is the exact shape of the case this feature exists for: a card alert abroad, arriving when the
 * phone has no usable data, so the conversion is the thing most likely to fail.
 */
class UnconvertedForeignGuardTest {

    @After fun restoreCurrency() {
        Money.displayCurrency = AppCurrency.DEFAULT
    }

    // ---- the guard itself ----

    @Test fun an_untouched_unconverted_foreign_amount_cannot_be_saved() {
        // The state the editor opens in when a ringgit alert could not be converted.
        assertThat(isUntouchedForeignAmount(true, "250.00", "250.00")).isTrue()
    }

    @Test fun editing_the_amount_releases_the_guard() {
        // The user has looked at the figure and put in what it is worth. That is all the guard asks for.
        assertThat(isUntouchedForeignAmount(true, "250.00", "4725.00")).isFalse()
    }

    @Test fun clearing_the_box_releases_the_guard() {
        // A blank box is already refused by the amount check, so it must not ALSO be held by this one —
        // that combination is a Save button that never lights up whatever the user does.
        assertThat(isUntouchedForeignAmount(true, "250.00", "")).isFalse()
    }

    @Test fun the_user_is_never_locked_out_of_their_own_entry() {
        // The guard compares TEXT, so digits identical to the seeded ones still read as untouched. That is
        // the intended strictness — but it must never become a dead end. Anything that parses to the same
        // money while reading differently releases it, so a user who genuinely wants the foreign figure
        // recorded as-is has a way through in one keystroke.
        assertThat(isUntouchedForeignAmount(true, "250.00", "250.00")).isTrue()
        assertThat(isUntouchedForeignAmount(true, "250.00", "250.0")).isFalse()
        assertThat(isUntouchedForeignAmount(true, "250.00", "250")).isFalse()
        // …and all three of those are the same amount, so nothing is lost by taking any of them.
        assertThat(Money.parseToMinor("250.0")).isEqualTo(Money.parseToMinor("250.00"))
        assertThat(Money.parseToMinor("250")).isEqualTo(Money.parseToMinor("250.00"))
    }

    @Test fun an_ordinary_rupee_entry_is_never_held() {
        // The overwhelmingly common case: no foreign currency involved, nothing to guard against.
        assertThat(isUntouchedForeignAmount(false, "250.00", "250.00")).isFalse()
        assertThat(isUntouchedForeignAmount(false, "", "")).isFalse()
    }

    @Test fun a_successfully_converted_alert_is_never_held() {
        // A converted row carries a rate, so `unconvertedForeign` is false and Save works on first tap —
        // which is the whole point of the feature, and must not have been slowed down by this fix.
        assertThat(isUntouchedForeignAmount(false, "4725.00", "4725.00")).isFalse()
    }

    // ---- what the user is told while it holds ----

    @Test fun the_warning_names_the_actual_foreign_amount() {
        // A bare currency code ("this alert was in MYR") left the reader to guess whether the number in
        // front of them was the ringgit one or an already-converted rupee one. It is the ringgit one.
        val note = conversionNoteFor(
            fxCurrency = "MYR", fxAmountMinor = 25_000, fxRateMicros = null, amountMinor = 25_000,
        )
        assertThat(note).contains("RM250.00")
        assertThat(note).contains("not converted")
    }

    @Test fun the_warning_does_not_tell_a_saved_row_to_check_before_saving() {
        // The same line is shown on the review card, in the editor, AND on the transaction after it has
        // been saved. Wording it as an instruction about saving read as nonsense on the third of those.
        val note = conversionNoteFor(
            fxCurrency = "MYR", fxAmountMinor = 25_000, fxRateMicros = null, amountMinor = 25_000,
        )
        assertThat(note).doesNotContain("before saving")
    }

    @Test fun a_converted_row_still_shows_its_receipt_not_a_warning() {
        // RM250.00 at 18.90 → ₹4,725.00. The guard must not have replaced the receipt with the warning.
        val note = conversionNoteFor(
            fxCurrency = "MYR", fxAmountMinor = 25_000, fxRateMicros = 18_900_000, amountMinor = 472_500,
        )
        assertThat(note).contains("RM250.00")
        assertThat(note).contains("4,725.00")
        assertThat(note).doesNotContain("not converted")
    }

    @Test fun once_the_user_sets_the_amount_the_warning_becomes_a_note() {
        // Same three columns, opposite meaning. The row still records that it arrived as RM250.00, but the
        // figure stored is one the user chose — so warning them that "the amount is not converted — set it
        // yourself" would be telling them to do the thing they just did.
        val note = conversionNoteFor(
            fxCurrency = "MYR", fxAmountMinor = 25_000, fxRateMicros = null, amountMinor = 472_500,
        )
        assertThat(note).contains("RM250.00")
        assertThat(note).contains("You set the amount below yourself")
        assertThat(note).doesNotContain("set it yourself")
    }

    @Test fun an_ordinary_entry_has_no_note_at_all() {
        assertThat(conversionNoteFor(null, null, null, 25_000)).isNull()
    }
}
