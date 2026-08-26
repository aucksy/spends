package com.spends.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.core.money.AppCurrency
import com.spends.app.core.money.FxMath
import com.spends.app.core.money.Money
import com.spends.app.data.ai.AiClient
import com.spends.app.data.ai.AiProvider
import com.spends.app.data.ai.AiResult
import com.spends.app.data.ai.CurrencyAi
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.data.settings.SettingsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the "Test key" button is currently reporting. */
sealed interface KeyTestState {
    data object Idle : KeyTestState
    data object Testing : KeyTestState
    data object Passed : KeyTestState
    data class Failed(val reason: String) : KeyTestState
}

/**
 * Backs "Currency & AI" settings: the currency the books are kept in, and the user's own AI key for
 * converting foreign-currency alerts.
 *
 * The stored key is deliberately never exposed to the UI — [hasKey] says only whether one exists. A
 * screen that could redisplay a secret is a screen that leaks it to anyone holding the phone, and the
 * user always has the provider's console if they need to read it back.
 */
@HiltViewModel
class CurrencyAiViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val aiClient: AiClient,
    private val currencyAi: CurrencyAi,
) : ViewModel() {

    val state: StateFlow<SettingsState> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    val hasKey: StateFlow<Boolean> = aiClient.hasKeyFlow

    private val _keyTest = MutableStateFlow<KeyTestState>(KeyTestState.Idle)
    val keyTest: StateFlow<KeyTestState> = _keyTest

    /**
     * Change the currency the whole ledger is kept in.
     *
     * [Money.displayCurrency] is set here as well as by `MainViewModel`'s collector so the change is on
     * screen the instant the sheet closes, rather than one recomposition later. Cached FX rates are
     * dropped because every one of them was quoted INTO the old currency.
     */
    fun setBaseCurrency(currency: AppCurrency, onSaved: () -> Unit = {}) = viewModelScope.launch {
        settingsRepository.setBaseCurrency(currency)
        Money.displayCurrency = currency
        currencyAi.clearCache()
        onSaved()
    }

    fun setAiConversionEnabled(value: Boolean) = viewModelScope.launch {
        settingsRepository.setAiConversionEnabled(value)
    }

    fun setProvider(provider: AiProvider) = viewModelScope.launch {
        settingsRepository.setAiProvider(provider)
        // The model field is per-provider; carrying "gpt-4o-mini" across to Anthropic would just 404.
        settingsRepository.setAiModel("")
        currencyAi.clearCache()
        _keyTest.value = KeyTestState.Idle
    }

    fun setModel(model: String) = viewModelScope.launch {
        settingsRepository.setAiModel(model)
        currencyAi.clearCache()
    }

    /** Save the key the user pasted. Blank clears it. */
    fun saveKey(rawKey: String) = viewModelScope.launch {
        aiClient.setKey(rawKey)
        currencyAi.clearCache()
        _keyTest.value = KeyTestState.Idle
    }

    fun clearKey() = viewModelScope.launch {
        aiClient.clearKey()
        currencyAi.clearCache()
        _keyTest.value = KeyTestState.Idle
    }

    /**
     * One tiny live call to prove the key works, so a wrong key is found here rather than discovered as a
     * silently unconverted transaction days later. [rawKey] lets a just-typed key be tested before saving.
     */
    fun testKey(rawKey: String? = null) = viewModelScope.launch {
        _keyTest.value = KeyTestState.Testing
        val settings = state.value
        val result = if (!rawKey.isNullOrBlank()) {
            aiClient.testKey(settings.aiProvider, settings.aiModel, rawKey)
        } else {
            aiClient.chat(
                provider = settings.aiProvider,
                model = settings.aiModel,
                system = "You are a connection health check. Reply with the single word OK.",
                user = "ping",
            )
        }
        _keyTest.value = when (result) {
            is AiResult.Ok -> KeyTestState.Passed
            is AiResult.Failed -> KeyTestState.Failed(explain(result.reason))
        }
    }

    /** Pin a fixed rate for [from] → the base currency, or clear it when [rateText] is blank/unusable. */
    fun setManualRate(from: String, rateText: String) = viewModelScope.launch {
        val base = state.value.baseCurrency.code
        val micros = FxMath.rateMicrosFromDouble(rateText.trim().replace(",", "").toDoubleOrNull())
        settingsRepository.setManualRate(from, base, micros)
        currencyAi.clearCache()
    }

    /**
     * Turn a provider's status code into something actionable. The client deliberately never surfaces a
     * response body (it can echo the key back), so these three codes are all there is to go on — and they
     * are the three that actually happen.
     */
    private fun explain(reason: String): String = when {
        reason.contains("401") || reason.contains("403") -> "That key was rejected. Check you pasted it in full."
        reason.contains("404") -> "The model name isn't available on this key. Try clearing the model field."
        reason.contains("429") -> "Rate limited by the provider. Wait a moment and try again."
        else -> reason
    }
}
