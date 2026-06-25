package com.spends.app.core

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.theme.SpendsTheme
import com.spends.app.receiver.CaptureActionReceiver
import com.spends.app.ui.navigation.SpendsNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { viewModel.uiState.value.loading }
        handleCaptureEditIntent(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val pendingEdit by viewModel.pendingEditId.collectAsStateWithLifecycle()
            SpendsTheme(
                themeMode = state.settings.themeMode,
                dynamicColor = state.settings.dynamicColor,
            ) {
                if (!state.loading) {
                    SpendsNavHost(
                        settings = state.settings,
                        pendingEditExpenseId = pendingEdit,
                        onPendingEditConsumed = viewModel::consumePendingEdit,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCaptureEditIntent(intent)
    }

    /** "Edit" action of a capture-prompt notification: dismiss it, persist the SMS, open its editor. */
    private fun handleCaptureEditIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CAPTURE_EDIT, false) != true) return
        val notifId = intent.getIntExtra(CaptureActionReceiver.EXTRA_NOTIF_ID, 0)
        NotificationManagerCompat.from(this).cancel(notifId)
        viewModel.handleCaptureEdit(
            sender = intent.getStringExtra(CaptureActionReceiver.EXTRA_SENDER),
            body = intent.getStringExtra(CaptureActionReceiver.EXTRA_BODY),
            receivedAt = intent.getLongExtra(CaptureActionReceiver.EXTRA_RECEIVED_AT, System.currentTimeMillis()),
        )
        intent.removeExtra(EXTRA_CAPTURE_EDIT) // consume so a config change / re-create doesn't re-fire
    }

    companion object {
        const val EXTRA_CAPTURE_EDIT = "capture_edit"
    }
}
