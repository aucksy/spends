package com.spends.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand splash colours (design "Spends Onboarding Flow" screen 1) — fixed regardless of app theme.
private val SplashBg = Color(0xFF0E1512)
private val SplashTile = Color(0xFF1C4A45)
private val SplashBarBright = Color(0xFF4FC9BD)
private val SplashBarDim = Color(0xFF2E6B5E)
private val SplashTitle = Color(0xFFF3F4F1)
private val SplashMuted = Color(0xFF7E8C85)

/**
 * The quiet brand splash shown briefly on every cold start (#10). Descending-bars mark + wordmark +
 * "Quiet money." + an on-device privacy line.
 */
@Composable
fun SplashScreenContent() {
    Box(modifier = Modifier.fillMaxSize().background(SplashBg)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(30.dp)).background(SplashTile),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Bar(42.dp, SplashBarBright)
                    Bar(30.dp, SplashBarDim)
                    Bar(18.dp, SplashBarDim)
                }
            }
            Spacer(Modifier.height(26.dp))
            Text("Spends", color = SplashTitle, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text("Quiet money.", color = SplashMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = SplashBarBright, modifier = Modifier.size(16.dp))
            Text("Private & on-device · always", color = SplashMuted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Bar(barHeight: androidx.compose.ui.unit.Dp, color: Color) {
    Box(modifier = Modifier.width(11.dp).height(barHeight).clip(RoundedCornerShape(3.dp)).background(color))
}
