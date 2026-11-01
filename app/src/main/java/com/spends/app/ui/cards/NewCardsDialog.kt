package com.spends.app.ui.cards

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.CardNudge
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.demo.DemoMode
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * On Home: looks for new credit cards in the SMS inbox (see [CardNudge] for how often), then asks the owner
 * ONCE per found card to set it up, so its spends follow the card's own bill date. The setup itself is the
 * existing "Cards to review" flow on Banks & cards, which already pre-fills a bill date found in SMS.
 */
@HiltViewModel
class NewCardsNudgeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val smsCaptureRepository: SmsCaptureRepository,
    private val paymentMethodRepository: PaymentMethodRepository,
) : ViewModel() {

    /** The cards to ask about, as display lines ("HDFC Bank card ending 4821"); empty = no dialog. */
    private val _cards = MutableStateFlow<List<String>>(emptyList())
    val cards: StateFlow<List<String>> = _cards

    init {
        viewModelScope.launch {
            if (DemoMode.isEnabled(context)) return@launch
            if (!settingsRepository.settings.first().smsCaptureEnabled) return@launch
            val canRead = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
            var state = CardNudge.read(context)
            val now = DateUtils.nowMillis()
            val start = if (canRead) CardNudge.scanStart(state, now) else null
            if (start != null) {
                try {
                    // Null = the inbox could not be read; try again next time instead of skipping a day.
                    if (smsCaptureRepository.scanInboxForCards(start, now) != null) {
                        state = state.copy(lastScanAt = now)
                        CardNudge.write(context, state)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("NewCardsNudge", "Card scan failed", e)
                }
            }
            val candidates = paymentMethodRepository.observeCandidates().first()
            val ask = CardNudge.toAsk(candidates.map { it.id }, state)
            if (ask.isEmpty()) return@launch
            CardNudge.write(context, CardNudge.onAsked(state, ask, candidates.map { it.id }))
            _cards.value = candidates.filter { it.id in ask }.map { c ->
                if (c.last4 != null) "${c.label} ending ${c.last4}" else c.label
            }
        }
    }

    fun dismiss() {
        _cards.value = emptyList()
    }
}

/** "New card found" — asks the owner to set up a card that Spends found in their SMS. */
@Composable
fun NewCardsDialog(onSetUp: () -> Unit, viewModel: NewCardsNudgeViewModel = hiltViewModel()) {
    val cards by viewModel.cards.collectAsState()
    if (cards.isEmpty()) return
    val one = cards.size == 1
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(if (one) "New card found" else "${cards.size} new cards found") },
        text = {
            Text(
                "Spends found ${if (one) "this card" else "these cards"} in your SMS:\n\n" +
                    cards.joinToString("\n") { "• $it" } + "\n\n" +
                    "Set the bill date for ${if (one) "it" else "each one"}. Then the spends on " +
                    "${if (one) "this card" else "each card"} count in the right month, the same as your card bill.",
            )
        },
        confirmButton = {
            TextButton(onClick = { viewModel.dismiss(); onSetUp() }) { Text(if (one) "Set up card" else "Set up cards") }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismiss) { Text("Later") }
        },
    )
}
