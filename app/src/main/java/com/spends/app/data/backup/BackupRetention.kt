package com.spends.app.data.backup

/**
 * Two rules that keep Drive history safe. Pure, so both are unit-tested.
 *
 * On 1 Nov 2026 one phone uploaded 60+ automatic backups in about five minutes (07:00 IST). Pruning kept
 * only the newest 60, so that one burst deleted every older backup — the owner lost all history in a
 * morning. Whatever starts a burst (a worker re-run after the phone kills the app mid-upload, two Spends
 * apps sharing the folder), these two rules make it harmless.
 */
object BackupRetention {

    /** An automatic backup never runs again within this long of the last automatic attempt. */
    const val MIN_AUTO_GAP_MILLIS: Long = 6L * 60 * 60 * 1000

    /** Newest backups always kept, however close together. */
    const val KEEP_NEWEST = 60

    /** On top of those, the newest backup of each of this many days is kept. */
    const val KEEP_DAILY = 90

    /** Automatic backup gate. [lastAttemptAt] is 0 when there has never been one. */
    fun mayRunAuto(lastAttemptAt: Long, now: Long): Boolean =
        lastAttemptAt <= 0L || now < lastAttemptAt || now - lastAttemptAt >= MIN_AUTO_GAP_MILLIS

    /**
     * Which real backups to delete. [names] are file names, in any order. Keeps the newest [KEEP_NEWEST], plus
     * the newest file of each of the newest [KEEP_DAILY] days (the day is read from the name's stamp, so a burst
     * on one day can never push other days out). Names without a readable stamp are never deleted.
     */
    fun toDelete(names: List<String>, prefix: String = BackupCodec.FILE_NAME_PREFIX): List<String> {
        val stamped = names.mapNotNull { n -> stampOf(n, prefix)?.let { n to it } }
            .sortedByDescending { it.second }
        val keep = HashSet<String>()
        stamped.take(KEEP_NEWEST).forEach { keep += it.first }
        stamped.groupBy { it.second.take(8) } // yyyyMMdd
            .entries.sortedByDescending { it.key }
            .take(KEEP_DAILY)
            .forEach { (_, files) -> keep += files.first().first } // newest of that day (list is newest first)
        return stamped.map { it.first }.filter { it !in keep }
    }

    /** "spends-backup-20261101-013107.spsenc" -> "20261101-013107"; null when it does not look like that. */
    private fun stampOf(name: String, prefix: String): String? {
        if (!name.startsWith(prefix)) return null
        val s = name.removePrefix(prefix).take(15)
        val ok = s.length == 15 && s[8] == '-' && s.withIndex().all { (i, c) -> i == 8 || c.isDigit() }
        return if (ok) s else null
    }
}
