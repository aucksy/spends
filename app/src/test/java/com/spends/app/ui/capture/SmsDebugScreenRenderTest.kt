package com.spends.app.ui.capture

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.spends.app.data.capture.CaptureNotifier
import com.spends.app.data.capture.SmsDebugLog
import com.spends.app.data.settings.SettingsRepository
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Opens the SMS debug screen for real on the JVM.
 *
 * **Why this file exists.** v1.63.0 shipped with a fully green build — the release APK built, Hilt/KSP
 * codegen ran, and 195 logic assertions passed — and the screen still closed the app the instant it was
 * opened. Nothing in CI had ever *opened* a screen, so no check could have caught it. Compiling a
 * composable proves almost nothing about running one: everything below the function signature (theme
 * lookups, `remember` initialisers, permission reads, ViewModel `init`, the first composition of every
 * branch) happens only when something actually composes it.
 *
 * These run on a plain cloud runner with no emulator, so they fit the project's cloud-build-only rule.
 * A crash here fails CI with the real stack trace, which is the whole point.
 *
 * `@Config(application = Application::class)` deliberately bypasses the real `@HiltAndroidApp`
 * application: the dependencies are constructed by hand below, so the screen is exercised without
 * standing up the DI graph.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmsDebugScreenRenderTest {

    private fun settingsRepository(): SettingsRepository {
        val file = File.createTempFile("settings-render-test", ".preferences_pb").also { it.delete() }
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create { file }
        return SettingsRepository(store)
    }

    private fun viewModelWith(context: Context, log: SmsDebugLog): SmsDebugViewModel =
        SmsDebugViewModel(
            debugLog = log,
            settingsRepository = settingsRepository(),
            captureNotifier = CaptureNotifier(context),
            context = context,
        )

    /** Isolates a crash in the ViewModel's construction/`init` from a crash in composition. */
    @Test
    fun the_view_model_can_be_constructed() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        viewModelWith(context, SmsDebugLog())
    }

    /** The owner's exact scenario: open the screen on a freshly started app, nothing recorded yet. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun the_screen_opens_with_an_empty_log() = runComposeUiTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = viewModelWith(context, SmsDebugLog())
        setContent { SmsDebugScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
    }

    /**
     * The same screen once messages have landed. Every [SmsDebugLog.Outcome] is recorded, so every
     * `plainSmsOutcome` branch and every per-message card is actually composed rather than merely
     * compiled — the empty-log case renders none of them.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun the_screen_opens_with_every_outcome_recorded() = runComposeUiTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val log = SmsDebugLog()
        log.recordReceived(now = 1_753_000_000_000L)
        SmsDebugLog.Outcome.values().forEachIndexed { index, outcome ->
            log.record(
                timeMillis = 1_753_000_000_000L + index,
                sender = if (index % 2 == 0) "AD-HDFCBK" else "+91 98765 43210",
                body = "Rs.1,234.00 debited from a/c XX4321 to coffeeday@ybl. OTP 481920.",
                outcome = outcome,
            )
        }
        val viewModel = viewModelWith(context, log)
        setContent { SmsDebugScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
    }
}
