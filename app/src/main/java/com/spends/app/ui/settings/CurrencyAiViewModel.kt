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
import kotlinx.coroutines.flow.first
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

    /**
     * Change provider — which also **removes the saved key**, because there is only one key slot and the
     * key in it belongs to the provider being left behind.
     *
     * Without this, picking Google with an Anthropic key saved sent `sk-ant-…` to Google as
     * `x-goog-api-key` on the very next foreign alert: no prompt, no warning, the settings row still
     * reading "Saved on this device", and the secret landing in another vendor's request logs. That is
     * the exact opposite of the sentence on the provider dialog, and the first thing an existing user
     * does after this release is switch provider with a key already saved.
     *
     * Choosing the provider that is already selected is a no-op — the dialog's "Done" fires this whether
     * or not the selection moved, and it must not cost the user their key or their typed model.
     */
    fun setProvider(provider: AiProvider) = viewModelScope.launch {
        if (provider == settingsRepository.settings.first().aiProvider) return@launch
        settingsRepository.setAiProvider(provider)
        // The model field is per-provider; carrying "gpt-4o-mini" across to Anthropic would just 404.
        settingsRepository.setAiModel("")
        aiClient.clearKey()
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
            is AiResult.Failed -> KeyTestState.Failed(explain(result.reason, settings.aiProvider))
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
     * response body (it can echo the key back), so a status code is all there is to go on — and these are
     * the ones that actually happen. Google is why there are four rather than three: it reports a bad key
     * as 400 where the others use 401.
     */
    private fun explain(reason: String, provider: AiProvider): String = when {
        reason.contains("401") || reason.contains("403") -> "That key was rejected. Check you pasted it in full."
        // Google answers a bad key with 400 where the others use 401, and the same 400 also covers a
        // request it could not read — so this one has to name both causes rather than guess.
        reason.contains("400") -> "That key or model was rejected. Check the key is pasted in full, " +
            "then try a different model."
        // Naming the default matters: the old wording said "try clearing the model field", which is a dead
        // end when the field is ALREADY blank — clearing it just puts back the default that has stopped
        // working. Providers rotate their model lineups, so that day comes.
        reason.contains("404") -> "That model isn't available on this key. Open Model and type one that " +
            "is — leaving it blank uses ${provider.defaultModel}."
        reason.contains("429") -> "Rate limited by the provider. Wait a moment and try again."
        // The provider's own trouble, not anything a setting can fix — and a bare "HTTP 503" reads like a
        // bug in Spends. Google documents 503 as "temporarily overloaded or down, wait and retry", and
        // the usual reason is a recently released model whose capacity has not caught up with its launch:
        // hence the nudge towards an older one, which is a thing the user can actually act on.
        reason.contains("500") || reason.contains("502") ||
            reason.contains("503") || reason.contains("504") ->
            "${provider.label} is busy or down right now — nothing wrong with your key. Try again in a " +
                "minute. If it keeps happening, open Model and use an older one, which is usually less " +
                "crowded than whatever launched most recently."
        else -> reason
    }
}
