package com.spends.app.data.capture

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What happened to the last money-looking alert from each watched app, shown under that app in the
 * "Apps to watch" checklist. Replaces the removed debug screens with one line the owner can read
 * after a missed alert ("Android hid the text" vs "not a bank Spends knows"). Stores the time, a
 * reason code and the sender name only — never the message text. Device-local, not in the backup.
 */
object LastAlertLog {

    enum class Reason(val line: String) {
        HIDDEN_BY_ANDROID("Android hid the text, so Spends could not read it"),
        UNKNOWN_SENDER("looked like money, but the sender is not a bank Spends knows"),
        NO_AMOUNT("read it, but found no amount"),
        ALREADY_IN_SPENDS("already in Spends"),
        ADDED_TO_REVIEW("added to Review"),
        ASKED_YOU("asked you to add it"),
        // SMS route only (key [SMS_KEY]).
        NOT_A_PAYMENT("read it, but it was not a payment"),
        PROMPT_BLOCKED("Spends notifications are off, so it went to Review"),
        FOUND_LATE("Android did not pass it to Spends when it arrived. Spends found it later and asked you"),
        FOUND_LATE_TO_REVIEW("Android did not pass it to Spends when it arrived. Spends found it later and added it to Review"),
    }

    /** The key the SMS route records under. Not a package name, so it can never clash with a watched app. */
    const val SMS_KEY = "sms"

    data class Entry(val atMillis: Long, val reason: Reason, val sender: String?)

    // Android's own placeholder when it redacts a "sensitive" notification for a normal listener (Android 15+).
    private const val HIDDEN_PLACEHOLDER = "sensitive notification content hidden"

    private val MONEY = Regex("""(?i)(₹|\brs\.?\s?\d|\binr\b|\bdebited\b|\bcredited\b|\bspent\b)""")

    fun looksHidden(vararg texts: String?): Boolean =
        texts.any { it != null && it.lowercase(Locale.ROOT).contains(HIDDEN_PLACEHOLDER) }

    fun looksLikeMoney(vararg texts: String?): Boolean = texts.any { it != null && MONEY.containsMatchIn(it) }

    /**
     * The reason for a notification that produced NO parse candidate, or null when it is not worth a line
     * (a missed call, a chat) — those must not overwrite the line a missed bank alert left behind.
     */
    fun reasonWithoutCandidate(vararg texts: String?): Reason? = when {
        looksHidden(*texts) -> Reason.HIDDEN_BY_ANDROID
        looksLikeMoney(*texts) -> Reason.UNKNOWN_SENDER
        else -> null
    }

    fun record(context: Context, pkg: String, reason: Reason, sender: String?, atMillis: Long = System.currentTimeMillis()) {
        runCatching {
            prefs(context).edit().putString(pkg, encode(Entry(atMillis, reason, sender))).apply()
        }
    }

    fun read(context: Context, pkg: String): Entry? =
        runCatching { prefs(context).getString(pkg, null)?.let(::decode) }.getOrNull()

    /** "Last alert, 2:14 pm: added to Review" (today) or "Last alert, 30 Oct 2:14 pm: …". */
    fun describe(
        e: Entry,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        label: String = "Last alert",
    ): String {
        val whenText = whenText(e.atMillis, today, zone)
        val from = e.sender?.takeIf { it.isNotBlank() }?.let { " (from $it)" }.orEmpty()
        return "$label, $whenText$from: ${e.reason.line}"
    }

    /**
     * "Android last passed a text to Spends: 2:14 pm". The one fact that splits a missed bank SMS into
     * its two causes: a recent time means texts ARE arriving (look at the bank SMS line); an old one, while
     * texts visibly arrive, means the phone is stopping Spends in the background.
     */
    fun deliveredLine(atMillis: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String =
        "Android last passed a text to Spends: ${whenText(atMillis, today, zone)}"

    /** "2:14 pm" today, "30 Oct 2:14 pm" on any other day. */
    private fun whenText(atMillis: Long, today: LocalDate, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(atMillis).atZone(zone)
        val time = at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).lowercase(Locale.ROOT)
        return if (at.toLocalDate() == today) time
        else at.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) + " " + time
    }

    internal fun encode(e: Entry): String = "${e.atMillis}|${e.reason.name}|${e.sender.orEmpty().replace("|", " ")}"

    internal fun decode(s: String): Entry? {
        val parts = s.split("|", limit = 3)
        if (parts.size < 2) return null
        val at = parts[0].toLongOrNull() ?: return null
        val reason = Reason.entries.firstOrNull { it.name == parts[1] } ?: return null
        return Entry(at, reason, parts.getOrNull(2)?.takeIf { it.isNotBlank() })
    }

    private fun prefs(context: Context) = context.getSharedPreferences("last_alert_log", Context.MODE_PRIVATE)
}
