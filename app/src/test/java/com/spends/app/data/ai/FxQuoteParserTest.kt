package com.spends.app.data.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The trust boundary: text written by a language model becoming a number that scales real money.
 *
 * The failure this suite exists to prevent is not a crash — every caller already treats null as "leave
 * the amount alone". It is a *plausible-looking wrong rate* getting through: an answer about the wrong
 * currency pair, a rate of zero, a value so large it is obviously a misunderstanding. Those are the
 * cases that would silently corrupt a ledger, so they get the most tests.
 */
class FxQuoteParserTest {

    private fun quote(raw: String) = FxQuoteParser.parse(raw, "MYR", "INR")

    // ---- the happy path ----

    @Test fun reads_a_bare_json_object() {
        val q = quote("""{"from":"MYR","to":"INR","rate":18.9,"note":"About 18.9 rupees per ringgit."}""")
        assertThat(q).isNotNull()
        assertThat(q!!.rateMicros).isEqualTo(18_900_000)
        assertThat(q.fromCode).isEqualTo("MYR")
        assertThat(q.toCode).isEqualTo("INR")
        assertThat(q.note).isEqualTo("About 18.9 rupees per ringgit.")
    }

    @Test fun reads_an_object_inside_a_code_fence() {
        val q = quote("```json\n{\"from\":\"MYR\",\"to\":\"INR\",\"rate\":18.9}\n```")
        assertThat(q?.rateMicros).isEqualTo(18_900_000)
    }

    @Test fun reads_an_object_buried_in_prose() {
        // Models add a preamble no matter how firmly the system prompt forbids it.
        val q = quote("""Sure! Here you go: {"from":"MYR","to":"INR","rate":18.9} — hope that helps.""")
        assertThat(q?.rateMicros).isEqualTo(18_900_000)
    }

    @Test fun a_rate_quoted_as_a_string_still_works() {
        // Perfectly good answers get returned this way; rejecting them would fail for no reason.
        assertThat(quote("""{"rate":"18.9"}""")?.rateMicros).isEqualTo(18_900_000)
        assertThat(quote("""{"rate":"18,9"}""")?.rateMicros).isNull() // a comma is not a decimal point here
    }

    @Test fun a_missing_pair_is_tolerated_and_filled_from_what_we_asked() {
        val q = quote("""{"rate":18.9}""")
        assertThat(q).isNotNull()
        assertThat(q!!.fromCode).isEqualTo("MYR")
        assertThat(q.toCode).isEqualTo("INR")
        // With no note supplied, one is generated that still states the rate.
        assertThat(q.note).contains("18.90")
    }

    @Test fun braces_inside_the_note_do_not_truncate_the_object() {
        val q = quote("""{"rate":18.9,"note":"Rates move {a lot} lately."}""")
        assertThat(q?.note).isEqualTo("Rates move {a lot} lately.")
    }

    @Test fun an_escaped_quote_inside_the_note_does_not_end_the_string_early() {
        val q = quote("""{"rate":18.9,"note":"The \"mid-market\" rate."}""")
        assertThat(q?.note).isEqualTo("""The "mid-market" rate.""")
    }

    // ---- the rejections that matter ----

    @Test fun an_answer_about_a_different_pair_is_refused() {
        // The dangerous one: a real, correct rate — for the wrong currencies. Applying a USD->INR rate
        // to a ringgit amount would produce a wrong figure that looks entirely reasonable.
        assertThat(quote("""{"from":"USD","to":"INR","rate":83.2}""")).isNull()
        assertThat(quote("""{"from":"MYR","to":"USD","rate":0.22}""")).isNull()
    }

    @Test fun the_pair_check_is_case_insensitive() {
        assertThat(quote("""{"from":"myr","to":"inr","rate":18.9}""")?.rateMicros).isEqualTo(18_900_000)
    }

    @Test fun an_impossible_rate_is_refused() {
        assertThat(quote("""{"rate":0}""")).isNull()
        assertThat(quote("""{"rate":-18.9}""")).isNull()
        assertThat(quote("""{"rate":1e30}""")).isNull()
    }

    @Test fun a_missing_or_unparseable_rate_is_refused() {
        assertThat(quote("""{"from":"MYR","to":"INR"}""")).isNull()
        assertThat(quote("""{"rate":"about eighteen"}""")).isNull()
        assertThat(quote("""{"rate":null}""")).isNull()
    }

    @Test fun a_reply_that_is_not_json_at_all_is_refused() {
        assertThat(quote("I'm sorry, I can't help with that.")).isNull()
        assertThat(quote("")).isNull()
        assertThat(quote("   ")).isNull()
        assertThat(FxQuoteParser.parse(null, "MYR", "INR")).isNull()
    }

    @Test fun a_truncated_object_is_refused() {
        // A response cut off by max_tokens must not half-parse into something usable.
        assertThat(quote("""{"from":"MYR","to":"INR","rate":18.9""")).isNull()
    }

    @Test fun an_absurdly_long_note_is_dropped_but_the_rate_survives() {
        // A model that ignores "one short sentence" shouldn't cost us the conversion — nor should it get
        // to write a paragraph into the transaction list.
        val q = quote("""{"rate":18.9,"note":"${"x".repeat(500)}"}""")
        assertThat(q).isNotNull()
        assertThat(q!!.rateMicros).isEqualTo(18_900_000)
        assertThat(q.note).doesNotContain("xxxx")
    }

    // ---- the extractor itself ----

    @Test fun the_object_extractor_finds_the_first_complete_object() {
        val obj = FxQuoteParser.extractJsonObject("""noise {"a":{"b":1}} trailing {"c":2}""")
        assertThat(obj).isNotNull()
        assertThat(obj!!.has("a")).isTrue()
        assertThat(obj.has("c")).isFalse()
    }

    @Test fun the_object_extractor_returns_null_when_there_is_nothing_to_find() {
        assertThat(FxQuoteParser.extractJsonObject("no braces here")).isNull()
        assertThat(FxQuoteParser.extractJsonObject(null)).isNull()
    }
}
