package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Bans regex syntax that the JVM accepts and Android does not.
 *
 * **The defect this exists to prevent.** `(?U)` is a Java-only inline flag. Android's regex engine is
 * ICU-backed and rejects it, throwing `PatternSyntaxException` *while the enclosing object's initialiser
 * runs* — so the failure is an `ExceptionInInitializerError` on first touch, not a parse miss. It landed
 * in `SmsParser` in v1.58.0 and silently killed live SMS capture on the phone for five releases: every
 * caller wraps parsing in `runCatching`, which swallowed the error, so bank texts simply stopped
 * becoming transactions with nothing logged anywhere. It then reached `SmsDebugLog` in v1.63.0 by
 * faithful copy, where it closed the app the instant the debug screen opened.
 *
 * **Why no other test could catch it.** Unit tests and Robolectric both run on the JVM, where `(?U)` is
 * perfectly valid — 195 assertions and a full render pass stayed green throughout. Only a real device
 * uses ICU. A source scan is therefore the right instrument: it checks the one thing the runtime under
 * test cannot.
 *
 * The portable spelling is `\p{Nd}` ("any Unicode decimal digit"), which both engines understand.
 */
class JvmOnlyRegexTest {

    @Test
    fun the_scanner_can_actually_see_what_it_bans() {
        // A clean result means nothing until the scanner has been shown to find a known defect — the
        // same discipline the newline scanner needed after it missed one by searching for LF in a CRLF
        // file. These are the exact two lines this test was written to have caught.
        assertThat(offendersIn("""    private val NUMERAL = Regex("(?U)\d[\d,]*(?:\.\d+)?")""")).isNotEmpty()
        assertThat(offendersIn("""val x = Regex("(?iU)foo")""")).isNotEmpty()

        // ...and that it does not fire on the fix, or on flags Android does support.
        assertThat(offendersIn("""    private val NUMERAL = Regex("\p{Nd}[\p{Nd},]*(?:\.\p{Nd}+)?")""")).isEmpty()
        assertThat(offendersIn("""val x = Regex("(?i)(inr|rs\.?)")""")).isEmpty()
    }

    @Test
    fun the_scan_actually_reaches_the_source_tree() {
        // Fail loud rather than reporting "no offenders" because the walk found nothing to walk.
        assertThat(kotlinSources()).isNotEmpty()
        assertThat(kotlinSources().map { it.name }).contains("SmsParser.kt")
    }

    @Test
    fun no_main_source_builds_a_regex_with_a_java_only_flag() {
        val offenders = kotlinSources().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> offendersIn(line).isNotEmpty() }
                .map { (index, line) -> "${file.path}:${index + 1}: ${line.trim()}" }
        }
        assertThat(offenders).isEmpty()
    }

    /**
     * `(?U)` is only a defect where a pattern is actually built, so the check is anchored to `Regex(` /
     * `Pattern.compile(`. Prose in a KDoc block naming the banned flag — including the warnings on the
     * two patterns this bug came from — is documentation, not a pattern, and must not fail the build.
     */
    @Test
    fun prose_naming_the_flag_is_not_treated_as_a_defect() {
        assertThat(offendersIn("""     * **Never write this as `(?U)\d`.** That inline flag is Java-only.""")).isEmpty()
    }

    /**
     * The fix changes how the rule is SPELLED, not what it masks. Both halves are asserted: that the
     * portable pattern agrees with the Java-only one it replaces, and that the samples actually exercise
     * the Unicode path — otherwise the agreement would be vacuously true on ASCII alone, which is the
     * shape of the seven dead tests this project has already had to throw away.
     */
    @Test
    fun the_portable_spelling_masks_exactly_what_the_java_only_flag_did() {
        val javaOnly = Regex("(?U)\\d[\\d,]*(?:\\.\\d+)?")
        val portable = Regex("\\p{Nd}[\\p{Nd},]*(?:\\.\\p{Nd}+)?")

        val samples = listOf(
            "Rs.5,59,393.44 debited from a/c XX4321",
            "INR 1234.00 spent on card ending 9012",
            "₹1234 credited",
            "OTP is 481920, valid 10 min",
            "UPI to coffeeday@ybl ref 123456789012",
            "१२३४ रुपये",
            "١٢٣٤ dirham",
            "no digits at all",
        )
        samples.forEach { sample ->
            assertThat(sample.replace(portable, "#")).isEqualTo(sample.replace(javaOnly, "#"))
        }

        // Non-vacuity: the Unicode digits really are being masked, not merely agreeing by both missing.
        assertThat("१२३४".replace(portable, "#")).isEqualTo("#")
        assertThat("١٢٣٤".replace(portable, "#")).isEqualTo("#")
        assertThat("Rs.5,59,393.44".replace(portable, "#")).isEqualTo("Rs.#")
    }

    private fun offendersIn(line: String): List<String> =
        if (BUILDS_A_PATTERN.containsMatchIn(line) && JAVA_ONLY_FLAG.containsMatchIn(line)) listOf(line) else emptyList()

    private fun kotlinSources(): List<File> =
        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun sourceRoot(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: error("Main source root not found from ${File("").absolutePath}")

    private companion object {
        val BUILDS_A_PATTERN = Regex("""Regex\(|Pattern\.compile\(""")

        /** Any inline flag group containing `U`, so `(?U)`, `(?iU)` and `(?Ui)` are all caught. */
        val JAVA_ONLY_FLAG = Regex("""\(\?[a-tv-zA-Z]*U[a-zA-Z]*\)""")
    }
}
