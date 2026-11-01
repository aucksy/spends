package com.spends.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRetentionTest {

    private val hour = 60L * 60 * 1000

    private fun name(day: Int, hhmmss: String) = "spends-backup-202610%02d-%s.spsenc".format(day, hhmmss)

    @Test
    fun autoGateBlocksABurst() {
        assertTrue(BackupRetention.mayRunAuto(0L, 1_000L))
        val start = 100 * hour
        assertFalse(BackupRetention.mayRunAuto(start, start + 5_000))
        assertFalse(BackupRetention.mayRunAuto(start, start + 5 * hour))
        assertTrue(BackupRetention.mayRunAuto(start, start + 6 * hour))
        // A clock moved backwards must not block backups forever.
        assertTrue(BackupRetention.mayRunAuto(start, start - hour))
    }

    @Test
    fun aBurstOnOneDayKeepsEveryOlderDay() {
        // 30 older daily backups, then 80 backups in five minutes on the last day (the 1 Nov 2026 burst).
        val older = (1..30).map { name(it, "020000") }
        val burst = (0 until 80).map { i -> name(31, "07%02d%02d".format(i / 60, i % 60)) }
        val deleted = BackupRetention.toDelete((older + burst).shuffled())
        assertTrue("every older day survives", older.none { it in deleted })
        assertEquals(20, deleted.size) // only the 20 oldest files of the burst go
        assertTrue(deleted.all { it in burst })
    }

    @Test
    fun keepsOnlyNewestPerDayBeyondTheNewest60() {
        val names = (1..31).flatMap { d -> listOf(name(d, "010000"), name(d, "020000"), name(d, "030000")) }
        val deleted = BackupRetention.toDelete(names).toSet()
        // 93 files: newest 60 kept, and each day's 03:00 file kept anyway.
        assertEquals(93 - 60 - 11, deleted.size)
        (1..31).forEach { d -> assertFalse(name(d, "030000") in deleted) }
    }

    @Test
    fun neverDeletesFilesItCannotRead() {
        val names = listOf("spends-backup-garbage.spsenc", "notes.txt") + (1..70).map { name(1, "00%04d".format(it)) }
        val deleted = BackupRetention.toDelete(names)
        assertFalse("spends-backup-garbage.spsenc" in deleted)
        assertFalse("notes.txt" in deleted)
    }
}
