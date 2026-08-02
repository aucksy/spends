package com.spends.app.core

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.theme.SpendsTheme
import com.spends.app.domain.model.ThemeMode
import com.spends.app.receiver.CaptureActionReceiver
import com.spends.app.ui.components.DemoModeWrapper
import com.spends.app.ui.navigation.SpendsNavHost
import com.spends.app.ui.onboarding.SplashScreenContent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { viewModel.uiState.value.loading }
        handleCaptureEditIntent(intent)
        handleOpenExpenseIntent(intent)
        // Only honor a widget quick-add on a genuinely fresh launch. On a config-change/process-death
        // recreate, savedInstanceState != null and Android re-delivers the original launch intent (extra
        // still set), which would otherwise re-pop the sheet. A real new tap arrives via onNewIntent.
        if (savedInstanceState == null) handleQuickAddIntent(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val pendingCaptureDraft by viewModel.pendingCaptureDraft.collectAsStateWithLifecycle()
            val pendingOpenExpenseId by viewModel.pendingOpenExpenseId.collectAsStateWithLifecycle()
            val pendingQuickAdd by viewModel.pendingQuickAdd.collectAsStateWithLifecycle()
            // Quiet brand splash on every cold start (#10), then hand off to the app.
            var showSplash by rememberSaveable { mutableStateOf(true) }
            LaunchedEffect(Unit) { delay(840); showSplash = false }
            // Onboarding is designed light-only — pin it to LIGHT regardless of the system/AUTO-dark
            // setting so it matches the design; the app respects the user's theme once onboarding is done.
            // (A dedicated dark onboarding variant is a separate later task.)
            val themeMode = if (state.settings.onboardingComplete) state.settings.themeMode else ThemeMode.LIGHT
            SpendsTheme(
                themeMode = themeMode,
                autoDarkStartMinute = state.settings.autoDarkStartMinute,
                autoDarkEndMinute = state.settings.autoDarkEndMinute,
            ) {
                // Reveal the app with a calm fade + gentle scale-up while the brand splash fades out —
                // a system-like settle-in instead of an abrupt cut from the dark splash to the app (#3).
                val showAppContent = !(showSplash || state.loading)
                AnimatedContent(
                    targetState = showAppContent,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(durationMillis = 420)) +
                            scaleIn(initialScale = 0.94f, animationSpec = tween(durationMillis = 420))) togetherWith
                            fadeOut(animationSpec = tween(durationMillis = 260))
                    },
                    label = "splash-reveal",
                ) { appVisible ->
                    if (appVisible) {
                        // In demo mode this adds the permanent "sample data" strip above everything; in normal
                        // mode it emits the nav host unchanged.
                        DemoModeWrapper {
                            SpendsNavHost(
                                settings = state.settings,
                                pendingCaptureDraft = pendingCaptureDraft,
                                onCaptureDraftConsumed = viewModel::consumeCaptureDraft,
                                pendingOpenExpenseId = pendingOpenExpenseId,
                                onOpenExpenseConsumed = viewModel::consumeOpenExpense,
                                pendingQuickAdd = pendingQuickAdd,
                                onQuickAddConsumed = viewModel::consumeQuickAdd,
                            )
                        }
                    } else {
                        SplashScreenContent()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCaptureEditIntent(intent)
        handleOpenExpenseIntent(intent)
        handleQuickAddIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Keep the home-screen summary widget (#2) in step with the latest data while the app is used.
        com.spends.app.widget.SummaryWidget.refresh(this)
    }

    /** Home-screen widget tap (#14): open the quick-add sheet once we're at Home. */
    private fun handleQuickAddIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_QUICK_ADD, false) != true) return
        viewModel.requestQuickAdd()
        intent.removeExtra(EXTRA_OPEN_QUICK_ADD) // consume so a recreate doesn't re-fire
    }

    /** "Edit" action of a capture-prompt notification: dismiss it, then open the editor on an unsaved draft (#4). */
    private fun handleCaptureEditIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CAPTURE_EDIT, false) != true) return
        val notifId = intent.getIntExtra(CaptureActionReceiver.EXTRA_NOTIF_ID, 0)
        NotificationManagerCompat.from(this).cancel(notifId)
        viewModel.handleCaptureEdit(
            sender = intent.getStringExtra(CaptureActionReceiver.EXTRA_SENDER),
            body = intent.getStringExtra(CaptureActionReceiver.EXTRA_BODY),
            receivedAt = intent.getLongExtra(CaptureActionReceiver.EXTRA_RECEIVED_AT, System.currentTimeMillis()),
            sourceApp = intent.getStringExtra(CaptureActionReceiver.EXTRA_SOURCE_APP),
        )
        intent.removeExtra(EXTRA_CAPTURE_EDIT) // consume so a config change / re-create doesn't re-fire
    }

    /**
     * "Edit" (or the body) of a "recurring added" notification (#3): open that transaction for editing.
     *
     * Unlike the capture prompt above, the row already EXISTS — this opens the real transaction, so a stale
     * notification acted on after the user deleted the transaction simply finds nothing and falls back to a
     * blank add rather than resurrecting anything.
     */
    private fun handleOpenExpenseIntent(intent: Intent?) {
        val expenseId = intent?.getLongExtra(EXTRA_OPEN_EXPENSE_ID, NO_ID) ?: NO_ID
        if (expenseId == NO_ID) return
        viewModel.requestOpenExpense(expenseId)
        intent?.removeExtra(EXTRA_OPEN_EXPENSE_ID) // consume so a recreate doesn't re-open it
    }

    companion object {
        const val EXTRA_CAPTURE_EDIT = "capture_edit"
        const val EXTRA_OPEN_QUICK_ADD = "open_quick_add"

        /** Transaction id carried by a "recurring added" notification (#3). */
        const val EXTRA_OPEN_EXPENSE_ID = "open_expense_id"
        private const val NO_ID = -1L
    }
}
