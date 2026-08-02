package com.spends.app.ui.capture

import com.spends.app.data.capture.NotificationDebugLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification report's redaction allow-list — the single gate between a PERSONAL CHAT's message
 * body and the clipboard, and until now the only privacy control in this feature with no test at all.
 *
 * A watched app is Google Messages, so an entry whose sender did not resolve to a bank is very likely a
 * private conversation, and "Copy report" exists to be pasted into a chat window. Ten separate mutations
 * of that gate — emptying the set, inverting the test, removing any one member, never withholding —
 * left the entire suite green.
 *
 * This is also the design `SmsDebugLog`'s own KDoc calls the WEAKER of the two: the SMS side refuses to
 * STORE what it would have to redact, so its report needs no allow-list to be kept correct forever.
 * This side stores everything and redacts on the way out, which is exactly the shape that rots when a
 * new outcome is added and nobody classifies it. Hence the partition assertion at the end.
 */
class NotificationReportRedactionTest {

    private fun entry(
        outcome: NotificationDebugLog.Outcome,
        text: String = "send me 5000, my a/c is 1234567890",
    ) = NotificationDebugLog.Entry(
        timeMillis = 1_000L,
        packageName = "com.google.android.apps.messaging",
        title = "Mum",
        text = text,
        bigText = text,
        messageSenders = listOf("Mum"),
        outcome = outcome,
        detail = null,
    )

    /**
     * Every outcome reachable WITHOUT the sender resolving to a bank must be redacted. `candidates()`
     * drops anything whose sender does not resolve, so the outcomes below are precisely those recorded
     * before that filter — and precisely those whose body may be a private conversation.
     */
    @Test
    fun `the redaction set is exactly the outcomes reachable without a recognised bank`() {
        val expected = setOf(
            NotificationDebugLog.Outcome.SENDER_NOT_RECOGNISED,
            NotificationDebugLog.Outcome.NO_READABLE_TEXT,
            NotificationDebugLog.Outcome.MESSAGES_SHADOWED_BIG_TEXT,
            NotificationDebugLog.Outcome.SKIPPED_SHAPE,
            // Recorded before the sender filter like the four above. The body is usually just Android's
            // placeholder, but `looksRedacted` matches a substring, so the entry can still carry whatever
            // text surrounds it — and no bank check ever ran on this path.
            NotificationDebugLog.Outcome.REDACTED_BY_ANDROID,
        )
        // Partitioned over the WHOLE enum, so a new outcome must be classified deliberately rather than
        // defaulting into the exported half by being forgotten.
        val redacted = NotificationDebugLog.Outcome.entries.filter { it in NotificationDebugViewModel.REDACTED_OUTCOMES }
        val exported = NotificationDebugLog.Outcome.entries.filter { it !in NotificationDebugViewModel.REDACTED_OUTCOMES }

        assertEquals(expected, redacted.toSet())
        assertEquals(
            "every other outcome is reachable only after the sender resolved to a bank",
            setOf(
                NotificationDebugLog.Outcome.TOO_OLD,
                NotificationDebugLog.Outcome.NOT_A_TRANSACTION,
                NotificationDebugLog.Outcome.DUPLICATE,
                NotificationDebugLog.Outcome.QUEUED,
                NotificationDebugLog.Outcome.PROMPTED,
            ),
            exported.toSet(),
        )
    }

    @Test
    fun `a personal chat's body never reaches the report`() {
        val secret = "send me 5000, my a/c is 1234567890"
        NotificationDebugViewModel.REDACTED_OUTCOMES.forEach { outcome ->
            val line = NotificationDebugViewModel.bodyFor(entry(outcome, secret).text, redact = true)
            assertTrue("$outcome must withhold the body", !line.contains(secret))
            assertTrue("$outcome must say it was withheld", line.contains("withheld"))
        }
    }

    /**
     * The other half. A recognised bank alert's text is what makes a parse failure diagnosable, so it
     * must survive — otherwise "redact everything" would pass the test above and destroy the screen.
     */
    @Test
    fun `a recognised bank alert's text does reach the report`() {
        val alert = "Rs.500 debited from a/c XX1234"
        val line = NotificationDebugViewModel.bodyFor(alert, redact = false)

        assertEquals(alert, line)
    }
}
