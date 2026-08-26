package com.spends.app.data.ai

import com.spends.app.core.money.AppCurrency
import com.spends.app.core.money.FxMath
import com.spends.app.core.money.Money
import com.spends.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The result of converting one foreign amount into the base currency.
 *
 * [baseMinor] is what the ledger stores; the rest is the receipt shown to the user, because a number the
 * app silently rewrote is worse than no number at all.
 */
data class Conversion(
    val baseMinor: Long,
    val foreignMinor: Long,
    val foreignCode: String,
    val rateMicros: Long,
    val note: String,
) {
    /** "RM 100.00 → ₹1,890.00 · 1 MYR = ₹18.90" */
    fun describe(base: AppCurrency = Money.displayCurrency): String =
        FxMath.describe(foreignMinor, foreignCode, baseMinor, rateMicros, base)
}

/**
 * What happened when a capture's currency was resolved.
 *
 * The three states are kept distinct because two of them look identical from the outside and mean
 * opposite things. "The alert is already in the ledger's currency" is the *normal* outcome for a
 * Malaysian user on a ringgit ledger reading a ringgit SMS — there is nothing to convert and nothing to
 * warn about. "We could not get a rate" is a problem the user has to see. Collapsing both into a null
 * return flagged every ordinary domestic capture as unconvertible for anyone not keeping books in rupees.
 */
sealed interface ConversionOutcome {
    /** The amount is already in the base currency (or no currency was named). Use it as-is. */
    data object NotNeeded : ConversionOutcome

    /** Converted, with the receipt explaining it. */
    data class Converted(val conversion: Conversion) : ConversionOutcome

    /** Foreign, but no usable rate was available — the amount is still in [code]. */
    data class Unavailable(val code: String) : ConversionOutcome
}

/**
 * Converts a foreign-currency capture into the currency the ledger is kept in, using the user's own AI
 * key (see [AiClient]).
 *
 * **What this is and is not.** A language model does not hold a live market feed; the rate it gives is
 * its best estimate, which is why every converted amount is labelled as an estimate in the UI, carries
 * its rate on the face of the transaction, and can be overridden by a fixed rate the user sets in
 * Settings. The alternative — quietly logging a ringgit figure as if it were rupees — is the actual
 * money bug this exists to prevent, and any sane rate beats that.
 *
 * **Fail-closed.** No key, no network, a nonsense reply, a rate outside [FxMath]'s sane band: all yield
 * [ConversionOutcome.Unavailable], which keeps the amount in its original currency and flagged, rather
 * than letting a half-trusted figure into the ledger.
 *
 * **Cheap.** Rates are cached per currency pair for [CACHE_TTL_MILLIS]; a day's worth of alerts from one
 * bank costs one call. The cache is in-memory only — it dies with the process rather than persisting a
 * stale rate across days.
 */
@Singleton
class CurrencyAi @Inject constructor(
    private val aiClient: AiClient,
    private val settingsRepository: SettingsRepository,
) {

    private data class CachedRate(val rateMicros: Long, val note: String, val atMillis: Long)

    private val cache = mutableMapOf<String, CachedRate>()
    private val cacheMutex = Mutex()

    /**
     * Resolve [foreignMinor] minor units of [foreignCode] against the currency the ledger is kept in.
     *
     * [nowMillis] is passed in rather than read from the clock so the cache is testable.
     */
    suspend fun convert(foreignCode: String, foreignMinor: Long, nowMillis: Long): ConversionOutcome {
        val settings = settingsRepository.settings.first()
        val base = settings.baseCurrency
        val from = foreignCode.trim().uppercase().takeIf { it.isNotEmpty() }
            ?: return ConversionOutcome.NotNeeded
        // The alert is already in the ledger's currency. This is the ordinary domestic case for any user
        // whose books are not in rupees, and it must be reported as "fine", not as "could not convert".
        if (from == base.code) return ConversionOutcome.NotNeeded

        // A manual rate wins outright: it is the user's own stated number, needs no call, and is the
        // documented escape hatch when an AI estimate has drifted.
        manualRateFor(settings.manualRates, from, base.code)?.let { manual ->
            return outcome(foreignMinor, from, manual, "Converted at your saved rate for $from.", base)
        }

        if (!settings.aiConversionEnabled || !aiClient.hasKey()) return ConversionOutcome.Unavailable(from)

        val cached = cacheMutex.withLock {
            cache["$from>${base.code}"]?.takeIf { nowMillis - it.atMillis < CACHE_TTL_MILLIS }
        }
        if (cached != null) return outcome(foreignMinor, from, cached.rateMicros, cached.note, base)

        val result = aiClient.chat(
            provider = settings.aiProvider,
            model = settings.aiModel,
            system = SYSTEM_PROMPT,
            user = userPrompt(from, base.code),
        )
        val quote = when (result) {
            is AiResult.Ok -> FxQuoteParser.parse(result.content, from, base.code)
            is AiResult.Failed -> null
        } ?: return ConversionOutcome.Unavailable(from)

        cacheMutex.withLock {
            cache["$from>${base.code}"] = CachedRate(quote.rateMicros, quote.note, nowMillis)
        }
        return outcome(foreignMinor, from, quote.rateMicros, quote.note, base)
    }

    /** Drop every cached rate — used when the key, provider or base currency changes. */
    suspend fun clearCache() = cacheMutex.withLock { cache.clear() }

    private fun outcome(
        foreignMinor: Long,
        fromCode: String,
        rateMicros: Long,
        note: String,
        base: AppCurrency,
    ): ConversionOutcome {
        // Last gate before a model-supplied number touches an amount. A rate outside the sane band, or
        // arithmetic that overflows, means "no conversion" — never a best-effort figure.
        if (!FxMath.isSaneRate(rateMicros)) return ConversionOutcome.Unavailable(fromCode)
        val baseMinor = runCatching { FxMath.convertMinor(foreignMinor, rateMicros) }.getOrNull()
            ?: return ConversionOutcome.Unavailable(fromCode)
        return ConversionOutcome.Converted(
            Conversion(
                baseMinor = baseMinor,
                foreignMinor = foreignMinor,
                foreignCode = fromCode,
                rateMicros = rateMicros,
                note = note,
            ),
        )
    }

    companion object {
        /** Six hours: long enough that a day's alerts cost one call, short enough to track a real move. */
        const val CACHE_TTL_MILLIS = 6 * 60 * 60 * 1000L

        /**
         * Look up a user-set fixed rate for [from] → [to] in the stored "MYR:INR=18.9" style map.
         * Exposed for testing; also used by the Settings screen to show what is currently pinned.
         */
        fun manualRateFor(manualRates: Map<String, Long>, from: String, to: String): Long? =
            manualRates["${from.uppercase()}:${to.uppercase()}"]?.takeIf { FxMath.isSaneRate(it) }

        private const val SYSTEM_PROMPT =
            "You are a currency conversion assistant inside a personal expense tracker. " +
                "Reply with ONE JSON object and nothing else — no prose, no code fence. " +
                "Schema: {\"from\":\"<ISO code>\",\"to\":\"<ISO code>\",\"rate\":<number>,\"note\":\"<one short sentence>\"}. " +
                "\"rate\" is how many units of the TO currency equal ONE unit of the FROM currency, as a plain " +
                "decimal number with no thousands separators and no currency symbol. " +
                "Give your best estimate of the current mid-market rate. " +
                "\"note\" must be one short sentence a non-expert can read, stating the rate you used and that " +
                "it is an estimate. Never invent a currency code you were not asked about."

        private fun userPrompt(from: String, to: String): String =
            "How many $to is 1 $from right now? Answer with the JSON object only."
    }
}
