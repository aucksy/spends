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

    // Android 15+ replaces OTP-classified notifications with a placeholder for ordinary listeners. That
    // placeholder has no bank sender and no amount, so capture yields nothing for it.
    @Test fun android_redaction_placeholder_yields_nothing() {
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

    // ---- SBI Card RCS (the owner never once had one captured) ----

    private val sbiCardAlert = "Rs.1,178.82 spent on your SBI Credit Card ending 1234 at PAYTM on 27/02/26. " +
        "Trxn. not done by you? Report at https://sbicard.com/Dispute"

    @Test fun sbi_card_rcs_chat_becomes_an_sbi_card_transaction() {
        val out = NotificationCapture.candidates(
            title = "SBI Card", text = sbiCardAlert, bigText = null, conversationTitle = "SBI Card",
            messages = listOf(RawMessage("SBI Card", sbiCardAlert, 1_700_000_100_000L)),
            postTime = postTime,
        )
        assertThat(out).hasSize(1)
        val parsed = SmsParser.parse(out[0].sender, out[0].body, out[0].timestamp)
        assertThat(parsed.result).isEqualTo(SmsParser.Result.TRANSACTION)
        assertThat(parsed.amountMinor).isEqualTo(117_882L)
        assertThat(parsed.institution).isEqualTo("SBI Card")
        assertThat(parsed.channel).isEqualTo(SmsParser.Channel.CREDIT_CARD)
    }

    // An RCS rich card can post messages that carry no text; the alert is only in the big text.
    @Test fun textless_messages_fall_back_to_big_text() {
        val out = NotificationCapture.candidates(
            title = "SBI Card", text = "Transaction alert", bigText = sbiCardAlert, conversationTitle = null,
            messages = listOf(RawMessage("SBI Card", null, 1L)),
            postTime = postTime,
        )
        assertThat(out).hasSize(1)
        assertThat(out[0].body).isEqualTo(sbiCardAlert)
        assertThat(out[0].timestamp).isEqualTo(1L) // dated by the textless message, not the post time
    }

    // The owner's real thread: older text alerts, then a new rich card. The new one must not be dropped,
    // and it is dated by its message, not by the (fresh-on-every-repost) post time.
    @Test fun new_rich_card_in_a_thread_with_older_text_is_still_captured() {
        val older = "Rs.99.00 spent on your SBI Credit Card ending 1234 at SWIGGY on 25/02/26."
        val out = NotificationCapture.candidates(
            title = "SBI Card", text = "Transaction alert", bigText = sbiCardAlert, conversationTitle = null,
            messages = listOf(
                RawMessage("SBI Card", older, 1_700_000_100_000L),
                RawMessage("SBI Card", null, 1_700_000_200_000L),
            ),
            postTime = postTime,
        )
        assertThat(out.map { it.body }).containsExactly(older, sbiCardAlert)
        assertThat(out[1].timestamp).isEqualTo(1_700_000_200_000L)
    }

    @Test fun newest_message_with_text_adds_no_big_text_copy() {
        val out = NotificationCapture.candidates(
            title = "SBI Card", text = "x", bigText = sbiCardAlert, conversationTitle = null,
            messages = listOf(RawMessage("SBI Card", null, 1L), RawMessage("SBI Card", sbiCardAlert, 2L)),
            postTime = postTime,
        )
        assertThat(out).hasSize(1)
    }

    @Test fun sbi_card_agent_name_variants_resolve() {
        for (name in listOf("SBI Card", "SBI Card Alerts", "SBI Card Official", "SBI Cards and Payment Services Ltd")) {
            assertThat(SenderAllowlist.lookupSenderOrName(name)?.name).isEqualTo("SBI Card")
        }
    }

    @Test fun a_person_chat_with_textless_messages_still_yields_nothing() {
        val out = NotificationCapture.candidates(
            title = "Rahul", text = "Photo", bigText = "Photo", conversationTitle = null,
            messages = listOf(RawMessage("Rahul", null, 1L)),
            postTime = postTime,
        )
        assertThat(out).isEmpty()
    }
}
