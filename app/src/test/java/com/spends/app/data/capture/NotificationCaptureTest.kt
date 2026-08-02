package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import com.spends.app.data.capture.NotificationCapture.RawMessage
import org.junit.Test

/** Phase 4: pure notification payload → parse candidates (the listener's testable core). */
class NotificationCaptureTest {

    private val postTime = 1_700_000_500_000L

    // ---- MessagingStyle notifications (Google Messages / Truecaller chats) ----

    @Test fun messaging_style_surfaces_every_financial_message_with_own_timestamp() {
        val out = NotificationCapture.candidates(
            title = "AX-AXISBK",
            text = "collapsed line",
            bigText = null,
            conversationTitle = null,
            messages = listOf(
                RawMessage("AX-AXISBK", "INR 499.00 debited A/c no. XX5678", 1_700_000_100_000L),
                RawMessage("AX-AXISBK", "INR 120.00 debited A/c no. XX5678", 1_700_000_200_000L),
            ),
            postTime = postTime,
        )
        assertThat(out).hasSize(2)
        assertThat(out[0].sender).isEqualTo("AX-AXISBK")
        assertThat(out[0].timestamp).isEqualTo(1_700_000_100_000L)
        assertThat(out[1].timestamp).isEqualTo(1_700_000_200_000L)
    }

    @Test fun rcs_friendly_sender_is_canonicalised() {
        val out = NotificationCapture.candidates(
            title = "Axis Bank",
            text = null,
            bigText = null,
            conversationTitle = null,
            messages = listOf(RawMessage("Axis Bank", "INR 499.00 debited A/c no. XX5678", 1L)),
            postTime = postTime,
        )
        assertThat(out).hasSize(1)
        assertThat(out[0].sender).isEqualTo("AXISBK")
    }

    @Test fun personal_chat_messages_are_dropped() {
        val out = NotificationCapture.candidates(
            title = "Mom",
            text = "hi",
            bigText = null,
            conversationTitle = null,
            messages = listOf(RawMessage("Mom", "send me 500", 1L)),
            postTime = postTime,
        )
        assertThat(out).isEmpty()
    }

    @Test fun blank_message_sender_falls_back_to_conversation_title_then_title() {
        val viaConversation = NotificationCapture.candidates(
            title = "Messages",
            text = null,
            bigText = null,
            conversationTitle = "IDFC FIRST Bank",
            messages = listOf(RawMessage(null, "Your A/C XX1234 is debited by INR 850.00", 1L)),
            postTime = postTime,
        )
        assertThat(viaConversation).hasSize(1)
        assertThat(viaConversation[0].sender).isEqualTo("IDFCFB")

        val viaTitle = NotificationCapture.candidates(
            title = "IDFC FIRST Bank",
            text = null,
            bigText = null,
            conversationTitle = null,
            messages = listOf(RawMessage("", "Your A/C XX1234 is debited by INR 850.00", 1L)),
            postTime = postTime,
        )
        assertThat(viaTitle).hasSize(1)
        assertThat(viaTitle[0].sender).isEqualTo("IDFCFB")
    }

    @Test fun blank_message_text_is_skipped() {
        val out = NotificationCapture.candidates(
            title = "AX-AXISBK",
            text = null,
            bigText = null,
            conversationTitle = null,
            messages = listOf(
                RawMessage("AX-AXISBK", "   ", 1L),
                RawMessage("AX-AXISBK", null, 2L),
                RawMessage("AX-AXISBK", "INR 100.00 debited A/c no. XX5678", 3L),
            ),
            postTime = postTime,
        )
        assertThat(out).hasSize(1)
        assertThat(out[0].body).isEqualTo("INR 100.00 debited A/c no. XX5678")
    }

    @Test fun zero_timestamp_falls_back_to_post_time() {
        val out = NotificationCapture.candidates(
            title = "AX-AXISBK",
            text = null,
            bigText = null,
            conversationTitle = null,
            messages = listOf(RawMessage("AX-AXISBK", "INR 100.00 debited", 0L)),
            postTime = postTime,
        )
        assertThat(out.single().timestamp).isEqualTo(postTime)
    }

    // ---- plain notifications (no MessagingStyle) ----

    @Test fun plain_notification_uses_title_as_sender_and_prefers_big_text() {
        val out = NotificationCapture.candidates(
            title = "AX-AXISBK",
            text = "INR 499.00 debited A/c no. XX5678 (truncated…",
            bigText = "INR 499.00 debited A/c no. XX5678 21-06-2026 UPI/P2A/000000/<PAYEE>",
            conversationTitle = null,
            messages = emptyList(),
            postTime = postTime,
        )
        assertThat(out).hasSize(1)
        assertThat(out[0].body).contains("UPI/P2A")
        assertThat(out[0].timestamp).isEqualTo(postTime)
    }

    @Test fun plain_notification_from_unknown_title_is_dropped() {
        val out = NotificationCapture.candidates(
            title = "Payment successful",
            text = "You paid ₹250 to Chai Point",
            bigText = null,
            conversationTitle = null,
            messages = emptyList(),
            postTime = postTime,
        )
        assertThat(out).isEmpty()
    }

    @Test fun empty_payload_yields_nothing() {
        val out = NotificationCapture.candidates(
            title = "AX-AXISBK", text = null, bigText = "  ", conversationTitle = null,
            messages = emptyList(), postTime = postTime,
        )
        assertThat(out).isEmpty()
    }

    // ---- Android's own redaction (the Truecaller Business Chat finding) ----
    //
    // This is the shape the owner's phone actually produced: title = the APP's name, text = the platform
    // placeholder, no bigText, no MessagingStyle. Before these tests it was reported as "the sender isn't a
    // bank Spends knows", which sent the investigation to the sender allowlist — a file that cannot possibly
    // be the cause when the body never arrived.

    @Test fun redacted_plain_notification_is_diagnosed_as_redacted_not_unknown_sender() {
        val d = NotificationCapture.diagnose(
            title = "Truecaller",
            text = "Sensitive notification content hidden",
            bigText = null,
            conversationTitle = null,
            messages = emptyList(),
            postTime = postTime,
        )
        assertThat(d.rejection).isEqualTo(NotificationCapture.Rejection.REDACTED_BY_ANDROID)
    }

    @Test fun redacted_message_inside_a_chat_is_also_diagnosed_as_redacted() {
        val d = NotificationCapture.diagnose(
            title = "Truecaller",
            text = null,
            bigText = null,
            conversationTitle = "HDFC Bank",
            messages = listOf(RawMessage("HDFC Bank", "Sensitive notification content hidden", postTime)),
            postTime = postTime,
        )
        assertThat(d.rejection).isEqualTo(NotificationCapture.Rejection.REDACTED_BY_ANDROID)
    }

    @Test fun redaction_check_is_case_and_padding_insensitive() {
        assertThat(NotificationCapture.looksRedacted("  Sensitive Content Hidden  ")).isTrue()
        assertThat(NotificationCapture.looksRedacted("SENSITIVE NOTIFICATION CONTENT HIDDEN")).isTrue()
    }

    // ⭐The guard that matters most. A real bank alert must NEVER be written off as a platform redaction —
    // that would blame the phone for a parser bug and close the only investigation that could fix it.
    @Test fun a_real_message_mentioning_hidden_is_not_treated_as_redacted() {
        assertThat(NotificationCapture.looksRedacted("INR 499 debited. Balance hidden for security")).isFalse()
        assertThat(NotificationCapture.looksRedacted("Your sensitive data is safe with us")).isFalse()
        assertThat(NotificationCapture.looksRedacted(null)).isFalse()
    }

    // A redacted body carries no amount, so capture was always going to fail on it. Pinned so the diagnosis
    // change can never be mistaken for a capture change.
    @Test fun redaction_diagnosis_does_not_alter_what_capture_does() {
        val out = NotificationCapture.candidates(
            title = "Truecaller",
            text = "Sensitive notification content hidden",
            bigText = null,
            conversationTitle = null,
            messages = emptyList(),
            postTime = postTime,
        )
        assertThat(out).isEmpty()
    }
}
