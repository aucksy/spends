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
    enum class Rejection {
        NONE,

        /** No text anywhere — the RCS custom-layout case, unreadable by any third-party app. */
        NO_READABLE_TEXT,

        /** Text was readable; the sender/title just isn't a bank we map. */
        SENDER_NOT_RECOGNISED,

        /**
         * ⭐**Android replaced the message before we were handed it.** Since Android 15, notifications that
         * Android System Intelligence classifies as carrying a one-time code are redacted for every
         * notification listener that lacks `RECEIVE_SENSITIVE_NOTIFICATIONS` — a system/role permission no
         * ordinary app can hold. The listener receives a rebuilt notification whose body is the placeholder
         * matched by [looksRedacted], so the real text never reaches this app at all.
         *
         * This is NOT the RCS custom-layout case and NOT an unmapped sender, and it must never be reported
         * as either: both of those point an investigation at the parser, which cannot be the cause when the
         * parser is being handed a system placeholder. It is also not fixable in this app — it is a phone
         * setting ("Enhanced notifications" / Android System Intelligence) or nothing.
         */
        REDACTED_BY_ANDROID,

        /**
         * A MessagingStyle whose messages are all textless SHADOWED a perfectly readable bigText:
         * [candidates] commits to the messages branch and never tries the plain fallback. This is our
         * OWN bug, not the RCS limit — it must never be reported as "unreadable", or the investigation
         * closes on the one hypothesis that is actually fixable.
         */
        MESSAGES_SHADOWED_BIG_TEXT,
    }

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
        val plainBody = (bigText?.trim()?.takeIf { it.isNotBlank() } ?: text?.trim())?.takeIf { it.isNotBlank() }
        val titleTried = title?.takeIf { it.isNotBlank() } ?: "(no title)"
        // ⭐Checked FIRST, ahead of every sender/text branch. The redaction placeholder is perfectly readable
        // text with an app-name title, so every branch below would classify it as "sender not recognised" —
        // sending the next investigation to the allowlist, which is not where the message went.
        if (looksRedacted(text) || looksRedacted(bigText) || messages.any { looksRedacted(it.text) }) {
            return Diagnosis(Rejection.REDACTED_BY_ANDROID, emptyList())
        }
        if (messages.isNotEmpty()) {
            // Mirrors the messages branch: a message with no text is skipped before its sender matters.
            val withText = messages.filter { !it.text.isNullOrBlank() }
            if (withText.isEmpty()) {
                // Textless messages, but the notification DOES carry a readable body that `candidates`
                // never reaches. Ours to fix — do not let this read as the RCS limit.
                return if (plainBody != null) {
                    Diagnosis(Rejection.MESSAGES_SHADOWED_BIG_TEXT, listOf(titleTried))
                } else {
                    Diagnosis(Rejection.NO_READABLE_TEXT, emptyList())
                }
            }
            val tried = withText.map { m ->
                m.sender?.takeIf { it.isNotBlank() }
                    ?: conversationTitle?.takeIf { it.isNotBlank() }
                    ?: title?.takeIf { it.isNotBlank() }
                    ?: "(no sender)"
            }.distinct()
            return Diagnosis(Rejection.SENDER_NOT_RECOGNISED, tried)
        }
        if (plainBody == null) return Diagnosis(Rejection.NO_READABLE_TEXT, emptyList())
        return Diagnosis(Rejection.SENDER_NOT_RECOGNISED, listOf(titleTried))
    }

    /**
     * Whether [body] is Android's own stand-in for a message it withheld, rather than a real message.
     *
     * Matched on the platform's user-visible placeholder ("Sensitive notification content hidden"), plus the
     * shorter variant OEM builds use. Substring and case-insensitive, because the surrounding punctuation
     * and capitalisation differ between builds.
     *
     * ⚠ Deliberately narrow. A wider match would let a REAL bank message containing the word "hidden" be
     * written off as a platform redaction, which is the one mistake here that would hide a genuine parser
     * bug — the opposite of what this exists to do. A miss costs the clear verdict and falls back to the old
     * one; a false positive would blame the phone for something this app got wrong.
     *
     * Only ever consulted by [diagnose]. The capture path is unaffected either way: a redacted body has no
     * amount in it, so it was already going to fail — this changes what the owner is TOLD, not what happens.
     */
    internal fun looksRedacted(body: String?): Boolean {
        val t = body?.trim()?.lowercase() ?: return false
        return REDACTION_MARKERS.any { t.contains(it) }
    }

    private val REDACTION_MARKERS = listOf(
        "sensitive notification content hidden",
        "sensitive content hidden",
    )
}
