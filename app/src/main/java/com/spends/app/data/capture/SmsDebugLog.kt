package com.spends.app.data.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * Every SMS on the phone flows through the receiver, so most of what passes here is personal mail. Four
 * rules are enforced INSIDE this class rather than at the call site, so no future caller can leak by
 * forgetting them. [record] deliberately takes no `institution` parameter — it resolves the sender
 * itself, so a caller cannot assert "this is a bank" and have the log believe it:
 *
 *  1. **A message body is stored only when the sender resolves to a tracked bank** — resolved here, via
 *     [SenderAllowlist], not by the caller. Everything else keeps `body = null` whatever is passed in.
 *  2. **…and only for an outcome reached with capture switched ON** ([BODY_BEARING]). An owner who has
 *     capture off is never having their bank alerts transcribed, which is the stance the notification
 *     log takes by gating its tally. Here the tally stays ungated — a count of zero is the whole point
 *     of the screen — and the CONTENT is gated instead.
 *  3. **Every stored body has every NUMBER masked and every link removed** — unconditionally, not just
 *     for the outcomes that look like a passcode. This report is built to be pasted into a chat window,
 *     so a numeric one-time passcode cannot reach it by any route, and neither can an amount, a balance,
 *     a card tail or a per-customer short link. The words that explain why a message did or did not
 *     parse survive. (A code written in LETTERS would survive too — no Indian bank uses that form, and
 *     the honest claim here is "every number", not "every secret".)
 *  4. **A sender that isn't an A2P header is masked.** Indian bank alerts arrive from alphanumeric
 *     headers ("AD-HDFCBK"), which are exactly what we need to read when the allowlist misses one. A
 *     sender that is only digits is a person's phone number, and one containing "@" is an email-to-SMS
 *     address — a stronger identifier than the number. Neither carries diagnostic value.
 *
 * `detail` is gated by rule 1 as well: it carries the parsed merchant and amount, and is the reason
 * rule 3 costs no diagnostic power — the figures Spends actually read are reported there.
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

        /** The broadcast carried no readable message at all. */
        NO_MESSAGE_DATA,

        /** A message arrived, but its text was empty. Separate from [NO_MESSAGE_DATA] so the reason can
         *  be named without a free-text detail, which would sit outside the privacy gate. */
        BLANK_BODY,

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

    /**
     * Broadcasts that reached the receiver but died before anything could be recorded, because the
     * dependency graph could not be built.
     *
     * A plain object with no Hilt involvement, because the thing being counted is Hilt not working —
     * and deliberately NOT a field on [Snapshot], because a graph failure is precisely the moment
     * `publish()` cannot run, so a snapshot copy would be stale exactly when it mattered.
     *
     * Without this, such a broadcast leaves [Snapshot.totalReceived] at zero and the verdict asserts
     * "Android is not delivering… nothing inside Spends can be the cause" — a confident claim that is
     * false in precisely the case it cannot see. Process-scoped; never persisted.
     *
     * **A flow, not a plain field, and there is deliberately no `reset()`.** The count was first read
     * once per `ON_RESUME`, by analogy with the permission grants — a bad analogy: a permission can only
     * change off-screen, whereas a graph failure happens *while the owner is watching*, in the exact
     * situation the verdict tells them to sit and wait for ("leave the app open, send yourself a text").
     * And "Clear what's recorded" used to zero it, which restored the very sentence this object exists
     * to prevent, against evidence that cannot be replayed. A confirmed fault inside the app outranks
     * tidiness; it dies with the process like everything else here.
     */
    object ReceiverFailures {
        private val _graphFailures = MutableStateFlow(0)
        val graphFailures: StateFlow<Int> = _graphFailures.asStateFlow()

        fun recordGraphFailure() {
            _graphFailures.update { it + 1 }
        }
    }

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
     * Record where one message stopped.
     *
     * [body] and [detail] are accepted for every call and kept only when all of this class's privacy
     * rules allow it. **There is deliberately no `institution` parameter**: the sender is resolved here
     * against [SenderAllowlist], so a caller cannot assert that a personal message came from a bank and
     * have the log store its text on that word.
     */
    @Synchronized
    fun record(
        timeMillis: Long,
        sender: String?,
        body: String?,
        outcome: Outcome,
        detail: String? = null,
    ) {
        val institution = SenderAllowlist.lookup(sender)?.name
        if (institution != null) fromKnownBanks++
        entries.addFirst(
            Entry(
                timeMillis = timeMillis,
                sender = maskSender(sender),
                institution = institution,
                body = storableBody(institution, body, outcome),
                outcome = outcome,
                // Gated by BOTH content rules, exactly as the body is. Gating it on the sender alone was
                // unreachable-but-wrong: this class's whole stance is that it does not trust its callers,
                // so a rule it claims to enforce must actually be enforced.
                detail = if (institution != null && outcome in BODY_BEARING) detail?.clip() else null,
            ),
        )
        while (entries.size > MAX_ENTRIES) entries.removeLast()
        publish()
    }

    /**
     * The content rules, applied in one place. Returns null unless the sender resolved to a bank AND the
     * outcome is one only reachable with capture switched on — and masks what survives.
     *
     * **Masking is unconditional, and an earlier version got this exactly backwards.** It masked only
     * `NOT_A_TRANSACTION`, on the reasoning that a non-transaction from a bank is overwhelmingly an OTP.
     * The gate producing that outcome is [SmsParser]'s `isOtp`, which excludes any text containing
     * "spent" or "debited" — so the three commonest Indian one-time-passcode formats
     * ("Rs.5000 debited… OTP 481920 to confirm") parse as genuine TRANSACTIONS, reached `PROMPTED`, and
     * had their passcode exported verbatim. **A parser heuristic is not a privacy control.**
     *
     * That lesson is why the mask below is written HERE rather than reusing `SmsParser.aiContextFor`,
     * which an earlier fix did. That function exists to build AI context, and it strips the "not you? /
     * SMS BLOCK…" trailer first — which for a message that opens with such a phrase deletes the entire
     * body and yields null, silently costing the diagnosis. Its 300-char cap also quietly took ownership
     * of this class's own bound. A privacy control must not be a borrowed function whose purpose, and
     * therefore whose future edits, belong to something else.
     */
    private fun storableBody(institution: String?, body: String?, outcome: Outcome): String? {
        if (institution == null || outcome !in BODY_BEARING) return null
        if (body.isNullOrBlank()) return null
        val collapsed = body.replace('\n', ' ').replace(WHITESPACE, " ").trim()
        val delinked = LINK.replace(collapsed, "(link)")
        val masked = NUMERAL.replace(delinked, "#").replace(WHITESPACE, " ").trim()
        return masked.takeIf { m -> m.any { it.isLetter() } }?.clip()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        totalReceived = 0
        fromKnownBanks = 0
        // ReceiverFailures is deliberately NOT reset — see its KDoc. Zeroing it here re-armed the exact
        // false claim it was added to prevent, one tap after the owner had already seen the truth.
        // lastReceivedAt is deliberately KEPT: "when did an SMS last reach the app" is the single most
        // useful fact on the screen, and clearing the list must not erase it and imply none ever has.
        // SmsVerdictTest pins the other half of that decision — the verdict must READ the kept
        // timestamp, or the screen contradicts the row printed directly beneath it.
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

        /** Shown in place of an email-to-SMS gateway sender. */
        const val MASKED_EMAIL_SENDER = "(an email address)"

        private const val MAX_ENTRIES = 60
        private const val MAX_CHARS = 500

        /**
         * One whole numeral → one `#`. Deliberately a copy of the rule in [SmsParser], not a call into
         * it: this is a privacy control and must not be able to change because a parser's needs changed.
         * `(?U)` makes `\d` Unicode-aware — Kotlin's default `\d` is ASCII `[0-9]`, so a Devanagari or
         * Arabic-Indic digit would otherwise pass straight through. Grouping separators and decimals are
         * swallowed so `Rs.5,59,393.44` becomes `Rs.#` rather than `Rs.#,#,#.#`, which would leak the
         * order of magnitude.
         */
        private val NUMERAL = Regex("(?U)\\d[\\d,]*(?:\\.\\d+)?")

        /**
         * URL-ish tokens → `(link)`. Indian bank alerts routinely carry a PER-CUSTOMER short link whose
         * path is a token identifying the recipient. Digit-masking alone leaves the letters, so
         * `hdfcbk.io/x/aB9cD2e` survives as `hdfcbk.io/x/aB#cD#e` — still enough to identify someone.
         */
        private val LINK = Regex("""\S*(?:https?://|www\.)\S*|\S*\.[a-z]{2,}/\S*""", RegexOption.IGNORE_CASE)

        private val WHITESPACE = Regex("\\s+")

        /**
         * A real email address, anchored — NOT a bare `contains('@')`, which was the first attempt.
         * GSM 03.38 encodes `@` as septet 0x00, so trailing `@` padding turns up on genuine alphanumeric
         * sender IDs in the wild: `AD-HDFCBK@` would have been masked as "an email address" while
         * [SenderAllowlist] still resolved it to HDFC Bank, contradicting itself on screen — and for an
         * UNRECOGNISED header it would hide the exact string the verdict tells the owner to report.
         */
        private val EMAIL = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

        /**
         * The only outcomes whose message text may be stored. Every one is reached AFTER the capture
         * switch has been read and found ON, so an owner who never enabled SMS capture never has a bank
         * alert transcribed. `CAPTURE_OFF` is absent for exactly that reason, and `DEMO_MODE`,
         * `NO_MESSAGE_DATA`, `BLANK_BODY` and `SENDER_NOT_RECOGNISED` have nothing worth keeping.
         */
        private val BODY_BEARING = setOf(
            Outcome.NOT_A_TRANSACTION,
            Outcome.PATTERN_SUPPRESSED,
            Outcome.ALREADY_KNOWN,
            Outcome.TWIN_ALREADY_PROMPTED,
            Outcome.PROMPT_BLOCKED,
            Outcome.PROMPTED,
        )

        /**
         * Bank and other A2P alerts arrive from alphanumeric headers ("AD-HDFCBK", "JD-SBIINB") — the
         * exact string we need to read when the allowlist misses a bank whose header has changed. Two
         * shapes are never that, and are replaced by a marker:
         *  - no letters at all → a person's phone number;
         *  - contains "@" → an email-to-SMS gateway address, which `displayOriginatingAddress` can
         *    return and which identifies a person more strongly than the phone number does. The
         *    letters-present rule alone would have exported it verbatim.
         *
         * Pure and public so the rule is directly testable.
         */
        fun maskSender(sender: String?): String = when {
            sender.isNullOrBlank() -> "(no sender)"
            EMAIL.matches(sender.trim()) -> MASKED_EMAIL_SENDER
            sender.any { it.isLetter() } -> sender
            else -> MASKED_SENDER
        }
    }
}
