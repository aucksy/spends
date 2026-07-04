package com.spends.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * A bottom-anchored panel built on a plain [Dialog] instead of a Material [ModalBottomSheet].
 *
 * The point: a Dialog has **no swipe-to-dismiss gesture**, so an accidental downward swipe can never
 * discard the user's work (the repeated complaint), and there is no `confirmValueChange` veto so it can't
 * deadlock touch either. Dismissal is deliberate only — the caller's ✕ button or the back gesture.
 *
 * INSETS: a plain Compose Dialog does NOT feed WindowInsets into its composition, so `navigationBarsPadding`
 * / `imePadding` read 0 and the content runs under the gesture bar / keyboard (this bit us twice). We instead
 * read the RAW window insets straight off the dialog's view via an OnApplyWindowInsetsListener and pad the
 * content by max(navigation bar, keyboard). That is the low-level value the system actually dispatches, so it
 * can never silently be zero.
 *
 * Colour matches the app's other bottom sheets (`surfaceContainerLow`, no tonal-elevation tint). Caps at 94%
 * of the screen and scrolls internally for tall content (short content just wraps).
 */
@Composable
fun DraglessBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.94f).dp
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            // Edge-to-edge so the raw insets are dispatched to the view (not consumed by the decor).
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        val density = LocalDensity.current
        var bottomInsetPx by remember { mutableStateOf(0) }
        DisposableEffect(view) {
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
                val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                bottomInsetPx = maxOf(nav, ime)
                insets
            }
            ViewCompat.requestApplyInsets(view)
            onDispose { ViewCompat.setOnApplyWindowInsetsListener(view, null) }
        }
        val bottomInset = with(density) { bottomInsetPx.toDp() }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
            ) {
                // The content scrolls internally past the height cap; the bottom padding keeps its last row
                // (the keypad's 0 · Save row / a focused Note) clear of the gesture bar and the keyboard.
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = bottomInset),
                    content = content,
                )
            }
        }
    }
}
