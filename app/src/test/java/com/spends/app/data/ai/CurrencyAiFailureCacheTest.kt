package com.spends.app.data.ai

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.spends.app.data.backup.SecureKeyStore
import com.spends.app.data.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A FAILED rate lookup must be remembered, not retried for every message.
 *
 * v1.70.0 cached only successes. The failure path returned before reaching the cache write, so an
 * unreachable provider — no signal, roaming data, a 5xx, a rejected key — cost a fresh HTTP attempt per
 * foreign message, each up to the client's 20-second call timeout. `SmsCaptureRepository.scanHistory`
 * runs that call for every parsed message inside the capture mutex with the SMS cursor open, so a few
 * hundred ringgit alerts on a bad connection turned "Scan past SMS" into an hours-long freeze. The code
 * comment there claimed a scan "costs one network call per distinct foreign currency"; that was true only
 * while the calls succeeded.
 *
 * These tests count calls against a stand-in client, which is the only way to see the difference — the
 * outcome (`Unavailable`) is identical either way, which is exactly why the bug survived review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CurrencyAiFailureCacheTest {

    private fun settingsRepository(): SettingsRepository {
        val file = File.createTempFile("currency-ai-failcache", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create { file }
        return SettingsRepository(store)
    }

    /** Answers every request the way an unreachable provider does, and counts how often it is asked. */
    private class CountingFailingClient(
        context: Application,
    ) : AiClient(SecureKeyStore(context)) {
        var calls = 0
            private set

        override fun hasKey(): Boolean = true

        override suspend fun chat(
            provider: AiProvider,
            model: String,
            system: String,
            user: String,
            maxTokens: Int,
        ): AiResult {
            calls++
            return AiResult.Failed("HTTP 503")
        }
    }

    /** Answers with a usable quote, and counts how often it is asked. */
    private class CountingOkClient(
        context: Application,
    ) : AiClient(SecureKeyStore(context)) {
        var calls = 0
            private set

        override fun hasKey(): Boolean = true

        override suspend fun chat(
            provider: AiProvider,
            model: String,
            system: String,
            user: String,
            maxTokens: Int,
        ): AiResult {
            calls++
            return AiResult.Ok("""{"from":"MYR","to":"INR","rate":18.90}""")
        }
    }

    private suspend fun enabledSettings(): SettingsRepository =
        settingsRepository().also { it.setAiConversionEnabled(true) }

    private val now = 1_754_000_000_000L

    @Test fun a_failed_lookup_is_asked_for_once_not_once_per_message() = runTest {
        val client = CountingFailingClient(ApplicationProvider.getApplicationContext())
        val ai = CurrencyAi(client, enabledSettings())

        // Fifty ringgit alerts in one scan, with the provider down throughout.
        repeat(50) { assertThat(ai.convert("MYR", 25_000, now)).isEqualTo(ConversionOutcome.Unavailable("MYR")) }

        // Before the fix this was 50 — fifty network attempts, fifty timeouts, one frozen scan.
        assertThat(client.calls).isEqualTo(1)
    }

    @Test fun the_remembered_failure_expires_so_conversion_returns_when_signal_does() = runTest {
        val client = CountingFailingClient(ApplicationProvider.getApplicationContext())
        val ai = CurrencyAi(client, enabledSettings())

        ai.convert("MYR", 25_000, now)
        // Still inside the short failure window: no second call.
        ai.convert("MYR", 25_000, now + CurrencyAi.FAILURE_TTL_MILLIS - 1)
        assertThat(client.calls).isEqualTo(1)

        // Past it: the provider is asked again, so a brief signal drop does not disable conversion for
        // the rest of the day. This is the half that matters to someone moving between roaming and wifi.
        ai.convert("MYR", 25_000, now + CurrencyAi.FAILURE_TTL_MILLIS + 1)
        assertThat(client.calls).isEqualTo(2)
    }

    @Test fun a_failure_is_forgotten_much_sooner_than_a_rate_is() = runTest {
        // A rate is good for hours; "the network was down" is good for minutes. If the two shared a TTL,
        // one bad moment would switch conversion off for the whole six hours.
        assertThat(CurrencyAi.FAILURE_TTL_MILLIS).isLessThan(CurrencyAi.CACHE_TTL_MILLIS)
    }

    @Test fun a_successful_rate_is_still_cached_and_still_converts() = runTest {
        // The guard on the fix: caching failures must not have broken the ordinary success path.
        val client = CountingOkClient(ApplicationProvider.getApplicationContext())
        val ai = CurrencyAi(client, enabledSettings())

        val first = ai.convert("MYR", 25_000, now)
        val second = ai.convert("MYR", 25_000, now + 1_000)

        assertThat(client.calls).isEqualTo(1)
        assertThat(first).isInstanceOf(ConversionOutcome.Converted::class.java)
        assertThat(second).isEqualTo(first)
        // RM250.00 at 18.90 is ₹4,725.00 — the figure the feature's own receipt line quotes.
        assertThat((first as ConversionOutcome.Converted).conversion.baseMinor).isEqualTo(472_500L)
    }

    @Test fun a_failure_for_one_currency_does_not_block_another() = runTest {
        // The cache is keyed per pair. A dead MYR lookup must not make USD look unavailable too — the
        // traveller in the brief spends in both on the same trip.
        val client = CountingFailingClient(ApplicationProvider.getApplicationContext())
        val ai = CurrencyAi(client, enabledSettings())

        ai.convert("MYR", 25_000, now)
        ai.convert("USD", 25_000, now)

        assertThat(client.calls).isEqualTo(2)
    }
}
