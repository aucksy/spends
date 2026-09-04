package com.spends.app.data.ai

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.spends.app.data.backup.SecureKeyStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading the answer out of each provider's reply.
 *
 * The failure this guards is not a crash — a null here is the fail-closed path and the amount simply
 * stays in its own currency. It is returning the WRONG text: a thinking block instead of the answer, or
 * half an answer because the reply arrived split across parts. Either produces a string that
 * [FxQuoteParser] then has to reject, and the whole conversion is lost for a reason no one can see.
 *
 * The bodies below are the real shapes each API returns, including the three that carry no answer at all:
 * a safety block, a turn that spent its whole budget thinking, and an error object.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AiClientParseTest {

    private val client = AiClient(SecureKeyStore(ApplicationProvider.getApplicationContext()))

    // ---- Google: candidates[].content.parts[] ----

    @Test fun gemini_reads_the_text_part() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"{\"rate\":18.9}"}],"role":"model"},""" +
            """"finishReason":"STOP"}]}"""
        assertThat(client.parseGemini(body)).isEqualTo("""{"rate":18.9}""")
    }

    @Test fun gemini_skips_a_thought_part_and_keeps_the_answer() {
        // Thinking is on by default across the Gemini 3 line, so the answer is not reliably part zero.
        val body = """{"candidates":[{"content":{"parts":[{"text":"working it out","thought":true},""" +
            """{"text":"{\"rate\":18.9}"}]}}]}"""
        assertThat(client.parseGemini(body)).isEqualTo("""{"rate":18.9}""")
    }

    @Test fun gemini_joins_text_split_across_parts() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"{\"rate\":"},{"text":"18.9}"}]}}]}"""
        assertThat(client.parseGemini(body)).isEqualTo("""{"rate":18.9}""")
    }

    @Test fun gemini_returns_null_when_the_candidate_carries_no_content() {
        // What a safety block looks like: a candidate with a finishReason and nothing to read.
        assertThat(client.parseGemini("""{"candidates":[{"finishReason":"SAFETY"}]}""")).isNull()
    }

    @Test fun gemini_returns_null_when_the_budget_went_entirely_on_thinking() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"hmm","thought":true}]},""" +
            """"finishReason":"MAX_TOKENS"}]}"""
        assertThat(client.parseGemini(body)).isNull()
    }

    @Test fun gemini_returns_null_for_an_error_body_or_no_candidates() {
        assertThat(client.parseGemini("""{"error":{"code":400,"message":"API key not valid"}}""")).isNull()
        assertThat(client.parseGemini("""{"promptFeedback":{"blockReason":"OTHER"}}""")).isNull()
        assertThat(client.parseGemini("{}")).isNull()
    }

    // ---- Anthropic: content[] blocks ----

    @Test fun anthropic_reads_a_text_block() {
        val body = """{"content":[{"type":"text","text":"{\"rate\":18.9}"}]}"""
        assertThat(client.parseAnthropic(body)).isEqualTo("""{"rate":18.9}""")
    }

    @Test fun anthropic_skips_a_thinking_block_first() {
        val body = """{"content":[{"type":"thinking","thinking":"working it out"},""" +
            """{"type":"text","text":"{\"rate\":18.9}"}]}"""
        assertThat(client.parseAnthropic(body)).isEqualTo("""{"rate":18.9}""")
    }

    @Test fun anthropic_returns_null_when_there_is_no_text_block() {
        assertThat(client.parseAnthropic("""{"content":[{"type":"thinking","thinking":"…"}]}""")).isNull()
        assertThat(client.parseAnthropic("""{"type":"error","error":{"type":"invalid_request_error"}}""")).isNull()
    }

    // ---- OpenAI / Groq: choices[].message.content ----

    @Test fun openai_reads_the_message_content() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"{\"rate\":18.9}"}}]}"""
        assertThat(client.parseOpenAi(body)).isEqualTo("""{"rate":18.9}""")
    }

    @Test fun openai_returns_null_for_an_empty_or_absent_message() {
        assertThat(client.parseOpenAi("""{"choices":[{"message":{"content":""}}]}""")).isNull()
        assertThat(client.parseOpenAi("""{"choices":[]}""")).isNull()
        assertThat(client.parseOpenAi("""{"error":{"message":"Incorrect API key provided"}}""")).isNull()
    }
}
