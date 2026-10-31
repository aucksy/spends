package com.spends.app.data.capture

import android.content.Context

/**
 * Which inbox texts Spends has already looked at, so the catch-up sweep ([SmsIntake.catchUp]) only
 * handles the ones the live receiver never got. Android (and Vivo/Xiaomi battery managers above all) can
 * simply not deliver SMS_RECEIVED to an app it has stopped; the text is in the inbox but Spends never
 * hears about it, and nothing on the phone says so. This is the memory that lets a later sweep find it.
 *
 * Stores a short fingerprint of each text (a hash and a length), never the text. Device-local.
 */
object SmsSeen {

    /** How many fingerprints to keep. A day of texts on a busy phone is well under this. */
    private const val MAX_KEPT = 400

    /** The sweep never looks further back than this, however long the app went unopened. */
    const val MAX_LOOKBACK_MILLIS: Long = 48L * 60 * 60 * 1000

    /** First sweep after install/update: the receiver kept no fingerprints before, so look back one day. */
    const val FIRST_LOOKBACK_MILLIS: Long = 24L * 60 * 60 * 1000

    /** Re-read a little before the last sweep, in case a text was stored late. Fingerprints stop repeats. */
    const val OVERLAP_MILLIS: Long = 30L * 60 * 1000

    /** Leave the newest texts to the live receiver, which may still be handling them. */
    const val SETTLE_MILLIS: Long = 30L * 1000

    /** Same text, same fingerprint — whether it came from the broadcast or from the inbox. */
    fun fingerprint(body: String): String {
        val norm = body.trim().replace(Regex("\\s+"), " ")
        return "${norm.hashCode()}_${norm.length}"
    }

    /** The inbox range one sweep reads: [start, end). Pure, so the rule is testable. */
    fun window(lastSweepAt: Long, now: Long): Pair<Long, Long> {
        val end = now - SETTLE_MILLIS
        val start = if (lastSweepAt <= 0L) now - FIRST_LOOKBACK_MILLIS
        else maxOf(lastSweepAt - OVERLAP_MILLIS, now - MAX_LOOKBACK_MILLIS)
        return start to end
    }

    /** Keep the newest [MAX_KEPT] fingerprints, oldest dropped first. Pure, so the cap is testable. */
    internal fun appendCapped(existing: List<String>, fp: String, max: Int = MAX_KEPT): List<String> {
        if (fp in existing) return existing
        val out = existing + fp
        return if (out.size > max) out.takeLast(max) else out
    }

    /**
     * The live receiver got this text. Also stamps the time Android last delivered any SMS to Spends.
     * Returns false when a sweep already handled it — a battery manager can hold the broadcast back and
     * release it only after the app is opened and the sweep has run. True on any storage failure, so a
     * broken preference file can never stop live capture.
     */
    @Synchronized
    fun markDelivered(context: Context, body: String, now: Long = System.currentTimeMillis()): Boolean = runCatching {
        val p = prefs(context)
        val list = read(p)
        val fp = fingerprint(body)
        p.edit()
            .putString(KEY_FPS, appendCapped(list, fp).joinToString(","))
            .putLong(KEY_LAST_DELIVERED, now)
            .apply()
        fp !in list
    }.getOrDefault(true)

    /** The sweep handled this text. Returns false when it had already been seen (live or by a sweep). */
    @Synchronized
    fun claim(context: Context, body: String): Boolean = runCatching {
        val p = prefs(context)
        val list = read(p)
        val fp = fingerprint(body)
        if (fp in list) return@runCatching false
        p.edit().putString(KEY_FPS, appendCapped(list, fp).joinToString(",")).commit()
        true
    }.getOrDefault(false)

    fun lastSweepAt(context: Context): Long = runCatching { prefs(context).getLong(KEY_LAST_SWEEP, 0L) }.getOrDefault(0L)

    fun setLastSweepAt(context: Context, at: Long) {
        runCatching { prefs(context).edit().putLong(KEY_LAST_SWEEP, at).apply() }
    }

    /** When Android last handed ANY text to Spends live, or 0 if never (since this was added). */
    fun lastDeliveredAt(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_LAST_DELIVERED, 0L) }.getOrDefault(0L)

    private fun read(p: android.content.SharedPreferences): List<String> =
        p.getString(KEY_FPS, null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    private fun prefs(context: Context) = context.getSharedPreferences("sms_seen", Context.MODE_PRIVATE)

    private const val KEY_FPS = "fps"
    private const val KEY_LAST_SWEEP = "last_sweep"
    private const val KEY_LAST_DELIVERED = "last_delivered"
}
