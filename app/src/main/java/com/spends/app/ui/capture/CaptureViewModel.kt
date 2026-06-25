package com.spends.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.domain.model.SmsCaptureMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CaptureUiState(
    val enabled: Boolean = false,
    val mode: SmsCaptureMode = SmsCaptureMode.AUTO_ADD,
    val working: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val captureRepository: SmsCaptureRepository,
) : ViewModel() {

    private val _local = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> =
        combine(_local, settingsRepository.settings) { local, s ->
            local.copy(enabled = s.smsCaptureEnabled, mode = s.smsCaptureMode)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CaptureUiState())

    fun setMode(mode: SmsCaptureMode) {
        viewModelScope.launch { settingsRepository.setSmsCaptureMode(mode) }
    }

    /** Permission granted → enable capture and backfill the inbox once. */
    fun enableWithGrant() {
        viewModelScope.launch {
            settingsRepository.setSmsCaptureEnabled(true)
            _local.update { it.copy(working = true, message = "Reading your SMS inbox…") }
            val r = runCatching { captureRepository.backfillInbox() }.getOrNull()
            _local.update {
                it.copy(
                    working = false,
                    message = if (r == null) {
                        "Couldn't read the inbox."
                    } else {
                        "Captured ${r.created} from SMS" + if (r.needsReview > 0) " · ${r.needsReview} to review" else ""
                    },
                )
            }
        }
    }

    fun disable() {
        viewModelScope.launch {
            settingsRepository.setSmsCaptureEnabled(false)
            _local.update { it.copy(message = "SMS capture turned off") }
        }
    }

    fun permissionDenied() {
        _local.update { it.copy(message = "SMS permission is needed to read bank texts.") }
    }
}
