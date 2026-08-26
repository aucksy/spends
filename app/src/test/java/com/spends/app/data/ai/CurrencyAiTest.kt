package com.spends.app.data.ai

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import com.spends.app.core.money.AppCurrency
import androidx.test.core.app.ApplicationProvider
import com.spends.app.data.backup.SecureKeyStore
import com.spends.app.data.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * How a capture's currency is resolved against the currency the books are kept in.
 *
 * Every case here is reachable with **no network and no API key**: a pinned manual rate, a currency that
 * needs no conversion, and the switched-off state. Between them they cover the whole decision tree that
 * decides whether an amount is used as-is, rewritten, or held back — which is the part that can put a
 * wrong number in a ledger.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CurrencyAiTest {

    private fun settingsRepository(): SettingsRepository {
        val file = File.createTempFile("currency-ai-test", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create { file }
        return SettingsRepository(store)
    }

    /**
     * A [CurrencyAi] backed by a real, EMPTY key store — so `hasKey()` is false and no HTTP call is ever
     * attempted. Every case below resolves before the network would be reached, which is what makes this
     * suite deterministic: it exercises the decision tree, not a provider.
     *
     * Robolectric is needed only for the `SharedPreferences` the key store reads; no hardware-keystore
     * path is touched, because a key is never written.
     */
    private fun currencyAi(settings: SettingsRepository): CurrencyAi {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return CurrencyAi(AiClient(SecureKeyStore(context)), settings)
    }

    private val now = 1_754_000_000_000L

    // ---- the bug this suite was written for ----

    @Test fun a_ringgit_alert_on_a_ringgit_ledger_needs_no_conversion() = runTest {
        // The ordinary domestic case for a Malaysian user. Before this was distinguished from "could not
        // convert", EVERY capture they made was flagged unconvertible and refused by "Add all" — the
        // feature actively broke the app for the exact user it was built for.
        val settings = settingsRepository()
        settings.setBaseCurrency(AppCurrency.MYR)
        val outcome = currencyAi(settings).convert("MYR", 25_000, now)
        assertThat(outcome).isEqualTo(ConversionOutcome.NotNeeded)
    }

    @Test fun a_rupee_alert_on_a_rupee_ledger_needs_no_conversion() = runTest {
        val settings = settingsRepository() // INR is the default
        assertThat(currencyAi(settings).convert("INR", 25_000, now)).isEqualTo(ConversionOutcome.NotNeeded)
    }

    @Test fun a_blank_currency_needs_no_conversion() = runTest {
        assertThat(currencyAi(settingsRepository()).convert("  ", 25_000, now))
            .isEqualTo(ConversionOutcome.NotNeeded)
    }

    // ---- foreign, with no way to convert ----

    @Test fun a_foreign_alert_with_the_feature_off_is_unavailable_not_silently_accepted() = runTest {
        // The amount must stay flagged as foreign. Reporting NotNeeded here would let 250 ringgit be
        // committed as 250 rupees with nobody warned.
        val outcome = currencyAi(settingsRepository()).convert("MYR", 25_000, now)
        assertThat(outcome).isEqualTo(ConversionOutcome.Unavailable("MYR"))
    }

    @Test fun a_foreign_alert_with_the_feature_on_but_no_key_is_unavailable() = runTest {
        val settings = settingsRepository()
        settings.setAiConversionEnabled(true)
        assertThat(currencyAi(settings).convert("MYR", 25_000, now))
            .isEqualTo(ConversionOutcome.Unavailable("MYR"))
    }

    // ---- a pinned rate: the whole path with no network at all ----

    @Test fun a_pinned_rate_converts_without_a_key_or_a_call() = runTest {
        val settings = settingsRepository()
        settings.setManualRate("MYR", "INR", 18_900_000)
        val outcome = currencyAi(settings).convert("MYR", 10_000, now)
        assertThat(outcome).isInstanceOf(ConversionOutcome.Converted::class.java)
        val conversion = (outcome as ConversionOutcome.Converted).conversion
        assertThat(conversion.baseMinor).isEqualTo(189_000) // RM 100.00 -> ₹1,890.00
        assertThat(conversion.foreignMinor).isEqualTo(10_000)
        assertThat(conversion.foreignCode).isEqualTo("MYR")
        assertThat(conversion.rateMicros).isEqualTo(18_900_000)
    }

    @Test fun a_pinned_rate_beats_the_ai_even_when_the_feature_is_off() = runTest {
        // The documented escape hatch has to work in the state most users are in: AI switched off.
        val settings = settingsRepository()
        settings.setAiConversionEnabled(false)
        settings.setManualRate("USD", "INR", 83_000_000)
        assertThat(currencyAi(settings).convert("USD", 5_000, now))
            .isInstanceOf(ConversionOutcome.Converted::class.java)
    }

    @Test fun a_pinned_rate_is_matched_case_insensitively() = runTest {
        val settings = settingsRepository()
        settings.setManualRate("myr", "inr", 18_900_000)
        assertThat(currencyAi(settings).convert("myr", 10_000, now))
            .isInstanceOf(ConversionOutcome.Converted::class.java)
    }

    @Test fun a_pinned_rate_for_a_different_pair_does_not_apply() = runTest {
        // A rate stored against USD must never be used to convert ringgit.
        val settings = settingsRepository()
        settings.setManualRate("USD", "INR", 83_000_000)
        assertThat(currencyAi(settings).convert("MYR", 10_000, now))
            .isEqualTo(ConversionOutcome.Unavailable("MYR"))
    }

    @Test fun changing_the_base_currency_retires_a_rate_pinned_against_the_old_one() = runTest {
        // "MYR:INR" must not be read as a MYR->USD rate after the books move to dollars.
        val settings = settingsRepository()
        settings.setManualRate("MYR", "INR", 18_900_000)
        settings.setBaseCurrency(AppCurrency.USD)
        assertThat(currencyAi(settings).convert("MYR", 10_000, now))
            .isEqualTo(ConversionOutcome.Unavailable("MYR"))
    }

    @Test fun the_receipt_describes_the_conversion_it_performed() = runTest {
        val settings = settingsRepository()
        settings.setManualRate("MYR", "INR", 18_900_000)
        val conversion = (currencyAi(settings).convert("MYR", 10_000, now) as ConversionOutcome.Converted).conversion
        val text = conversion.describe(AppCurrency.INR)
        assertThat(text).contains("RM100.00")
        assertThat(text).contains("₹1,890.00")
        assertThat(text).contains("1 MYR = ₹18.90")
    }

    // ---- the manual-rate lookup itself ----

    @Test fun an_out_of_range_pinned_rate_is_ignored_rather_than_applied() = runTest {
        // A corrupted or hand-edited preference must not scale an amount by nonsense.
        assertThat(CurrencyAi.manualRateFor(mapOf("MYR:INR" to 0L), "MYR", "INR")).isNull()
        assertThat(CurrencyAi.manualRateFor(mapOf("MYR:INR" to -5L), "MYR", "INR")).isNull()
        assertThat(CurrencyAi.manualRateFor(mapOf("MYR:INR" to 18_900_000L), "MYR", "INR"))
            .isEqualTo(18_900_000L)
    }
}
