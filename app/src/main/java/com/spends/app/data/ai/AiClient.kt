package com.spends.app.data.ai

import com.spends.app.data.backup.SecureKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The outcome of one AI call. Every caller treats [Failed] as "behave exactly as if there were no key". */
sealed interface AiResult {
    data class Ok(val content: String) : AiResult
    data class Failed(val reason: String) : AiResult
}

/**
 * Minimal bring-your-own-key chat client, mirroring [com.spends.app.data.backup.DriveClient]'s OkHttp
 * house style. Speaks the three wire formats in [AiWire]: Anthropic's Messages API, the
 * OpenAI-compatible `chat/completions` that OpenAI and Groq both serve, and Google's `generateContent`.
 *
 * Three properties matter more than features here:
 *  - **The key is the user's.** It comes from [SecureKeyStore] (encrypted, device-local) and is sent to
 *    exactly one host — the provider the user chose. It is never logged and never put in a backup.
 *  - **Fail-closed, never crash.** Every network/parse/HTTP path is wrapped so the worst outcome is
 *    [AiResult.Failed]; nothing here can throw into a caller. A failed conversion leaves the transaction
 *    exactly as the rules-based parser produced it.
 *  - **It only ever produces TEXT.** This class cannot touch money, the ledger or a balance. The single
 *    number it can influence travels back through [com.spends.app.core.money.FxMath]'s sanity checks
 *    before it is allowed near an amount.
 *
 * Key writes go through [setKey]/[clearKey] so [hasKeyFlow] stays reactive — a gate re-evaluates the
 * moment a key is saved or removed, without waiting for the next data emission.
 */
@Singleton
open class AiClient @Inject constructor(
    private val secureKeyStore: SecureKeyStore,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=UTF-8".toMediaType()

    private val _hasKey = MutableStateFlow(secureKeyStore.hasApiKey())
    val hasKeyFlow: StateFlow<Boolean> = _hasKey

    /** True when a key is present (presence only; a call still fails closed if it can't be decrypted). */
    // `open` here and on [chat] purely so a test can stand in a deterministic double. Nothing in the app
    // subclasses AiClient; the alternative was an interface plus a Hilt binding for one call site, or a
    // negative-rate-cache fix that shipped with no test able to see it.
    open fun hasKey(): Boolean = secureKeyStore.hasApiKey()

    /** Store a key (encrypted, device-local) and update [hasKeyFlow]. Blank clears. */
    fun setKey(rawKey: String) {
        secureKeyStore.setApiKey(rawKey)
        _hasKey.value = secureKeyStore.hasApiKey()
    }

    /** Remove the stored key and update [hasKeyFlow]. */
    fun clearKey() {
        secureKeyStore.clearApiKey()
        _hasKey.value = false
    }

    /** One completion using the STORED key. */
    open suspend fun chat(
        provider: AiProvider,
        model: String,
        system: String,
        user: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): AiResult {
        val key = secureKeyStore.apiKey() ?: return AiResult.Failed("No API key set")
        return chatWithKey(key, provider, model, system, user, maxTokens)
    }

    /**
     * Verify an explicit (possibly not-yet-saved) key with one tiny call — backs Settings' "Test key"
     * button. Any successful completion is a pass; the content is irrelevant.
     */
    suspend fun testKey(provider: AiProvider, model: String, rawKey: String): AiResult {
        val key = rawKey.trim()
        if (key.isEmpty()) return AiResult.Failed("Key is empty")
        return chatWithKey(
            key = key,
            provider = provider,
            model = model.ifBlank { provider.defaultModel },
            system = "You are a connection health check. Reply with the single word OK.",
            user = "ping",
            maxTokens = DEFAULT_MAX_TOKENS,
        )
    }

    private suspend fun chatWithKey(
        key: String,
        provider: AiProvider,
        model: String,
        system: String,
        user: String,
        maxTokens: Int,
    ): AiResult = withContext(Dispatchers.IO) {
        runCatching {
            val effectiveModel = model.ifBlank { provider.defaultModel }
            val builder = Request.Builder().url(provider.endpointFor(effectiveModel))
            val payload = when (provider.wire) {
                AiWire.OPENAI_CHAT -> {
                    builder.header("Authorization", "Bearer $key")
                    openAiPayload(effectiveModel, system, user, maxTokens)
                }
                AiWire.ANTHROPIC_MESSAGES -> {
                    builder
                        .header("x-api-key", key)
                        .header("anthropic-version", ANTHROPIC_VERSION)
                    anthropicPayload(effectiveModel, system, user, maxTokens)
                }
                // Google names the model in the URL, so the body carries no model at all.
                AiWire.GEMINI_GENERATE_CONTENT -> {
                    builder.header("x-goog-api-key", key)
                    geminiPayload(system, user, maxTokens)
                }
            }
            val request = builder
                .post(payload.toString().toRequestBody(jsonType))
                .build()

            // Cooperatively cancellable: if the coroutine is cancelled (the user leaves the screen, the
            // capture is superseded), the in-flight HTTP call is aborted rather than orphaned.
            awaitResponse(client.newCall(request)).use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // A compact reason only — never the body, which can echo the key back or carry
                    // provider-side detail we have no business storing. 401 (400 on Google) = bad key,
                    // 429 = rate limited.
                    AiResult.Failed("HTTP ${resp.code}")
                } else {
                    val content = when (provider.wire) {
                        AiWire.OPENAI_CHAT -> parseOpenAi(body)
                        AiWire.ANTHROPIC_MESSAGES -> parseAnthropic(body)
                        AiWire.GEMINI_GENERATE_CONTENT -> parseGemini(body)
                    }
                    if (content == null) AiResult.Failed("Empty response") else AiResult.Ok(content)
                }
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e // never swallow structured cancellation
            AiResult.Failed(compactReason(e))
        }
    }

    /**
     * Anthropic Messages API. `system` is a TOP-LEVEL field, not a message role.
     *
     * Deliberately minimal: model, max_tokens, system, one user turn. No `thinking`, no `output_config`,
     * no beta flags — the model id is a free-text field the user can point at anything their key reaches,
     * and a request carrying a parameter their chosen model doesn't accept would 400 for a reason they
     * could not diagnose from a phone. [DEFAULT_MAX_TOKENS] leaves generous room because on models where
     * thinking is on by default those tokens are drawn from the same budget as the answer.
     */
    private fun anthropicPayload(model: String, system: String, user: String, maxTokens: Int): JSONObject =
        JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", user)),
            )

    /** OpenAI-compatible `chat/completions` — OpenAI and Groq both serve this shape. */
    private fun openAiPayload(model: String, system: String, user: String, maxTokens: Int): JSONObject =
        JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )

    /**
     * Google's `generateContent`. The model is already in the URL, so it is absent here; the system
     * prompt is its own `systemInstruction` object rather than a message role.
     *
     * **No `temperature`, deliberately.** The obvious thing to send is 0 — one right answer, quote it the
     * same way every time — and it is the wrong thing here: Google's own Gemini 3 guidance is to leave
     * temperature at its default, because these models reason across the sampled tokens and pinning it
     * down can send them looping or degrade the answer.
     *
     * What that costs is real and worth naming: each lookup is one SAMPLE, and [CurrencyAi]'s cache does
     * not average samples — it repeats whichever one it drew, and it is in-memory, so a background
     * capture in a cold process draws again. Two alerts a day apart can therefore sit in the ledger at
     * slightly different rates. That is acceptable only because of what surrounds it: every rate is
     * checked against [com.spends.app.core.money.FxMath.isSaneRate], printed on the face of the
     * transaction, labelled an estimate, and overridable by a rate the user pins themselves. A looping
     * or degraded answer would not be caught by any of that.
     *
     * [maxTokens] stays, as the same generous ceiling the other providers get and for the same reason —
     * on a model that thinks, the thinking is drawn from this budget before any answer text appears.
     */
    private fun geminiPayload(system: String, user: String, maxTokens: Int): JSONObject =
        JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))),
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user))),
                ),
            )
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))

    // The three parsers below are `internal` rather than private so unit tests can drive them with real
    // provider bodies. They are the step where a reply becomes text a rate is read out of, and every
    // interesting case in them — a thinking block first, a safety block, a truncated turn — is a body
    // shape, not a network condition. The alternative was a MockWebServer dependency to reach code that
    // needs no socket to be wrong.

    /**
     * Anthropic returns `content` as an ARRAY of blocks. Text is collected from every `text` block and
     * non-text blocks (thinking, tool use) are skipped — reading `content[0]` alone would come back empty
     * on any model that emits a thinking block first.
     */
    internal fun parseAnthropic(body: String): String? {
        val blocks = JSONObject(body).optJSONArray("content") ?: return null
        val sb = StringBuilder()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    internal fun parseOpenAi(body: String): String? =
        JSONObject(body)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }

    /**
     * Google returns `candidates[].content.parts[]`. Text is collected from every part and any part
     * flagged `thought` is skipped — the same reason [parseAnthropic] walks blocks rather than reading
     * the first one: on a thinking model the answer is not necessarily part zero.
     *
     * An empty result here is the normal shape of a refusal, a safety block, or a turn that hit the
     * output ceiling while still thinking. All three become [AiResult.Failed], which leaves the amount
     * in its original currency and flagged — the fail-closed path, not a guess.
     */
    internal fun parseGemini(body: String): String? {
        val parts = JSONObject(body)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            sb.append(part.optString("text"))
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /**
     * A short, safe description of a thrown failure — never the exception's own message.
     *
     * The [AiResult.Failed] built from an unsuccessful HTTP status is already careful never to carry the
     * response body. The thrown path was not: a 2xx that is not JSON — a hotel wifi captive portal's
     * login page, which is precisely this feature's traveller — makes `org.json` throw with the WHOLE
     * input appended to its message, and that string is rendered verbatim on the settings screen. Same
     * rule for both paths now: say what kind of thing went wrong, never what came back.
     */
    private fun compactReason(e: Throwable): String = when (e) {
        is JSONException -> "Unreadable reply"
        is InterruptedIOException -> "Timed out"
        is UnknownHostException -> "No connection"
        // A model id that cannot be put in a URL — only reachable on the one provider that puts it there.
        is IllegalArgumentException -> "That model name can't be used"
        is IOException -> "Network error"
        else -> "Network error"
    }

    /** Suspend on an OkHttp call, aborting it if the coroutine is cancelled. */
    private suspend fun awaitResponse(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Resuming an already-cancelled continuation is a safe no-op.
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"

        // Generous on purpose. The reply itself is a few dozen tokens of JSON, but on models where
        // thinking is enabled by default the thinking tokens are billed against this same ceiling — a
        // tight cap would truncate the turn before any text block was produced.
        const val DEFAULT_MAX_TOKENS = 4096

        private const val TIMEOUT_SECONDS = 20L
    }
}
