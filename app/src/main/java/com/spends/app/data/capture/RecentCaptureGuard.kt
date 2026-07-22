package com.spends.app.data.capture

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small in-memory TTL registry that collapses the two live-capture races (Phase 4):
 *
 *  1. **Reposts** — a messaging notification re-delivers its whole recent-message list every time
 *     the conversation updates; each message must be processed once ([MESSAGE_TTL_MILLIS]).
 *  2. **Twins** — the same bank alert arriving through BOTH the SMS receiver and the Messages/
 *     Truecaller notification must produce ONE heads-up prompt ([PROMPT_TTL_MILLIS], keyed on the
 *     parse's dedupe hash, checked by both paths).
 *
 * Purely advisory: losing this state (process death) never loses money — the DB dedupe hashes are
 * the real guard; this only prevents duplicate *prompts*/re-parses. Bounded LRU so it can't grow.
 */
@Singleton
class RecentCaptureGuard @Inject constructor() {

    private val seen = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > MAX_ENTRIES
    }

    /**
     * True exactly once per [ttlMillis] window for a given [key]: the first caller gets true (and
     * the key is stamped [now]); repeats within the window get false. After the window lapses the
     * key is treated as fresh again.
     */
    @Synchronized
    fun checkAndMark(key: String, ttlMillis: Long, now: Long = System.currentTimeMillis()): Boolean {
        val last = seen[key]
        if (last != null && now - last < ttlMillis) return false
        seen[key] = now
        return true
    }

    /** Repost guard key for one message inside one app's notification. */
    fun messageKey(sourceApp: String, sender: String, body: String): String = "msg|$sourceApp|$sender|${body.hashCode()}|${body.length}"

    /** Cross-source prompt guard key for a parsed transaction. */
    fun promptKey(dedupeHash: String): String = "prompt|$dedupeHash"

    companion object {
        private const val MAX_ENTRIES = 512

        /** A conversation notification reposts the same recent messages for DAYS as new ones arrive.
         *  Held longer than the listener's 72h live-age window so a rejected capture can't ride back
         *  in on a later repost (72h age gate + 7d guard never leaves a gap while the process lives). */
        const val MESSAGE_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

        /** SMS + notification twins of one alert land within moments of each other. */
        const val PROMPT_TTL_MILLIS: Long = 15 * 60 * 1000
    }
}
