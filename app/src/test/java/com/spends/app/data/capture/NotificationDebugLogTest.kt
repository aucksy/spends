package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import com.spends.app.data.capture.NotificationCapture.RawMessage
import org.junit.Test

/**
 * TEMPORARY diagnostic support (remove with [NotificationDebugLog]): the in-memory recorder stays
 * bounded and honest, and [NotificationCapture.diagnose] names the right reason for a silent drop.
 */
class NotificationDebugLogTest {

    private fun entry(pkg: String = "com.truecaller", outcome: NotificationDebugLog.Outcome) =
        NotificationDebugLog.Entry(
            timeMillis = 1L,
            packageName = pkg,
            title = null,
            text = null,
            bigText = null,
            messageSenders = emptyList(),
            outcome = outcome,
            detail = null,
        )

    @Test fun counts_every_notification_but_keeps_only_package_names() {
        val log = NotificationDebugLog()
        repeat(3) { log.recordSeen("com.truecaller") }
        log.recordSeen("com.whatsapp")

        val s = log.state.value
        assertThat(s.totalSeen).isEqualTo(4)
        assertThat(s.packageCounts).containsExactly("com.truecaller" to 3, "com.whatsapp" to 1).inOrder()
    }

    @Test fun entries_are_newest_first_and_bounded() {
        val log = NotificationDebugLog()
        repeat(70) { log.record(entry(outcome = NotificationDebugLog.Outcome.QUEUED)) }
        log.record(entry(pkg = "com.newest", outcome = NotificationDebugLog.Outcome.PROMPTED))

        val s = log.state.value
        assertThat(s.entries).hasSize(60)
        assertThat(s.entries.first().packageName).isEqualTo("com.newest")
    }

    @Test fun connection_state_and_clear_behave() {
        val log = NotificationDebugLog()
        log.setConnected(true, now = 500L)
        log.recordSeen("com.truecaller")
        log.record(entry(outcome = NotificationDebugLog.Outcome.QUEUED))
        assertThat(log.state.value.connected).isTrue()

        log.clear()
        val s = log.state.value
        assertThat(s.entries).isEmpty()
        assertThat(s.packageCounts).isEmpty()
        assertThat(s.totalSeen).isEqualTo(0)
        // Clearing the record must NOT lie about the live connection.
        assertThat(s.connected).isTrue()
        assertThat(s.lastConnectedAt).isEqualTo(500L)
    }

    // ---- diagnose(): why nothing survived ----

    @Test fun diagnose_reports_none_when_a_candidate_survives() {
        val d = NotificationCapture.diagnose(
            title = "Axis Bank", text = null, bigText = null, conversationTitle = null,
            messages = listOf(RawMessage("Axis Bank", "INR 499.00 debited A/c no. XX5678", 1L)),
            postTime = 100L,
        )
        assertThat(d.rejection).isEqualTo(NotificationCapture.Rejection.NONE)
    }

    @Test fun diagnose_flags_an_unmapped_bank_name_and_shows_what_it_tried() {
        val d = NotificationCapture.diagnose(
            title = "Messages", text = null, bigText = null,
            conversationTitle = "HDFC Bank InstaAlerts",
            messages = listOf(RawMessage(null, "Rs.250 debited from a/c XX1234", 1L)),
            postTime = 100L,
        )
        assertThat(d.rejection).isEqualTo(NotificationCapture.Rejection.SENDER_NOT_RECOGNISED)
        assertThat(d.sendersTried).containsExactly("HDFC Bank InstaAlerts")
    }

    @Test fun diagnose_flags_the_rcs_no_text_case() {
        // A rich business card with a custom layout: known bank, but no text anywhere to parse.
        val noText = NotificationCapture.diagnose(
            title = "Axis Bank", text = null, bigText = null, conversationTitle = null,
            messages = emptyList(), postTime = 100L,
        )
        assertThat(noText.rejection).isEqualTo(NotificationCapture.Rejection.NO_READABLE_TEXT)

        // Same verdict when a MessagingStyle carries messages that are all textless AND there is no
        // body anywhere else to fall back to.
        val blankMessages = NotificationCapture.diagnose(
            title = "Axis Bank", text = null, bigText = null, conversationTitle = null,
            messages = listOf(RawMessage("Axis Bank", "  ", 1L), RawMessage("Axis Bank", null, 2L)),
            postTime = 100L,
        )
        assertThat(blankMessages.rejection).isEqualTo(NotificationCapture.Rejection.NO_READABLE_TEXT)
    }

    /**
     * The trap this guards: textless MessagingStyle messages make `candidates()` commit to the messages
     * branch and never try bigText, even though bigText holds the whole alert. That is OUR bug, and it
     * must NOT be reported as the unfixable "no readable text" RCS limit — doing so would close the
     * investigation on the one hypothesis that can actually be fixed.
     */
    @Test fun diagnose_separates_our_shadowing_bug_from_the_unfixable_rcs_case() {
        val d = NotificationCapture.diagnose(
            title = "Axis Bank",
            text = null,
            bigText = "INR 499.00 debited A/c no. XX5678 21-06-2026",
            conversationTitle = null,
            messages = listOf(RawMessage("Axis Bank", "  ", 1L)),
            postTime = 100L,
        )
        assertThat(d.rejection).isEqualTo(NotificationCapture.Rejection.MESSAGES_SHADOWED_BIG_TEXT)
        assertThat(d.rejection).isNotEqualTo(NotificationCapture.Rejection.NO_READABLE_TEXT)
        assertThat(d.sendersTried).containsExactly("Axis Bank")
    }

    @Test fun diagnose_falls_back_to_a_placeholder_when_the_title_is_blank() {
        val d = NotificationCapture.diagnose(
            title = "   ", text = "Rs.250 debited", bigText = null, conversationTitle = null,
            messages = emptyList(), postTime = 100L,
        )
        assertThat(d.rejection).isEqualTo(NotificationCapture.Rejection.SENDER_NOT_RECOGNISED)
        assertThat(d.sendersTried).containsExactly("(no title)")
    }

    @Test fun diagnose_uses_the_title_for_a_plain_notification() {
        val d = NotificationCapture.diagnose(
            title = "Payment successful", text = "You paid Rs.250 to Chai Point", bigText = null,
            conversationTitle = null, messages = emptyList(), postTime = 100L,
        )
        assertThat(d.rejection).isEqualTo(NotificationCapture.Rejection.SENDER_NOT_RECOGNISED)
        assertThat(d.sendersTried).containsExactly("Payment successful")
    }
}
