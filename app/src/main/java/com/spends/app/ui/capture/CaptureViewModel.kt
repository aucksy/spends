package com.spends.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.time.DateUtils
import com.spends.app.data.capture.SmsCaptureRepository
import com.spends.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class CaptureUiState(
    val enabled: Boolean = false,
    val pendingCount: Int = 0,
    val capturedCount: Int = 0, // SMS-captured transactions already on the timeline (#7)
    val hideCaptured: Boolean = false,
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
        combine(
            _local,
            settingsRepository.settings,
            captureRepository.observePendingCount(),
            captureRepository.observeCapturedCount(),
        ) { local, s, pending, captured ->
            local.copy(
                enabled = s.smsCaptureEnabled,
                hideCaptured = s.hideCapturedInLists,
                pendingCount = pending,
                capturedCount = captured,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CaptureUiState())

    /** Permission granted → turn capture on. Does NOT scan history (review-only: that's an explicit action). */
    fun enableWithGrant() {
        viewModelScope.launch {
            settingsRepository.setSmsCaptureEnabled(true)
            _local.update {
                it.copy(message = "On — new bank SMS will ask you to add it. Use \"Scan past SMS\" to review older texts.")
            }
        }
    }

    fun disable() {
        viewModelScope.launch {
            settingsRepository.setSmsCaptureEnabled(false)
            _local.update { it.copy(message = "SMS detection turned off") }
        }
    }

    fun permissionDenied() = _local.update { it.copy(message = "SMS permission is needed to read bank texts.") }

    fun setHideCaptured(value: Boolean) = viewModelScope.launch { settingsRepository.setHideCapturedInLists(value) }

    /** Scan the last [months] calendar months (including the current one) into the review queue. */
    fun scanLastMonths(months: Int) {
        val today = LocalDate.now(DateUtils.ZONE)
        val firstMonth = YearMonth.from(today).minusMonths((months - 1).toLong())
        val start = DateUtils.startOfDayMillis(firstMonth.atDay(1))
        val endExclusive = DateUtils.startOfDayMillis(YearMonth.from(today).plusMonths(1).atDay(1))
        scan(start, endExclusive)
    }

    fun scanCustom(startMillis: Long, endExclusiveMillis: Long) = scan(startMillis, endExclusiveMillis)

    private fun scan(startMillis: Long, endExclusiveMillis: Long) {
        viewModelScope.launch {
            _local.update { it.copy(working = true, message = "Scanning your SMS…") }
            val r = runCatching { captureRepository.scanHistory(startMillis, endExclusiveMillis) }.getOrNull()
            _local.update {
                it.copy(
                    working = false,
                    message = when {
                        r == null -> "Couldn't read the inbox."
                        r.queued == 0 -> "Nothing new to review in that range."
                        else -> "Queued ${r.queued} to review" +
                            if (r.skippedDuplicate > 0) " · skipped ${r.skippedDuplicate} already-known" else ""
                    },
                )
            }
        }
    }

    /** #7: clear the to-review SCAN QUEUE (pending_captures) — leaves anything already added. */
    fun clearReviewQueue() {
        viewModelScope.launch {
            _local.update { it.copy(working = true, message = null) }
            runCatching { captureRepository.clearPending() }
            _local.update { it.copy(working = false, message = "Review queue cleared") }
        }
    }

    /** #7: clear the queue AND delete the past-SMS-scanned transactions already added to the timeline. */
    fun clearQueueAndDeleteAdded() {
        viewModelScope.launch {
            _local.update { it.copy(working = true, message = null) }
            runCatching { captureRepository.clearPending() }
            val n = runCatching { captureRepository.deleteAllCaptured() }.getOrNull() ?: 0
            _local.update { it.copy(working = false, message = "Cleared queue · deleted $n added transactions") }
        }
    }

    fun clearMessage() = _local.update { it.copy(message = null) }
}
