package com.spends.app.data.ai

/**
 * Where the model id goes in an endpoint template — only Google's contains it. A `const` so it is
 * folded in at compile time, which is what lets an enum entry above use it in its own constructor.
 */
private const val MODEL_TOKEN = "{model}"

/**
 * The HTTP shape a provider speaks. Three formats rather than a boolean because that is genuinely how
 * many there are: a two-valued flag could not say which of three payload builders and parsers to use,
 * and lumping Gemini in with the OpenAI pair would be a lie that costs the request its parameters.
 */
enum class AiWire {
    /** Anthropic Messages API — `system` is a top-level field, `content` comes back as an array of blocks. */
    ANTHROPIC_MESSAGES,

    /** OpenAI's `chat/completions`, which Groq serves too — `system` is a message role. */
    OPENAI_CHAT,

    /** Google's `generateContent` — the model is in the URL, not the body, and the reply is `candidates`. */
    GEMINI_GENERATE_CONTENT,
}

/**
 * Which AI service the user's own key belongs to (BYOK — see [AiClient]).
 *
 * Four providers rather than one because the key is the *user's*: forcing them to open an account with a
 * particular vendor to use a currency converter would be a worse feature than no feature. Each speaks one
 * of the three wire formats in [AiWire], which is all that separates them in [AiClient].
 *
 * Google is on its own `generateContent` endpoint rather than the OpenAI-compatible one it also serves.
 * That compatibility layer is documented as beta, documents only a handful of the fields it accepts, and
 * *silently ignores* the rest — the output ceiling among them. A parameter that is quietly dropped rather
 * than rejected is invisible until the day it matters, so this takes the first-party endpoint, where what
 * is sent is what applies and the reply has a shape Google actually documents.
 *
 * [defaultModel] is only a default — the user can type any model id their key can reach, so a provider
 * rotating its lineup is a one-field fix on the phone rather than an app update.
 */
enum class AiProvider(
    val label: String,
    private val endpointTemplate: String,
    val defaultModel: String,
    val wire: AiWire,
    val keyHint: String,
    val consoleUrl: String,
) {
    ANTHROPIC(
        label = "Anthropic (Claude)",
        endpointTemplate = "https://api.anthropic.com/v1/messages",
        defaultModel = "claude-opus-5",
        wire = AiWire.ANTHROPIC_MESSAGES,
        keyHint = "sk-ant-…",
        consoleUrl = "https://console.anthropic.com/settings/keys",
    ),
    OPENAI(
        label = "OpenAI",
        endpointTemplate = "https://api.openai.com/v1/chat/completions",
        defaultModel = "gpt-4o-mini",
        wire = AiWire.OPENAI_CHAT,
        keyHint = "sk-…",
        consoleUrl = "https://platform.openai.com/api-keys",
    ),
    GROQ(
        label = "Groq",
        endpointTemplate = "https://api.groq.com/openai/v1/chat/completions",
        defaultModel = "llama-3.3-70b-versatile",
        wire = AiWire.OPENAI_CHAT,
        keyHint = "gsk_…",
        consoleUrl = "https://console.groq.com/keys",
    ),

    /**
     * Google AI Studio keys. [defaultModel] is the flash-lite tier on purpose: this feature asks one
     * one-line question ("how many rupees is a ringgit?"), so the cheapest, fastest model that thinks
     * only minimally is the right one — a heavier model would spend thinking tokens against the same
     * output ceiling and answer no better.
     */
    GEMINI(
        label = "Google (Gemini)",
        endpointTemplate = "https://generativelanguage.googleapis.com/v1beta/models/${MODEL_TOKEN}:generateContent",
        defaultModel = "gemini-3.5-flash-lite",
        wire = AiWire.GEMINI_GENERATE_CONTENT,
        keyHint = "AIza…",
        consoleUrl = "https://aistudio.google.com/app/apikey",
    ),
    ;

    /**
     * Where to POST for [model]. Only Google puts the model in the path; for everyone else the template
     * has no placeholder and the argument is ignored.
     *
     * A blank model means "use the default", matching [AiClient]. The `models/` prefix is stripped
     * because that is how Google's own docs name a model in prose and in most SDKs, and pasting it
     * verbatim would otherwise build `/v1beta/models/models/gemini-…` and 404 for no visible reason.
     */
    fun endpointFor(model: String): String {
        val id = model.trim().removePrefix("models/").trim().ifBlank { defaultModel }
        return endpointTemplate.replace(MODEL_TOKEN, id)
    }

    companion object {
        val DEFAULT = ANTHROPIC

        fun fromName(name: String?): AiProvider =
            runCatching { valueOf(name.orEmpty()) }.getOrDefault(DEFAULT)
    }
}
