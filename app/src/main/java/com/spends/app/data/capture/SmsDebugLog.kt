package com.spends.app.data.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TEMPORARY diagnostic recorder for **live SMS capture** (owner-facing "SMS debug" screen).
 *
 * The notification side has had a diagnostic since v1.57.0 precisely because "nothing appeared"
 * carries no information; the SMS side — the more important path — had none, and a July 2026
 * investigation into total capture loss stalled on exactly that. Every check below is silent from
 * outside the app, and the phone offers no way to tell them apart:
 *
 *  - Android never delivered the broadcast at all (force-stop, OEM background kill, revoked grant);
 *  - it was delivered, but the "Detect from bank SMS" switch is off;
 *  - the sender header isn't in [SenderAllowlist] (banks DO change their headers);
 *  - it parsed, but as an OTP / promo / declined / statement rather than a money movement;
 *  - we already hold it, so no prompt is posted;
 *  - the prompt was posted and the phone declined to show it.
 *
 * [totalReceived] is the load-bearing number: it is incremented for EVERY SMS the receiver is handed,
 * before any other check. If it stays at zero while texts are visibly arriving, the app is never being
 * given them and nothing inside Spends can be the cause.
 *
 * Deliberately **in memory only**: nothing is written to disk, nothing enters the backup snapshot, and
 * everything is dropped when the app's process restarts.
 *
 * ## Privacy — stricter than the notification log, on purpose
 * Every SMS on the phone flows through the receiver, so most of what passes here is personal mail. Two
 * rules are enforced INSIDE this class rather than at the call site, so no future caller can leak by
 * forgetting them:
 *
 *  1. **A message body is stored only when the sender resolved to a tracked bank.** A recognised sender
 *     means the text is a bank alert; everything else keeps `body = null` no matter what is passed in.
 *  2. **A numeric sender is masked.** Indian bank/A2P alerts arrive from alphanumeric headers
 *     ("AD-HDFCBK"), which are exactly what we need to read when the allowlist misses one. A sender with
 *     no letters is a person's phone number, carries no diagnostic value, and is replaced by a marker.
 *
 * Remove this class and the debug screen once the root cause is fixed (see
 * `docs/NOTIFICATION-CAPTURE-DEBUG.md`).
 */
@Singleton
class SmsDebugLog @Inject constructor() {

    /** Where an SMS stopped, in the order [com.spends.app.receiver.SmsReceiver] applies its checks. */
    enum class Outcome {
        /** Demo mode is active — real alerts are dropped on purpose so the sandbox can't eat them. */
        DEMO_MODE,

        /** The broadcast carried no readable message, or an empty body. */
        NO_MESSAGE_DATA,

        /** The "Detect from bank SMS" switch is off. */
        CAPTURE_OFF,

        /** The sender header isn't a bank [SenderAllowlist] knows. Body withheld — likely personal. */
        SENDER_NOT_RECOGNISED,

        /** From a known bank, but not a money movement (OTP, promo, declined, statement…). */
        NOT_A_TRANSACTION,

        /** Ignored enough times that its alert is suppressed (#7) — queued silently instead. */
        PATTERN_SUPPRESSED,

        /** Already in the ledger or the review queue, so no prompt is posted. */
        ALREADY_KNOWN,

        /** The notification twin of this alert claimed the prompt moments ago. One prompt is the contract. */
        TWIN_ALREADY_PROMPTED,

        /**
         * Parsed fine, but the phone would not show the prompt (notifications off, or the "Transaction
         * detection" category switched off). Queued for review instead of being dropped.
         */
        PROMPT_BLOCKED,

        /** Heads-up "Review & Add" prompt posted. The healthy outcome. */
        PROMPTED,
    }

    data class Entry(
        val timeMillis: Long,
        /** The sender header as received, or [MASKED_SENDER] when it carries no letters. */
        val sender: String,
        /** The bank this sender resolved to, or null when the allowlist didn't recognise it. */
        val institution: String?,
        /** The message text — non-null ONLY when [institution] is non-null (see the class doc). */
        val body: String?,
        val outcome: Outcome,
        /** Free-text extra — e.g. the parsed amount, or why the prompt could not be shown. */
        val detail: String?,
    )

    data class Snapshot(
        /** EVERY SMS the receiver was handed, counted before any other check. The key number. */
        val totalReceived: Int,
        val lastReceivedAt: Long?,
        /** How many of those came from a sender the allowlist recognised as a bank. */
        val fromKnownBanks: Int,
        /** Newest first. */
        val entries: List<Entry>,
    )

    private val _state = MutableStateFlow(Snapshot(0, null, 0, emptyList()))
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val entries = ArrayDeque<Entry>()
    private var totalReceived = 0
    private var lastReceivedAt: Long? = null
    private var fromKnownBanks = 0

    /**
     * Called for EVERY SMS the receiver is handed, before anything else — including in demo mode and
     * with capture switched off. No content, just the count and the time. This is the one fact no other
     * surface on the phone can give us: whether Android is delivering SMS to Spends at all.
     */
    @Synchronized
    fun recordReceived(now: Long = System.currentTimeMillis()) {
        totalReceived++
        lastReceivedAt = now
        publish()
    }

    /**
     * Record where one message stopped. [body] is accepted for every call and **kept only when
     * [institution] is non-null** — the privacy rule lives here so a caller cannot bypass it.
     */
    @Synchronized
    fun record(
        timeMillis: Long,
        sender: String?,
        institution: String?,
        body: String?,
        outcome: Outcome,
        detail: String? = null,
    ) {
        if (institution != null) fromKnownBanks++
        entries.addFirst(
            Entry(
                timeMillis = timeMillis,
                sender = maskSender(sender),
                institution = institution,
                body = if (institution != null) body?.clip() else null,
                outcome = outcome,
                detail = detail?.clip(),
            ),
        )
        while (entries.size > MAX_ENTRIES) entries.removeLast()
        publish()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        totalReceived = 0
        fromKnownBanks = 0
        // lastReceivedAt is deliberately KEPT: "when did an SMS last reach the app" is the single most
        // useful fact on the screen, and clearing the list must not erase it and imply none ever has.
        publish()
    }

    private fun publish() {
        _state.value = Snapshot(
            totalReceived = totalReceived,
            lastReceivedAt = lastReceivedAt,
            fromKnownBanks = fromKnownBanks,
            entries = entries.toList(),
        )
    }

    private fun String.clip(): String = if (length <= MAX_CHARS) this else take(MAX_CHARS) + "…"

    companion object {
        /** Shown in place of a personal sender's phone number. */
        const val MASKED_SENDER = "(a phone number)"

        private const val MAX_ENTRIES = 60
        private const val MAX_CHARS = 500

        /**
         * Bank and other A2P alerts arrive from alphanumeric headers ("AD-HDFCBK", "JD-SBIINB") — the
         * exact string we need to read when the allowlist misses a bank whose header has changed. A
         * sender with no letters at all is a person's phone number: no diagnostic value, so it never
         * gets recorded. Pure and public so the rule is directly testable.
         */
        fun maskSender(sender: String?): String = when {
            sender.isNullOrBlank() -> "(no sender)"
            sender.any { it.isLetter() } -> sender
            else -> MASKED_SENDER
        }
    }
}
