package com.spends.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.ai.GroqClient
import com.spends.app.data.ai.GroqResult
import com.spends.app.data.backup.SecureKeyStore
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.data.settings.SettingsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Result of pressing "Test key" — drives a plain-English status line. */
sealed interface KeyTestStatus {
    object Idle : KeyTestStatus
    object Testing : KeyTestStatus
    object Working : KeyTestStatus
    data class Failed(val message: String) : KeyTestStatus
}

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val secureKeyStore: SecureKeyStore,
    private val groqClient: GroqClient,
) : ViewModel() {

    val state: StateFlow<SettingsState> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    private val _hasKey = MutableStateFlow(secureKeyStore.hasApiKey())
    val hasKey: StateFlow<Boolean> = _hasKey

    private val _testStatus = MutableStateFlow<KeyTestStatus>(KeyTestStatus.Idle)
    val testStatus: StateFlow<KeyTestStatus> = _testStatus

    fun setEnabled(value: Boolean) = viewModelScope.launch { settingsRepository.setAiEnabled(value) }
    fun setCategorize(value: Boolean) = viewModelScope.launch { settingsRepository.setAiCategorize(value) }
    fun setInsights(value: Boolean) = viewModelScope.launch { settingsRepository.setAiInsights(value) }

    /** Persist a pasted key (encrypted, device-local). Blank is ignored. */
    fun saveKey(raw: String) = viewModelScope.launch {
        val key = raw.trim()
        if (key.isEmpty()) return@launch
        withContext(Dispatchers.IO) { secureKeyStore.setApiKey(key) }
        _hasKey.value = secureKeyStore.hasApiKey()
        _testStatus.value = KeyTestStatus.Idle
    }

    fun removeKey() = viewModelScope.launch {
        withContext(Dispatchers.IO) { secureKeyStore.clearApiKey() }
        _hasKey.value = false
        _testStatus.value = KeyTestStatus.Idle
    }

    /** Test [raw] if the user typed one, otherwise the stored key. One tiny call; fail-closed messaging. */
    fun testKey(raw: String) = viewModelScope.launch {
        _testStatus.value = KeyTestStatus.Testing
        val typed = raw.trim()
        val result = when {
            typed.isNotEmpty() -> groqClient.testKey(typed)
            else -> {
                val stored = withContext(Dispatchers.IO) { secureKeyStore.apiKey() }
                if (stored == null) GroqResult.Failed("No API key set") else groqClient.testKey(stored)
            }
        }
        _testStatus.value = when (result) {
            is GroqResult.Ok -> KeyTestStatus.Working
            is GroqResult.Failed -> KeyTestStatus.Failed(friendlyError(result.reason))
        }
    }

    private fun friendlyError(reason: String): String = when {
        reason.contains("401") || reason.contains("403") -> "That key was rejected — double-check you pasted it correctly."
        reason.contains("429") -> "Groq is busy right now — wait a moment and try again."
        reason.contains("No API key") -> "Enter a key first, then test it."
        reason.startsWith("HTTP 5") -> "Groq had a temporary problem — try again shortly."
        else -> "Couldn't reach Groq — check your internet connection."
    }
}
