package com.spends.app.data.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Provider wiring — the parts a wrong value breaks silently rather than loudly.
 *
 * Google is the only provider that names the model in the URL, which means a model id the user typed is
 * suddenly part of an address rather than a JSON field. A mistake there does not throw: it produces a
 * well-formed request to the wrong place and comes back a 404 the user cannot diagnose from a phone.
 * That is what these tests pin.
 */
class AiProviderTest {

    @Test fun a_stored_provider_name_round_trips() {
        AiProvider.entries.forEach { provider ->
            assertThat(AiProvider.fromName(provider.name)).isEqualTo(provider)
        }
    }

    @Test fun an_unknown_or_missing_provider_name_falls_back_to_the_default() {
        // A settings value written by a newer build, or none at all, must never crash the settings screen.
        assertThat(AiProvider.fromName("GEMINI_ULTRA_9000")).isEqualTo(AiProvider.DEFAULT)
        assertThat(AiProvider.fromName(null)).isEqualTo(AiProvider.DEFAULT)
        assertThat(AiProvider.fromName("")).isEqualTo(AiProvider.DEFAULT)
    }

    @Test fun every_provider_has_a_usable_default_model() {
        AiProvider.entries.forEach { provider ->
            assertThat(provider.defaultModel).isNotEmpty()
        }
    }

    // ---- the model-in-the-URL cases, which only Google has ----

    @Test fun google_puts_the_model_in_the_path() {
        assertThat(AiProvider.GEMINI.endpointFor("gemini-2.5-flash"))
            .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
    }

    @Test fun a_blank_model_falls_back_to_the_default() {
        val expected =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "${AiProvider.GEMINI.defaultModel}:generateContent"
        assertThat(AiProvider.GEMINI.endpointFor("")).isEqualTo(expected)
        assertThat(AiProvider.GEMINI.endpointFor("   ")).isEqualTo(expected)
    }

    @Test fun a_pasted_models_prefix_is_stripped_rather_than_doubled() {
        // Google's own docs name a model "models/gemini-…"; pasted verbatim that would build
        // /v1beta/models/models/gemini-… and 404.
        assertThat(AiProvider.GEMINI.endpointFor("models/gemini-2.5-flash"))
            .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
    }

    @Test fun surrounding_whitespace_from_a_paste_is_trimmed() {
        assertThat(AiProvider.GEMINI.endpointFor("  gemini-2.5-flash  "))
            .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
    }

    @Test fun a_prefix_with_nothing_after_it_still_yields_the_default() {
        val expected =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "${AiProvider.GEMINI.defaultModel}:generateContent"
        assertThat(AiProvider.GEMINI.endpointFor("models/")).isEqualTo(expected)
    }

    @Test fun the_other_providers_ignore_the_model_and_keep_a_fixed_endpoint() {
        AiProvider.entries.filter { it.wire != AiWire.GEMINI_GENERATE_CONTENT }.forEach { provider ->
            val withModel = provider.endpointFor("anything-at-all")
            assertThat(withModel).isEqualTo(provider.endpointFor(""))
            assertThat(withModel).doesNotContain("anything-at-all")
        }
    }

    @Test fun no_endpoint_ever_leaves_an_unreplaced_placeholder() {
        AiProvider.entries.forEach { provider ->
            assertThat(provider.endpointFor("")).doesNotContain("{model}")
            assertThat(provider.endpointFor("some-model")).doesNotContain("{model}")
        }
    }

    @Test fun every_endpoint_is_https() {
        // The key travels in a header on every one of these calls; plain HTTP would put it on the wire.
        AiProvider.entries.forEach { provider ->
            assertThat(provider.endpointFor("")).startsWith("https://")
        }
    }
}
