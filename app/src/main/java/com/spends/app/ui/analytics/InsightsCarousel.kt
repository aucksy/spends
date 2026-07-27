package com.spends.app.ui.analytics

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spends.app.data.ai.insights.InsightCard
import com.spends.app.data.ai.insights.InsightKind
import com.spends.app.ui.components.SpendsCard

/**
 * The insights carousel: one card per page, with dots showing there is more to swipe.
 *
 * Replaces the single summary card. The summary is still page 1 — nothing was taken away — and the pages
 * after it are the things the on-device engine found worth saying about this cycle, which is the actual fix
 * for "it's the same insight every single time".
 *
 * Pages carry a minimum height so a one-line card doesn't collapse the carousel; a page taller than that
 * still grows the card, so the content below can shift slightly on swipe. Acceptable for now — a truly fixed
 * height would need measuring every page up front.
 */
@Composable
fun InsightsCarousel(
    loading: Boolean,
    cards: List<InsightCard>,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    SpendsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Insights",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                // Refresh only when there's something to refresh; dismiss is ALWAYS available (an escape
                // hatch even while cards are generating, so a slow or stuck call is never a dead end).
                if (!loading) {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Refresh insights",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss insights",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (loading || cards.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Thinking…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            val pagerState = rememberPagerState(pageCount = { cards.size })
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                pageSpacing = 16.dp,
                verticalAlignment = Alignment.Top,
            ) { page ->
                val card = cards[page]
                Column(modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp)) {
                    // The summary page keeps its original look (no heading) so nothing about the card that
                    // already shipped changes; the new pages lead with a short heading. Keyed on the card's
                    // KIND, not its index — when the summary call fails its page is dropped, and a positional
                    // check would then strip the heading off whichever finding landed first.
                    if (card.title.isNotBlank() && card.kind != InsightKind.CYCLE_SUMMARY) {
                        Text(
                            card.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        card.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (cards.size > 1) {
                Spacer(Modifier.height(12.dp))
                PagerDots(count = cards.size, selected = pagerState.currentPage)
            }
        }
    }
}

/** The "there's more to swipe" affordance: the active page's dot stretches into a short bar. */
@Composable
private fun PagerDots(count: Int, selected: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == selected
            val width by animateDpAsState(targetValue = if (active) 18.dp else 6.dp, label = "insight-dot")
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .height(6.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}
