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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.spends.app.core.theme.LocalSheetBottomInset

/**
 * A bottom-anchored panel built on a plain [Dialog] instead of a Material [ModalBottomSheet].
 *
 * Why a Dialog: it has **no swipe-to-dismiss gesture**, so an accidental downward swipe can never discard
 * the user's work (the repeated complaint), and there is no `confirmValueChange` veto so it can't deadlock
 * touch either. It dismisses deliberately only — the caller's ✕ button or the back gesture.
 *
 * GESTURE-BAR INSET: Compose WindowInsets read 0 inside a plain Dialog (`navigationBarsPadding()` and the
 * `decorFitsSystemWindows` flag are both no-ops there — this bit us repeatedly), yet the fullscreen dialog
 * still draws under the gesture bar, clipping the keypad's bottom row. The real inset is captured in the
 * ACTIVITY (where edge-to-edge insets work) and handed down via [LocalSheetBottomInset]; we pad the content
 * by it so the last row (0 · Save) always clears the gesture bar.
 *
 * Colour matches the app's other bottom sheets (`surfaceContainerLow`, no tonal tint). Caps at 94% of the
 * screen and scrolls internally for tall content.
 */
@Composable
fun DraglessBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.94f).dp
    val bottomInset = LocalSheetBottomInset.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
            ) {
                // Scrolls internally past the height cap; the bottom padding keeps the last row (the keypad's
                // 0 · Save row) clear of the gesture bar — the inset comes from the activity via CompositionLocal
                // because a Dialog can't read it itself.
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
