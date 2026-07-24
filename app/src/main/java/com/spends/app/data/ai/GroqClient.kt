package com.spends.app.data.ai

import com.spends.app.data.backup.SecureKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of one Groq call. Everything else in the AI layer treats [Failed] as "behave like today". */
sealed interface GroqResult {
    data class Ok(val content: String) : GroqResult
    data class Failed(val reason: String) : GroqResult
}

/**
 * Minimal Groq client (OpenAI-compatible `chat/completions`), mirroring [com.spends.app.data.backup.DriveClient]'s
 * OkHttp house style. BYOK: the key comes from [SecureKeyStore] (encrypted, device-local). Short timeout, and
 * EVERYTHING is wrapped so a network/parse/HTTP error can only ever yield [GroqResult.Failed] — never a crash,
 * never a thrown exception into the caller (the whole AI layer is strictly additive and fail-closed, see
 * docs/AI-RESEARCH.md §2.4/§2.6). This client only ever produces TEXT; it can't touch money, the ledger, or a
 * balance.
 */
@Singleton
class GroqClient @Inject constructor(
    private val secureKeyStore: SecureKeyStore,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=UTF-8".toMediaType()

    /** True when a usable key is present (presence check; the call itself still fails closed if it can't decrypt). */
    fun hasKey(): Boolean = secureKeyStore.hasApiKey()

    /** One chat completion using the STORED key. [jsonObject] asks the model for strict JSON output. */
    suspend fun chat(
        model: String,
        system: String,
        user: String,
        jsonObject: Boolean,
        temperature: Double = 0.2,
        maxTokens: Int? = null,
    ): GroqResult {
        val key = secureKeyStore.apiKey() ?: return GroqResult.Failed("No API key set")
        return chatWithKey(key, model, system, user, jsonObject, temperature, maxTokens)
    }

    /**
     * Verify an explicit (possibly not-yet-saved) key with one tiny call — backs the Settings "Test key" button.
     * Returns [GroqResult.Ok] on any successful completion; the content is irrelevant.
     */
    suspend fun testKey(rawKey: String): GroqResult {
        val key = rawKey.trim()
        if (key.isEmpty()) return GroqResult.Failed("Key is empty")
        return chatWithKey(
            key = key,
            model = MODEL_CATEGORIZE,
            system = "You are a connection health check. Reply with the single word OK.",
            user = "ping",
            jsonObject = false,
            temperature = 0.0,
            maxTokens = 5,
        )
    }

    private suspend fun chatWithKey(
        key: String,
        model: String,
        system: String,
        user: String,
        jsonObject: Boolean,
        temperature: Double,
        maxTokens: Int?,
    ): GroqResult = withContext(Dispatchers.IO) {
        runCatching {
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user))
            val payload = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", temperature)
            if (jsonObject) payload.put("response_format", JSONObject().put("type", "json_object"))
            if (maxTokens != null) payload.put("max_tokens", maxTokens)

            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $key")
                .post(payload.toString().toRequestBody(jsonType))
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // Surface a compact reason (never the body — it can echo the key/errors). 401 = bad key.
                    GroqResult.Failed("HTTP ${resp.code}")
                } else {
                    val content = JSONObject(body)
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        ?.takeIf { it.isNotBlank() }
                    if (content == null) GroqResult.Failed("Empty response") else GroqResult.Ok(content)
                }
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e // never swallow structured cancellation
            GroqResult.Failed(e.message ?: "Network error")
        }
    }

    companion object {
        const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

        // Verified as current Groq PRODUCTION models (console.groq.com/docs/models, 2026-07). Both support
        // JSON mode. Kept as constants so a future rotation is a one-line swap; a decommissioned model just
        // 404s → GroqResult.Failed → fall back to today's behaviour.
        const val MODEL_CATEGORIZE = "llama-3.1-8b-instant"
        const val MODEL_INSIGHTS = "llama-3.3-70b-versatile"

        private const val TIMEOUT_SECONDS = 12L
    }
}
