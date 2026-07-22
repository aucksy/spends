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
}
