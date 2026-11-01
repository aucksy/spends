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

/** What the Home pop-up about banks and cards shows right now. */
sealed interface BanksCardsAsk {
    data object None : BanksCardsAsk

    /** "May Spends look in your SMS for your banks and cards?" — asked once, before anything is read. */
    data object MayWeLook : BanksCardsAsk

    /** Display lines for what a scan found ("HDFC Bank Credit Card ending 4821"); asked about once each. */
    data class Found(val lines: List<String>, val anyCard: Boolean) : BanksCardsAsk
}

/**
 * On Home: first asks the owner whether Spends may look in their SMS for banks and cards. On "Yes" it scans
 * (see [CardNudge] for how often) and lists what it found, ONCE per item, so they set each one up — a card's
 * bill date decides which month its spends count in. On "I'll add them myself" it never reads, and opens
 * Banks & Cards. Setup itself is the existing review flow there, which pre-fills a bill date found in SMS.
 */
@HiltViewModel
class NewCardsNudgeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val smsCaptureRepository: SmsCaptureRepository,
    private val paymentMethodRepository: PaymentMethodRepository,
) : ViewModel() {

    private val _ask = MutableStateFlow<BanksCardsAsk>(BanksCardsAsk.None)
    val ask: StateFlow<BanksCardsAsk> = _ask

    init {
        viewModelScope.launch {
            if (DemoMode.isEnabled(context)) return@launch
            if (!settingsRepository.settings.first().smsCaptureEnabled || !canReadSms()) return@launch
            when (CardNudge.read(context).consent) {
                CardNudge.Consent.UNKNOWN -> _ask.value = BanksCardsAsk.MayWeLook
                CardNudge.Consent.YES -> scanAndAsk()
                CardNudge.Consent.NO -> Unit
            }
        }
    }

    /** "Yes, find them". */
    fun allow() {
        _ask.value = BanksCardsAsk.None
        CardNudge.write(context, CardNudge.read(context).copy(consent = CardNudge.Consent.YES))
        viewModelScope.launch { scanAndAsk() }
    }

    /** "I'll add them myself" — never read the inbox for this. */
    fun decline() {
        _ask.value = BanksCardsAsk.None
        CardNudge.write(context, CardNudge.read(context).copy(consent = CardNudge.Consent.NO))
    }

    fun dismiss() {
        _ask.value = BanksCardsAsk.None
    }

    private fun canReadSms(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private suspend fun scanAndAsk() {
        var state = CardNudge.read(context)
        val now = DateUtils.nowMillis()
        val start = CardNudge.scanStart(state, now)
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
                android.util.Log.w("NewCardsNudge", "Bank and card scan failed", e)
            }
        }
        val candidates = paymentMethodRepository.observeCandidates().first()
        val ask = CardNudge.toAsk(candidates.map { it.id }, state)
        if (ask.isEmpty()) return
        CardNudge.write(context, CardNudge.onAsked(state, ask, candidates.map { it.id }))
        val found = candidates.filter { it.id in ask }
        _ask.value = BanksCardsAsk.Found(
            lines = found.map { c -> if (c.last4 != null) "${c.label} ending ${c.last4}" else c.label },
            anyCard = found.any { it.isCardInstrument() },
        )
    }
}

/** The Home pop-up: "May Spends look?" first, then "Found in your SMS" for each new bank or card. */
@Composable
fun NewCardsDialog(onOpenBanksCards: () -> Unit, viewModel: NewCardsNudgeViewModel = hiltViewModel()) {
    val ask by viewModel.ask.collectAsState()
    when (val a = ask) {
        BanksCardsAsk.None -> Unit
        BanksCardsAsk.MayWeLook -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("Find your banks and cards?") },
            text = {
                Text(
                    "Spends can look at your bank SMS from the last 1 year to find your bank accounts and credit " +
                        "cards, and each card's bill date.\n\n" +
                        "It reads them on this phone only. Nothing is sent anywhere, and nothing is added until you check it.",
                )
            },
            confirmButton = { TextButton(onClick = viewModel::allow) { Text("Yes, find them") } },
            dismissButton = {
                TextButton(onClick = { viewModel.decline(); onOpenBanksCards() }) { Text("I'll add them myself") }
            },
        )
        is BanksCardsAsk.Found -> {
            val one = a.lines.size == 1
            AlertDialog(
                onDismissRequest = viewModel::dismiss,
                title = { Text("Found in your SMS") },
                text = {
                    Text(
                        a.lines.joinToString("\n") { "• $it" } + "\n\n" +
                            "Add ${if (one) "it" else "them"} so Spends knows what paid for each spend." +
                            if (a.anyCard) {
                                " For a card, also set its bill date. Then its spends count in the same month as your card bill."
                            } else {
                                ""
                            },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismiss(); onOpenBanksCards() }) { Text("Review & add") }
                },
                dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("Later") } },
            )
        }
    }
}
