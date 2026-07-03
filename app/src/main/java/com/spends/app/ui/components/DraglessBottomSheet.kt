package com.spends.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A bottom-anchored panel built on a plain [Dialog] instead of a Material [ModalBottomSheet].
 *
 * The whole point: a Dialog has **no swipe-to-dismiss gesture**, so an accidental downward swipe can never
 * discard the user's work — the repeated complaint that ModalBottomSheet's swipe kept causing. And there is
 * no `confirmValueChange` veto anywhere, so it also cannot deadlock touch handling (the earlier freeze).
 *
 * Dismissal is deliberate only: the caller's own ✕ button, or the system back gesture ([onDismissRequest]).
 * Tapping the dimmed area outside does nothing (`dismissOnClickOutside = false`). The panel spans the full
 * width, sits above the keyboard (imePadding) and the navigation bar, with a rounded top like a sheet.
 */
@Composable
fun DraglessBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
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
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding(),
            ) {
                Column(content = content)
            }
        }
    }
}
