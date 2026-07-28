package com.spends.app.data.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan result must always say how many messages it READ.
 *
 * The old wording printed "Nothing new to review in that range" whenever nothing was queued — the same
 * sentence for a scan that read zero messages and for one that read two hundred and already held every
 * one. Those are opposite facts: the first means Spends cannot see the inbox at all, the second means it
 * can see it perfectly. During the July 2026 capture investigation that ambiguity sent the diagnosis
 * down a dead end, because the owner's manual entries were legitimately masking every message.
 */
class ScanMessageTest {

    private fun result(scanned: Int, queued: Int, skipped: Int) =
        SmsCaptureRepository.ScanResult(scanned = scanned, queued = queued, skippedDuplicate = skipped)

    /** THE case the old message could not express. An empty inbox must never read like a full one. */
    @Test
    fun `reading nothing at all says so explicitly`() {
        val msg = SmsCaptureRepository.scanMessage(result(scanned = 0, queued = 0, skipped = 0))

        assertEquals("No messages at all in that range — Spends couldn't see a single one.", msg)
    }

    /** The other half of the same fork: it read plenty, and knew about all of them. */
    @Test
    fun `reading many and queueing none reports both numbers`() {
        val msg = SmsCaptureRepository.scanMessage(result(scanned = 214, queued = 0, skipped = 38))

        assertTrue("must say how many it read", msg.contains("214"))
        assertTrue("must say how many it already had", msg.contains("38"))
    }

    /**
     * The two "nothing new" cases must not produce the same sentence — that identity is the entire
     * defect. Asserted directly so no future rewording can quietly collapse them again.
     */
    @Test
    fun `an empty inbox and a fully-known inbox never read the same`() {
        val emptyInbox = SmsCaptureRepository.scanMessage(result(scanned = 0, queued = 0, skipped = 0))
        val allKnown = SmsCaptureRepository.scanMessage(result(scanned = 214, queued = 0, skipped = 214))

        assertTrue(emptyInbox != allKnown)
    }

    @Test
    fun `read some but recognised none of them still reports the read count`() {
        val msg = SmsCaptureRepository.scanMessage(result(scanned = 90, queued = 0, skipped = 0))

        assertTrue(msg.contains("90"))
        assertTrue("no skipped count when there is nothing to skip", !msg.contains("skipped"))
    }

    @Test
    fun `a successful scan reports queued, read and skipped`() {
        val msg = SmsCaptureRepository.scanMessage(result(scanned = 500, queued = 12, skipped = 40))

        assertTrue(msg.contains("12"))
        assertTrue(msg.contains("500"))
        assertTrue(msg.contains("40"))
    }

    @Test
    fun `a failed read is distinct from an empty one`() {
        val failed = SmsCaptureRepository.scanMessage(null)

        assertEquals("Couldn't read the inbox.", failed)
        assertTrue(failed != SmsCaptureRepository.scanMessage(result(scanned = 0, queued = 0, skipped = 0)))
    }

    @Test
    fun `one message is not described as one messages`() {
        assertTrue(SmsCaptureRepository.scanMessage(result(scanned = 1, queued = 0, skipped = 1)).contains("Read 1 message,"))
    }
}
