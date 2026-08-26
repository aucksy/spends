package com.spends.app.ui.settings

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.spends.app.core.money.AppCurrency
import com.spends.app.data.ai.AiClient
import com.spends.app.data.ai.CurrencyAi
import com.spends.app.data.backup.SecureKeyStore
import com.spends.app.data.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Opens the Currency & AI settings screen for real on the JVM.
 *
 * **Why this file exists.** v1.63.0 shipped green — APK built, codegen ran, every logic assertion
 * passed — and a screen still closed the app the moment it was opened, because nothing in CI had ever
 * *opened* one. This screen is the same shape of risk: it is built from several branches (AI section
 * hidden vs shown, key saved vs not, a manual rate pinned vs not) that only exist once something
 * composes them.
 *
 * `@Config(application = Application::class)` bypasses the real `@HiltAndroidApp` application; the
 * dependencies are built by hand so the screen runs without standing up the DI graph. No network call
 * is made or needed: rendering never calls the AI client.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CurrencySettingsScreenRenderTest {

    private fun settingsRepository(): SettingsRepository {
        val file = File.createTempFile("currency-render-test", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create { file }
        return SettingsRepository(store)
    }

    private fun viewModelWith(settings: SettingsRepository): CurrencyAiViewModel {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val client = AiClient(SecureKeyStore(context))
        return CurrencyAiViewModel(settings, client, CurrencyAi(client, settings))
    }

    /** Isolates a crash in the ViewModel's construction from a crash in composition. */
    @Test
    fun the_view_model_can_be_constructed() {
        viewModelWith(settingsRepository())
    }

    /** The default state every existing user lands in: rupees, AI off, no key. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun the_screen_opens_with_the_ai_section_switched_off() = runComposeUiTest {
        setContent { CurrencySettingsScreen(onBack = {}, viewModel = viewModelWith(settingsRepository())) }
        waitForIdle()
        onNodeWithText("Currency").assertExists()
        onNodeWithText("Convert with AI").assertExists()
    }

    /**
     * The AI section expanded. Switching it on reveals four more rows and a block of explanatory copy —
     * none of which is composed in the default state above, so without this the whole enabled branch
     * would ship untested.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun the_screen_opens_with_the_ai_section_switched_on() = runComposeUiTest {
        val settings = settingsRepository()
        runBlocking { settings.setAiConversionEnabled(true) }
        setContent { CurrencySettingsScreen(onBack = {}, viewModel = viewModelWith(settings)) }
        waitForIdle()
        onNodeWithText("Provider").assertExists()
        onNodeWithText("API key").assertExists()
        onNodeWithText("Model").assertExists()
    }

    /**
     * A non-rupee ledger with a pinned rate. This composes the paths the default state cannot reach: the
     * "Your own rates" rows exclude the BASE currency, so which rows exist depends on that setting — and
     * a pinned rate renders a formatted figure rather than the "Ask the AI" placeholder.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun the_screen_opens_on_a_ringgit_ledger_with_a_pinned_rate() = runComposeUiTest {
        val settings = settingsRepository()
        runBlocking {
            settings.setBaseCurrency(AppCurrency.MYR)
            settings.setAiConversionEnabled(true)
            settings.setManualRate("INR", "MYR", 55_000)
        }
        setContent { CurrencySettingsScreen(onBack = {}, viewModel = viewModelWith(settings)) }
        waitForIdle()
        // The base currency is never offered as a rate against itself; the others are.
        onNodeWithText("1 INR =").assertExists()
        onNodeWithText("1 USD =").assertExists()
    }
}
