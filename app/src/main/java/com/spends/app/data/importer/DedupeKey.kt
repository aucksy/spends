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

    fun forImport(occurredAt: Long, amountMinor: Long, categoryName: String, note: String?, kind: TxnKind): String {
        val day = occurredAt / DAY_MS
        val raw = listOf(
            day.toString(),
            amountMinor.toString(),
            categoryName.trim().lowercase(),
            (note ?: "").trim().lowercase(),
            kind.name,
        ).joinToString("|")
        return sha256Hex(raw)
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString { bytes.forEach { append("%02x".format(it)) } }
    }
}
