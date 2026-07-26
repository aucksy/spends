package com.spends.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spends.app.data.demo.DemoMode

/**
 * Wraps the whole app in a permanent "this is demo data" strip while demo mode is active.
 *
 * This is a safety control, not decoration. Every figure on screen in demo mode is fabricated; without a
 * permanent marker it would be genuinely possible to glance at the app, read a balance, and act on a number
 * that means nothing. It is not dismissible and it does not scroll away.
 *
 * **When demo mode is off this emits [content] and nothing else** — no wrapper, no extra layout node — so the
 * real app is unchanged.
 *
 * Inset handling: the strip paints behind the status bar and pads its own text clear of it, then the content
 * below declares the status-bar inset already consumed, so each screen's `Scaffold` doesn't add that padding
 * a second time and leave a gap under the strip.
 */
@Composable
fun DemoModeWrapper(content: @Composable () -> Unit) {
    if (!DemoMode.isEnabled(LocalContext.current)) {
        content()
        return
    }
    Column(Modifier.fillMaxSize()) {
        DemoStrip(Modifier.windowInsetsPadding(WindowInsets.statusBars))
        Box(
            Modifier
                .weight(1f)
                .consumeWindowInsets(WindowInsets.statusBars),
        ) { content() }
    }
}

@Composable
private fun DemoStrip(modifier: Modifier = Modifier) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .then(modifier)
            .padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            text = "  DEMO MODE — sample data, not your money",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}
