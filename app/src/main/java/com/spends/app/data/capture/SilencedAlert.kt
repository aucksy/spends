package com.spends.app.data.capture

import com.spends.app.data.db.entity.IgnoredPatternEntity
import com.spends.app.domain.model.TxnKind

/**
 * One learn-from-ignore pattern, decoded back into something a person can read (#7 follow-up).
 *
 * The stored `patternKey` is an opaque join of `header|who|kind` built by
 * `SmsCaptureRepository.ignoreKey`. Nothing could read it back, so the ignore counter was a one-way
 * door: three ignores silenced an alert **permanently** and no screen in the app could show it, let
 * alone undo it. [decode] is that door's other side — pure, so it is directly testable.
 *
 * **The key no longer carries the amount** (v1.69.0). It used to, and that made the feature dead code —
 * see `SmsCaptureRepository.ignoreKey` for the full reasoning. A row therefore now stands for *every*
 * alert from one source in one direction, which is a bigger thing to switch off than a single figure,
 * so the screen has to say so rather than showing a rupee amount that no longer means anything.
 *
 * Decoding splits from the RIGHT, not the left: `kind` is machine-written and can never contain a `|`,
 * whereas the merchant is a verbatim slice of the bank's text. A stray separator can then only ever
 * widen [who], the one field where that is harmless — and `ignoreKey` strips them anyway.
 */
data class SilencedAlert(
    val patternKey: String,
    /** The bank's SMS sender header, e.g. "AXISBK" — always present, it is the key's first field. */
    val sender: String,
    /** Merchant (or institution) as the parser saw it, lowercased at write time. Blank when unknown. */
    val who: String,
    /** Null when the stored kind is unrecognised (e.g. written by a much older build). */
    val kind: TxnKind?,
    val ignoreCount: Int,
    val lastIgnoredAt: Long,
) {

    /** True once the count has crossed the threshold — this alert is no longer posted at all. */
    val isSilenced: Boolean get() = ignoreCount >= SmsCaptureRepository.IGNORE_SUPPRESS_THRESHOLD

    /** How many more ignores until it goes quiet. Zero once [isSilenced]. */
    val ignoresUntilSilenced: Int
        get() = (SmsCaptureRepository.IGNORE_SUPPRESS_THRESHOLD - ignoreCount).coerceAtLeast(0)

    /** The line the owner reads: who the alerts come from, e.g. "Php*finreliable Digite". */
    fun title(): String {
        val label = who.ifBlank { sender }
        return if (label.isNotBlank()) titleCase(label) else "Unrecognised alert"
    }

    /**
     * What is actually being silenced, in words — "Money-out alerts · from YESBNK".
     *
     * A row covers EVERY amount from this source now, not one figure, and the owner has to be able to
     * tell that at a glance before tapping Reset. The direction is part of the key, so alerts for money
     * coming in and money going out from the same bank are silenced separately and must read separately.
     */
    fun scopeLine(): String {
        val direction = when (kind) {
            TxnKind.INCOME -> "Money-in alerts"
            TxnKind.EXPENSE -> "Money-out alerts"
            null -> "Alerts"
        }
        // The sender is only worth naming when the headline is showing a MERCHANT. When there was no
        // merchant to parse, `title()` is already the sender, and "Alerts · from garbage" under a heading
        // reading "Garbage" says the same thing twice.
        val from = sender.takeIf { it.isNotBlank() && who.isNotBlank() && !who.equals(it, ignoreCase = true) }
        return if (from != null) "$direction  ·  from $from" else "$direction, any amount"
    }

    companion object {

        /**
         * Decode a stored row. Never throws and never returns null: an unparseable key still yields a
         * row the owner can SEE and un-silence, because a key this code cannot read is exactly the one
         * that would otherwise stay stuck silencing an alert forever with no way out.
         */
        fun decode(entity: IgnoredPatternEntity): SilencedAlert {
            // header | who… | kind — three fields, with `who` the only one free to contain more.
            val parts = entity.patternKey.split('|')
            val sender = parts.firstOrNull().orEmpty()
            val kind = parts.lastOrNull()?.let { name -> TxnKind.entries.firstOrNull { it.name == name } }
            val who = if (parts.size >= 3) parts.subList(1, parts.size - 1).joinToString("|") else ""
            return SilencedAlert(
                patternKey = entity.patternKey,
                sender = sender,
                who = who.trim(),
                kind = kind,
                ignoreCount = entity.ignoreCount,
                lastIgnoredAt = entity.updatedAt,
            )
        }

        /** "swiggy instamart" -> "Swiggy Instamart". Merchants are stored lowercased at write time. */
        private fun titleCase(raw: String): String =
            raw.split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
                .ifBlank { raw }
    }
}
