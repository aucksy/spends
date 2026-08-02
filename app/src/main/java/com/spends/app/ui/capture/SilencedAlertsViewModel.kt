package com.spends.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.capture.SilencedAlert
import com.spends.app.data.capture.SmsCaptureRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SilencedAlertsUiState(
    /** Already past the threshold — Spends no longer posts these at all. */
    val silenced: List<SilencedAlert> = emptyList(),
    /** Ignored once or twice: not silenced yet, but on the way. Shown so it is never a surprise. */
    val approaching: List<SilencedAlert> = emptyList(),
    val message: String? = null,
    val loaded: Boolean = false,
) {
    val isEmpty: Boolean get() = silenced.isEmpty() && approaching.isEmpty()
}

/**
 * Backs the "Silenced alerts" screen — the undo for learn-from-ignore (#7).
 *
 * Ignoring the same alert three times switched its prompt off permanently and, until this screen,
 * nothing in the app could list that state or reverse it. The suppression is device-local and is not
 * part of the Drive backup, so a reinstall was the only known way out.
 */
@HiltViewModel
class SilencedAlertsViewModel @Inject constructor(
    private val captureRepository: SmsCaptureRepository,
) : ViewModel() {

    private val _local = MutableStateFlow(SilencedAlertsUiState())

    val state: StateFlow<SilencedAlertsUiState> =
        combine(_local, captureRepository.observeSilencedAlerts()) { local, alerts ->
            local.copy(
                silenced = alerts.filter { it.isSilenced },
                approaching = alerts.filterNot { it.isSilenced },
                loaded = true,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SilencedAlertsUiState())

    /** Start asking about this alert again. The row is removed, so the count restarts from zero. */
    fun unsilence(alert: SilencedAlert) {
        viewModelScope.launch {
            runCatching { captureRepository.unsilenceAlert(alert.patternKey) }
            _local.update {
                it.copy(
                    message = if (alert.isSilenced) {
                        "Spends will ask you about this one again"
                    } else {
                        "Ignore count reset — this one was never silenced"
                    },
                )
            }
        }
    }

    fun unsilenceAll() {
        viewModelScope.launch {
            runCatching { captureRepository.unsilenceAllAlerts() }
            _local.update { it.copy(message = "Spends will ask you about all of them again") }
        }
    }

    fun clearMessage() = _local.update { it.copy(message = null) }
}
