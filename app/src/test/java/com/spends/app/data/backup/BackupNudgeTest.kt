package com.spends.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupNudgeTest {

    private val day = 24L * 60 * 60 * 1000

    private fun usedOn(vararg days: Long) =
        days.fold(BackupNudge.State()) { s, d -> BackupNudge.onOpened(s, d) }

    @Test
    fun sameDayCountsOnce() {
        assertEquals(1, usedOn(100, 100, 100).daysUsed)
        assertEquals(3, usedOn(100, 101, 101, 105).daysUsed)
    }

    @Test
    fun waitsForThirdDayOfUse() {
        assertFalse(BackupNudge.shouldShow(usedOn(1, 2), hasBackup = false, autoBackupOn = false, now = 0))
        assertTrue(BackupNudge.shouldShow(usedOn(1, 2, 3), hasBackup = false, autoBackupOn = false, now = 0))
    }

    @Test
    fun neverWhenBackupExistsOrIsOn() {
        val s = usedOn(1, 2, 3)
        assertFalse(BackupNudge.shouldShow(s, hasBackup = true, autoBackupOn = false, now = 0))
        assertFalse(BackupNudge.shouldShow(s, hasBackup = false, autoBackupOn = true, now = 0))
    }

    @Test
    fun neverAfterADriveRestore() {
        val s = BackupNudge.onDriveRestored(usedOn(1, 2, 3, 4))
        assertFalse(BackupNudge.shouldShow(s, hasBackup = false, autoBackupOn = false, now = 100 * day))
    }

    @Test
    fun snoozesAWeekThenStopsAfterThree() {
        var s = BackupNudge.onShown(usedOn(1, 2, 3), now = 0)
        assertFalse(BackupNudge.shouldShow(s, false, false, now = 6 * day))
        assertTrue(BackupNudge.shouldShow(s, false, false, now = 7 * day))
        s = BackupNudge.onShown(BackupNudge.onShown(s, 7 * day), 14 * day)
        assertFalse(BackupNudge.shouldShow(s, false, false, now = 100 * day))
    }
}
