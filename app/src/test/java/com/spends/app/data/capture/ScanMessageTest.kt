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

    private fun scan(added: Int) = SmsCaptureRepository.CardScan(added)
    private fun demoScan() = SmsCaptureRepository.CardScan(0, refusedDemoMode = true)

    private fun result(scanned: Int, queued: Int, skipped: Int, demo: Boolean = false) =
        SmsCaptureRepository.ScanResult(
            scanned = scanned,
            queued = queued,
            skippedDuplicate = skipped,
            refusedDemoMode = demo,
        )

    /**
     * Demo mode refuses to read the inbox and returns zero counts. Rendering that as "no messages at
     * all" would be an affirmative lie about the owner's real inbox — from the very function whose
     * purpose is to stop conflating opposite facts. Capture settings are NOT hidden in demo mode
     * (only Backup & Restore is), so this is reachable with two taps.
     */
    @Test
    fun `demo mode is never reported as an empty inbox`() {
        val demo = SmsCaptureRepository.scanMessage(result(0, 0, 0, demo = true))

        assertTrue(demo.contains("demo mode"))
        assertTrue(
            "must not claim anything about the real inbox",
            !demo.contains("No messages at all"),
        )
        assertTrue(demo != SmsCaptureRepository.scanMessage(result(0, 0, 0)))
    }

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

    // ---- the card scan, which carried the same defect one function away ----

    /**
     * A throw and a refusal are different causes. Folding both through `getOrNull()` and naming only
     * demo mode meant a SecurityException from a revoked READ_SMS — the permission MIUI and ColorOS
     * expose as its own switch — told the owner to turn off a mode that wasn't on.
     */
    @Test
    fun `a card scan that threw is not blamed on demo mode`() {
        val threw = SmsCaptureRepository.cardScanMessage(scan = null, threw = true)

        assertTrue(threw.contains("permission"))
        assertTrue("must not name a cause it cannot know", !threw.contains("demo"))
    }

    @Test
    fun `a card scan refused by demo mode says so, and claims nothing about the inbox`() {
        val demo = SmsCaptureRepository.cardScanMessage(scan = demoScan(), threw = false)

        assertTrue(demo.contains("demo mode"))
        assertTrue("must not claim the inbox was read", !demo.contains("No new cards"))
    }

    @Test
    fun `a genuine empty result still reads as an empty result`() {
        assertEquals("No new cards found in SMS", SmsCaptureRepository.cardScanMessage(scan = scan(0), threw = false))
    }

    @Test
    fun `found cards are counted and pluralised`() {
        assertEquals("Found 1 new card to review", SmsCaptureRepository.cardScanMessage(scan(1), false))
        assertEquals("Found 3 new cards to review", SmsCaptureRepository.cardScanMessage(scan(3), false))
    }

    /**
     * FOUR causes now, not three. "Refused by demo mode" and "couldn't read the inbox" shared a null for
     * one review round, and the shared sentence blamed demo mode for a null cursor with demo mode off —
     * the same defect, relocated. The API could not express the fourth state, so no test could catch it.
     */
    @Test
    fun `refused, unreadable, failed and empty are four distinct sentences`() {
        val all = listOf(
            SmsCaptureRepository.cardScanMessage(null, threw = true),
            SmsCaptureRepository.cardScanMessage(null, threw = false),
            SmsCaptureRepository.cardScanMessage(demoScan(), threw = false),
            SmsCaptureRepository.cardScanMessage(scan(0), threw = false),
        )

        assertEquals(4, all.toSet().size)
    }

    @Test
    fun `an unreadable card scan is not blamed on demo mode`() {
        val unreadable = SmsCaptureRepository.cardScanMessage(scan = null, threw = false)

        // The exact string, not two negatives: asserting only what it ISN'T left `-> ""` green, because
        // the four-distinct test still counts an empty string as distinct.
        assertEquals("Couldn't read the inbox.", unreadable)
    }

    @Test
    fun `one message is not described as one messages`() {
        assertTrue(SmsCaptureRepository.scanMessage(result(scanned = 1, queued = 0, skipped = 1)).contains("Read 1 message,"))
    }
}
