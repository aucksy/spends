package com.spends.app.data.capture

/**
 * Pure mapping of a notification's text payload into parse candidates (Phase 4). Kept free of any
 * Android type so it's exhaustively unit-testable; the listener service adapts extras/MessagingStyle
 * into [RawMessage]s and hands them here.
 *
 * A messaging notification (Google Messages, Truecaller) carries a MessagingStyle list of the
 * conversation's recent messages — and REPOSTS the whole list every time a new message arrives.
 * We surface every message as its own candidate (so two bank alerts arriving close together are
 * both seen) and rely on the caller's repost guard + dedupe hashes to collapse the re-deliveries.
 *
 * Only candidates whose sender resolves via [SenderAllowlist.canonicalSenderFor] survive — a chat
 * from a person drops out right here, before anything else looks at it.
 */
object NotificationCapture {

    /** One message inside a notification, as the service read it (nulls tolerated). */
    data class RawMessage(val sender: String?, val text: String?, val timestamp: Long)

    /** One parseable candidate: [sender] is canonical (accepted by [SmsParser]). */
    data class Candidate(val sender: String, val body: String, val timestamp: Long)

    fun candidates(
        title: String?,
        text: String?,
        bigText: String?,
        conversationTitle: String?,
        messages: List<RawMessage>,
        postTime: Long,
    ): List<Candidate> {
        if (messages.isNotEmpty()) {
            val fromMessages = messages.mapNotNull { m ->
                val body = m.text?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // A 1:1 business chat often leaves the per-message sender blank and names the
                // conversation instead; fall back conversationTitle → notification title.
                val who = m.sender?.takeIf { it.isNotBlank() }
                    ?: conversationTitle?.takeIf { it.isNotBlank() }
                    ?: title
                val sender = SenderAllowlist.canonicalSenderFor(who) ?: return@mapNotNull null
                Candidate(sender, body, if (m.timestamp > 0) m.timestamp else postTime)
            }
            // An RCS rich card (how SBI Card sends its alerts) arrives as a message with NO text; the alert
            // sits only in the notification's big text. Returning the message list alone dropped it — even
            // in a chat that also held older text messages. So when the NEWEST message is textless, add the
            // big-text candidate too, dated by that message (not the post time, which is fresh on every
            // re-post and would defeat the age check and the day-bucketed dedupe hash).
            val newest = messages.maxByOrNull { it.timestamp }
            if (newest == null || !newest.text.isNullOrBlank()) return fromMessages
            val fallback = plainCandidate(title, text, bigText, if (newest.timestamp > 0) newest.timestamp else postTime)
            return if (fallback == null || fromMessages.any { it.body == fallback.body }) fromMessages else fromMessages + fallback
        }
        return listOfNotNull(plainCandidate(title, text, bigText, postTime))
    }

    private fun plainCandidate(title: String?, text: String?, bigText: String?, timestamp: Long): Candidate? {
        // No MessagingStyle (e.g. a plain Truecaller notification): title is the sender, big text
        // preferred over the (possibly truncated) collapsed text.
        val body = (bigText?.trim()?.takeIf { it.isNotBlank() } ?: text?.trim())
            ?.takeIf { it.isNotBlank() } ?: return null
        val sender = SenderAllowlist.canonicalSenderFor(title) ?: return null
        return Candidate(sender, body, timestamp)
    }
}
