package com.spends.app.data.ai

/**
 * Which AI service the user's own key belongs to (BYOK — see [AiClient]).
 *
 * Three providers rather than one because the key is the *user's*: forcing them to open an account with
 * a particular vendor to use a currency converter would be a worse feature than no feature. Anthropic
 * speaks its own Messages wire format; OpenAI and Groq share the OpenAI-compatible `chat/completions`
 * shape, so [openAiCompatible] is all that separates them in [AiClient].
 *
 * [defaultModel] is only a default — the user can type any model id their key can reach, so a provider
 * rotating its lineup is a one-field fix on the phone rather than an app update.
 */
enum class AiProvider(
    val label: String,
    val endpoint: String,
    val defaultModel: String,
    val openAiCompatible: Boolean,
    val keyHint: String,
    val consoleUrl: String,
) {
    ANTHROPIC(
        label = "Anthropic (Claude)",
        endpoint = "https://api.anthropic.com/v1/messages",
        defaultModel = "claude-opus-5",
        openAiCompatible = false,
        keyHint = "sk-ant-…",
        consoleUrl = "https://console.anthropic.com/settings/keys",
    ),
    OPENAI(
        label = "OpenAI",
        endpoint = "https://api.openai.com/v1/chat/completions",
        defaultModel = "gpt-4o-mini",
        openAiCompatible = true,
        keyHint = "sk-…",
        consoleUrl = "https://platform.openai.com/api-keys",
    ),
    GROQ(
        label = "Groq",
        endpoint = "https://api.groq.com/openai/v1/chat/completions",
        defaultModel = "llama-3.3-70b-versatile",
        openAiCompatible = true,
        keyHint = "gsk_…",
        consoleUrl = "https://console.groq.com/keys",
    ),
    ;

    companion object {
        val DEFAULT = ANTHROPIC

        fun fromName(name: String?): AiProvider =
            runCatching { valueOf(name.orEmpty()) }.getOrDefault(DEFAULT)
    }
}
