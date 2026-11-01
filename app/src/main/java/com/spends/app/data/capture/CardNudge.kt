package com.spends.app.data.capture

import android.content.Context

/**
 * When to look for new credit cards and bank accounts in the SMS inbox, and which found ones to ask about. Before this, a
 * card was found only if the owner opened Banks & cards and tapped "Scan" — a new user never learned that a
 * card needed a bill date, so its spends sat in the salary cycle instead of the card's own cycle.
 *
 * Nothing is read until the owner says yes to "May Spends look?" ([Consent.YES]); "No" means never — they
 * add banks and cards themselves (the Scan button on Banks & Cards still works). The scan runs at most once per [SCAN_GAP_MILLIS]. The first one reads [FIRST_SCAN_DAYS] back (so a new
 * install sees every card it has used this year, with any statement day); later ones read only since the
 * last scan, plus [OVERLAP_MILLIS] in case a message arrived late. Each found card is asked about ONCE —
 * after that it waits quietly in "Cards to review". Device-local, not in the backup.
 */
object CardNudge {

    const val SCAN_GAP_MILLIS = 24L * 60 * 60 * 1000
    const val OVERLAP_MILLIS = 2L * 24 * 60 * 60 * 1000
    const val FIRST_SCAN_DAYS = 365L

    /** The owner's answer to "May Spends look in your SMS for banks and cards?". */
    enum class Consent { UNKNOWN, YES, NO }

    data class State(
        val lastScanAt: Long = 0,
        val askedIds: Set<Long> = emptySet(),
        val consent: Consent = Consent.UNKNOWN,
    )

    /** Start of the window to scan now, or null when the last scan is too recent. PURE. */
    fun scanStart(s: State, now: Long): Long? = when {
        s.lastScanAt <= 0L || now < s.lastScanAt -> now - FIRST_SCAN_DAYS * 24 * 60 * 60 * 1000
        now - s.lastScanAt < SCAN_GAP_MILLIS -> null
        else -> s.lastScanAt - OVERLAP_MILLIS
    }

    /** The candidates not asked about yet, in the order given. PURE. */
    fun toAsk(candidateIds: List<Long>, s: State): List<Long> = candidateIds.filter { it !in s.askedIds }

    /** Mark [ids] as asked. Drops ids that are no longer candidates, so the set never grows forever. PURE. */
    fun onAsked(s: State, ids: List<Long>, candidateIds: List<Long>): State =
        s.copy(askedIds = (s.askedIds + ids).filter { it in candidateIds }.toSet())

    fun read(context: Context): State {
        val p = prefs(context)
        return State(
            lastScanAt = p.getLong("last_scan_at", 0),
            askedIds = p.getStringSet("asked_ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
            consent = runCatching { Consent.valueOf(p.getString("consent", null) ?: "") }.getOrDefault(Consent.UNKNOWN),
        )
    }

    fun write(context: Context, s: State) {
        prefs(context).edit()
            .putLong("last_scan_at", s.lastScanAt)
            .putStringSet("asked_ids", s.askedIds.map { it.toString() }.toSet())
            .putString("consent", s.consent.name)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("card_nudge", Context.MODE_PRIVATE)
}
