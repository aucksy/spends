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
            return messages.mapNotNull { m ->
                val body = m.text?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // A 1:1 business chat often leaves the per-message sender blank and names the
                // conversation instead; fall back conversationTitle → notification title.
                val who = m.sender?.takeIf { it.isNotBlank() }
                    ?: conversationTitle?.takeIf { it.isNotBlank() }
                    ?: title
                val sender = SenderAllowlist.canonicalSenderFor(who) ?: return@mapNotNull null
                Candidate(sender, body, if (m.timestamp > 0) m.timestamp else postTime)
            }
        }
        // No MessagingStyle (e.g. a plain Truecaller notification): title is the sender, big text
        // preferred over the (possibly truncated) collapsed text.
        val body = (bigText?.trim()?.takeIf { it.isNotBlank() } ?: text?.trim())
            ?.takeIf { it.isNotBlank() } ?: return emptyList()
        val sender = SenderAllowlist.canonicalSenderFor(title) ?: return emptyList()
        return listOf(Candidate(sender, body, postTime))
    }

    /** Why [candidates] came back empty. TEMPORARY — feeds the owner-facing notification debug screen. */
    enum class Rejection { NONE, NO_READABLE_TEXT, SENDER_NOT_RECOGNISED }

    /** [rejection] plus the sender strings that were tried (so an unmapped bank name is visible). */
    data class Diagnosis(val rejection: Rejection, val sendersTried: List<String>)

    /**
     * TEMPORARY diagnostic mirror of [candidates]: says WHY nothing survived, so a silent drop becomes
     * readable on the phone. Pure and side-effect free; the capture path never calls this. Delete it
     * together with `NotificationDebugLog` once the Truecaller root cause is fixed.
     */
    fun diagnose(
        title: String?,
        text: String?,
        bigText: String?,
        conversationTitle: String?,
        messages: List<RawMessage>,
        postTime: Long,
    ): Diagnosis {
        if (candidates(title, text, bigText, conversationTitle, messages, postTime).isNotEmpty()) {
            return Diagnosis(Rejection.NONE, emptyList())
        }
        if (messages.isNotEmpty()) {
            // Mirrors the messages branch: a message with no text is skipped before its sender matters.
            val withText = messages.filter { !it.text.isNullOrBlank() }
            if (withText.isEmpty()) return Diagnosis(Rejection.NO_READABLE_TEXT, emptyList())
            val tried = withText.map { m ->
                m.sender?.takeIf { it.isNotBlank() }
                    ?: conversationTitle?.takeIf { it.isNotBlank() }
                    ?: title
                    ?: "(no sender)"
            }.distinct()
            return Diagnosis(Rejection.SENDER_NOT_RECOGNISED, tried)
        }
        val body = (bigText?.trim()?.takeIf { it.isNotBlank() } ?: text?.trim())?.takeIf { it.isNotBlank() }
        if (body == null) return Diagnosis(Rejection.NO_READABLE_TEXT, emptyList())
        return Diagnosis(Rejection.SENDER_NOT_RECOGNISED, listOf(title ?: "(no title)"))
    }
}
