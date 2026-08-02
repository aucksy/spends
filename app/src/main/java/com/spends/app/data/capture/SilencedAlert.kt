package com.spends.app.data.capture

import com.spends.app.core.money.Money
import com.spends.app.data.db.entity.IgnoredPatternEntity
import com.spends.app.domain.model.TxnKind

/**
 * One learn-from-ignore pattern, decoded back into something a person can read (#7 follow-up).
 *
 * The stored `patternKey` is an opaque join of `header|who|amountMinor|kind` built by
 * `SmsCaptureRepository.ignoreKey`. Nothing could read it back, so the ignore counter was a one-way
 * door: three ignores silenced an alert **permanently** and no screen in the app could show it, let
 * alone undo it. [decode] is that door's other side — pure, so it is directly testable.
 *
 * Decoding splits from the RIGHT, not the left: `kind` and `amountMinor` are machine-written and can
 * never contain a `|`, whereas the merchant is a verbatim slice of the bank's text and one day will.
 * Splitting left-first would silently mis-assign every field after such a merchant; this way a stray
 * separator can only ever widen [who], which is the one field where that is harmless.
 */
data class SilencedAlert(
    val patternKey: String,
    /** The bank's SMS sender header, e.g. "AXISBK" — always present, it is the key's first field. */
    val sender: String,
    /** Merchant (or institution) as the parser saw it, lowercased at write time. Blank when unknown. */
    val who: String,
    /** Paise. Zero when the original parse carried no amount. */
    val amountMinor: Long,
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

    /**
     * The line the owner reads, e.g. "₹450.00 at Swiggy". Falls back through merchant → sender →
     * a bare amount, so a row is never rendered as an empty string it cannot act on.
     */
    fun title(): String {
        val label = who.ifBlank { sender }.ifBlank { "" }
        val money = if (amountMinor > 0) Money.formatRupees(amountMinor) else null
        val preposition = if (kind == TxnKind.INCOME) "from" else "at"
        return when {
            money != null && label.isNotBlank() -> "$money $preposition ${titleCase(label)}"
            money != null -> money
            label.isNotBlank() -> titleCase(label)
            else -> "Unrecognised alert"
        }
    }

    /** The sender header, shown only when the headline is carrying a merchant name instead. */
    fun senderLine(): String? =
        sender.takeIf { it.isNotBlank() && who.isNotBlank() && !who.equals(it, ignoreCase = true) }

    companion object {

        /**
         * Decode a stored row. Never throws and never returns null: an unparseable key still yields a
         * row the owner can SEE and un-silence, because a key this code cannot read is exactly the one
         * that would otherwise stay stuck silencing an alert forever with no way out.
         */
        fun decode(entity: IgnoredPatternEntity): SilencedAlert {
            val parts = entity.patternKey.split('|')
            // header | who… | amount | kind — at least four fields, with `who` free to contain more.
            val sender = parts.firstOrNull().orEmpty()
            val kind = parts.lastOrNull()?.let { name -> TxnKind.entries.firstOrNull { it.name == name } }
            val amount = if (parts.size >= 4) parts[parts.size - 2].toLongOrNull() ?: 0L else 0L
            val who = if (parts.size >= 4) parts.subList(1, parts.size - 2).joinToString("|") else ""
            return SilencedAlert(
                patternKey = entity.patternKey,
                sender = sender,
                who = who.trim(),
                amountMinor = amount,
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
