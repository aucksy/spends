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
import org.json.JSONObject
import java.io.IOException
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
 * house style. Speaks two wire formats: Anthropic's Messages API, and the OpenAI-compatible
 * `chat/completions` that OpenAI and Groq both serve (see [AiProvider]).
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
class AiClient @Inject constructor(
    private val secureKeyStore: SecureKeyStore,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=UTF-8".toMediaType()

    private val _hasKey = MutableStateFlow(secureKeyStore.hasApiKey())
    val hasKeyFlow: StateFlow<Boolean> = _hasKey

    /** True when a key is present (presence only; a call still fails closed if it can't be decrypted). */
    fun hasKey(): Boolean = secureKeyStore.hasApiKey()

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
    suspend fun chat(
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
            val builder = Request.Builder().url(provider.endpoint)
            val payload = if (provider.openAiCompatible) {
                builder.header("Authorization", "Bearer $key")
                openAiPayload(effectiveModel, system, user, maxTokens)
            } else {
                builder
                    .header("x-api-key", key)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                anthropicPayload(effectiveModel, system, user, maxTokens)
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
                    // provider-side detail we have no business storing. 401 = bad key, 429 = rate limited.
                    AiResult.Failed("HTTP ${resp.code}")
                } else {
                    val content = if (provider.openAiCompatible) {
                        parseOpenAi(body)
                    } else {
                        parseAnthropic(body)
                    }
                    if (content == null) AiResult.Failed("Empty response") else AiResult.Ok(content)
                }
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e // never swallow structured cancellation
            AiResult.Failed(e.message ?: "Network error")
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
     * Anthropic returns `content` as an ARRAY of blocks. Text is collected from every `text` block and
     * non-text blocks (thinking, tool use) are skipped — reading `content[0]` alone would come back empty
     * on any model that emits a thinking block first.
     */
    private fun parseAnthropic(body: String): String? {
        val blocks = JSONObject(body).optJSONArray("content") ?: return null
        val sb = StringBuilder()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun parseOpenAi(body: String): String? =
        JSONObject(body)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }

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
