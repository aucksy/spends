package com.spends.app.data.backup

import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Encodes/decodes a [Snapshot] as gzip-compressed JSON (PRD §4.12). */
object BackupCodec {

    val FILE_NAME_PREFIX = "spends-backup-"
    // Pre-restore safety copies use a prefix that is NOT a prefix of FILE_NAME_PREFIX, so they are
    // excluded from the restore picker and from the rolling 60-keep prune of real backups.
    const val SAFETY_NAME_PREFIX = "spends-presafety-"
    // Legacy plaintext backups carried this extension; we can still decode them on restore.
    const val FILE_EXTENSION = ".json.gz"
    // New backups are encrypted (opaque container, see BackupCrypto) — a distinct extension signals that.
    const val ENCRYPTED_EXTENSION = ".spsenc"

    private val json = Json {
        ignoreUnknownKeys = true // tolerate fields added by newer app versions
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(snapshot: Snapshot): ByteArray {
        val raw = json.encodeToString(Snapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    /** @throws Exception on corrupt/truncated data or an unsupported (newer) schema. */
    fun decode(bytes: ByteArray): Snapshot {
        val raw = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        val snapshot = json.decodeFromString(Snapshot.serializer(), raw.toString(Charsets.UTF_8))
        require(snapshot.schemaVersion <= Snapshot.CURRENT_SCHEMA) {
            "This backup was made by a newer version of Spends. Update the app to restore it."
        }
        return snapshot
    }
}
