package com.spends.app.data.importer

import com.spends.app.domain.model.TxnKind
import java.security.MessageDigest

/**
 * Stable content hash for de-duplicating imported rows against existing data and within the same
 * import (PRD §4.13). Buckets the timestamp to the day so a re-export of the same data — which may
 * differ only in intraday time — still dedupes.
 */
object DedupeKey {
    private const val DAY_MS = 86_400_000L

    /**
     * Composite key for an imported row: day + amount + category + note/merchant + kind — never amount
     * alone (#14). [occurrence] disambiguates a GENUINE same-day repeat of an otherwise-identical row
     * (so two ₹500 coffees bought the same day are BOTH kept, not merged); the 0th occurrence keeps the
     * legacy hash so re-importing data imported by an earlier build still dedupes instead of doubling.
     */
    fun forImport(
        occurredAt: Long,
        amountMinor: Long,
        categoryName: String,
        note: String?,
        kind: TxnKind,
        occurrence: Int = 0,
    ): String {
        val day = occurredAt / DAY_MS
        val parts = mutableListOf(
            day.toString(),
            amountMinor.toString(),
            categoryName.trim().lowercase(),
            (note ?: "").trim().lowercase(),
            kind.name,
        )
        if (occurrence > 0) parts.add("#$occurrence")
        return sha256Hex(parts.joinToString("|"))
    }

    /**
     * Stable hash for one occurrence of a recurring rule, namespaced by rule id + day so a rule can
     * never materialise the same date twice — the durable idempotency backstop for [materializeDue]
     * against edit-rewinds, concurrent passes, and partial-failure retries.
     */
    fun forRecurring(ruleId: Long, occurredAt: Long, amountMinor: Long, kind: TxnKind): String {
        val day = occurredAt / DAY_MS
        val raw = listOf("recurring", ruleId.toString(), day.toString(), amountMinor.toString(), kind.name)
            .joinToString("|")
        return sha256Hex(raw)
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString { bytes.forEach { append("%02x".format(it)) } }
    }
}
