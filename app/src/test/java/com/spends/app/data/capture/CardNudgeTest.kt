package com.spends.app.data.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardNudgeTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000L * day

    @Test
    fun firstScanReadsAYearBack() {
        assertEquals(now - 365 * day, CardNudge.scanStart(CardNudge.State(), now))
    }

    @Test
    fun scansAtMostOnceADay() {
        assertNull(CardNudge.scanStart(CardNudge.State(lastScanAt = now - day + 1), now))
        assertEquals(now - day - 2 * day, CardNudge.scanStart(CardNudge.State(lastScanAt = now - day), now))
    }

    @Test
    fun aClockSetBackStartsAgainFromAYear() {
        assertEquals(now - 365 * day, CardNudge.scanStart(CardNudge.State(lastScanAt = now + day), now))
    }

    @Test
    fun asksAboutEachCardOnlyOnce() {
        var s = CardNudge.State()
        assertEquals(listOf(1L, 2L), CardNudge.toAsk(listOf(1L, 2L), s))
        s = CardNudge.onAsked(s, listOf(1L, 2L), listOf(1L, 2L))
        assertEquals(emptyList<Long>(), CardNudge.toAsk(listOf(1L, 2L), s))
        assertEquals(listOf(3L), CardNudge.toAsk(listOf(1L, 2L, 3L), s))
    }

    @Test
    fun forgetsCardsThatAreNoLongerWaiting() {
        val s = CardNudge.onAsked(CardNudge.State(askedIds = setOf(1L, 2L)), listOf(3L), listOf(2L, 3L))
        assertEquals(setOf(2L, 3L), s.askedIds)
    }
}
