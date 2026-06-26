package com.spends.app.core

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spends.app.core.theme.SpendsTheme
import com.spends.app.ui.quickadd.QuickAddSheet
import dagger.hilt.android.AndroidEntryPoint

/**
 * Standalone transparent overlay that hosts ONLY the quick-add keypad sheet (#1, the Monito-style "+").
 * Launched directly by the home-screen widgets so adding a transaction never opens the full app /
 * transaction list behind it — and so a future app-lock (which would gate [MainActivity]) never blocks a
 * quick add. The window is see-through; the sheet draws over whatever was behind (the home screen). Saving
 * shows a toast and lets the sheet animate away; dismissing finishes the activity.
 *
 * Manifest: its own task affinity + noHistory + excludeFromRecents so it stays a transient overlay and
 * never brings the main app task forward.
 */
@AndroidEntryPoint
class QuickAddActivity : ComponentActivity() {

    private val viewModel: QuickAddHostViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            SpendsTheme(
                themeMode = settings.themeMode,
                autoDarkStartMinute = settings.autoDarkStartMinute,
                autoDarkEndMinute = settings.autoDarkEndMinute,
            ) {
                QuickAddSheet(
                    onDismiss = { finish() },
                    onSaved = {
                        Toast.makeText(this, "Transaction added", Toast.LENGTH_SHORT).show()
                        // This overlay never starts MainActivity, whose onResume is the only other place the
                        // summary widget is refreshed — so refresh it here, or its totals would stay stale.
                        com.spends.app.widget.SummaryWidget.refresh(this)
                    },
                )
            }
        }
    }
}
