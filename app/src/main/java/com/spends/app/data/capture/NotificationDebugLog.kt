package com.spends.app.data.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TEMPORARY diagnostic recorder for notification capture (owner-facing "Notification debug" screen).
 *
 * Notification capture fails SILENTLY at four different links — sender not recognised, no readable
 * text, parse rejected, deduped — and all four look identical from outside (nothing appears). This
 * records what the listener actually saw so the owner can read it back instead of needing adb/logcat.
 *
 * Deliberately **in memory only**: nothing is written to disk, nothing enters the backup snapshot,
 * and everything is dropped when the app's process restarts. Notification CONTENT is kept only for
 * the watched apps (the ones the owner ticked); every other app contributes nothing but a package
 * name and a count — that counter is the proof of whether the reader is alive at all.
 *
 * Remove this class, [NotificationCapture.diagnose] and the debug screen once the root cause is fixed.
 */
@Singleton
class NotificationDebugLog @Inject constructor() {

    /** Where a notification stopped, in the order the listener applies its checks. */
    enum class Outcome {
        /** Group summary / ongoing / foreground-service — never a bank alert. */
        SKIPPED_SHAPE,

        /** No message text at all (the RCS custom-layout case — unreadable by any third-party app). */
        NO_READABLE_TEXT,

        /**
         * Textless chat messages hid a perfectly readable body — OUR bug, not the RCS limit. Called out
         * separately so it can never be mistaken for the unfixable case.
         */
        MESSAGES_SHADOWED_BIG_TEXT,

        /** Text was readable, but the sender/title isn't a bank we know. */
        SENDER_NOT_RECOGNISED,

        /** Older than the listener's age window. */
        TOO_OLD,

        /** Parsed by [SmsParser] but not a money movement (OTP, promo, declined, statement…). */
        NOT_A_TRANSACTION,

        /** A real transaction we already hold (ledger or review queue). */
        DUPLICATE,

        /** Queued into the review list. */
        QUEUED,

        /** Heads-up "Review & Add" prompt posted. */
        PROMPTED,
    }

    data class Entry(
        val timeMillis: Long,
        val packageName: String,
        val title: String?,
        val text: String?,
        val bigText: String?,
        /** Per-message senders read from a MessagingStyle notification (empty for plain ones). */
        val messageSenders: List<String>,
        val outcome: Outcome,
        /** Free-text extra — e.g. the sender strings we tried, or the canonical sender that parsed. */
        val detail: String?,
    )

    data class Snapshot(
        val connected: Boolean,
        val lastConnectedAt: Long?,
        /** Every notification from every app the listener was handed (proof the reader is alive). */
        val totalSeen: Int,
        /** Package name → how many notifications it posted. Package names only, no content. */
        val packageCounts: List<Pair<String, Int>>,
        /** Newest first. Watched apps only. */
        val entries: List<Entry>,
    )

    private val _state = MutableStateFlow(Snapshot(false, null, 0, emptyList(), emptyList()))
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val entries = ArrayDeque<Entry>()
    private val counts = LinkedHashMap<String, Int>()
    private var connected = false
    private var lastConnectedAt: Long? = null
    private var totalSeen = 0

    @Synchronized
    fun setConnected(value: Boolean, now: Long = System.currentTimeMillis()) {
        connected = value
        if (value) lastConnectedAt = now
        publish()
    }

    /**
     * Called for EVERY notification the listener is handed — package name and a count, never content.
     * Runs on the main looper inside a system callback, so it skips the (sorting, copying) publish
     * when nobody is watching the debug screen; the next [record] or screen open republishes anyway.
     */
    @Synchronized
    fun recordSeen(packageName: String) {
        totalSeen++
        if (counts.size < MAX_PACKAGES || counts.containsKey(packageName)) {
            counts[packageName] = (counts[packageName] ?: 0) + 1
        }
        if (_state.subscriptionCount.value > 0) publish()
    }

    /** Called for a notification from a WATCHED app, with what the listener read and where it stopped. */
    @Synchronized
    fun record(entry: Entry) {
        entries.addFirst(entry)
        while (entries.size > MAX_ENTRIES) entries.removeLast()
        publish()
    }

    /** Republish the current state — call when the screen subscribes, since [recordSeen] skips the
     *  publish while nobody is watching and the last emitted value can therefore be stale. */
    @Synchronized
    fun refresh() = publish()

    @Synchronized
    fun clear() {
        entries.clear()
        counts.clear()
        totalSeen = 0
        publish()
    }

    private fun publish() {
        _state.value = Snapshot(
            connected = connected,
            lastConnectedAt = lastConnectedAt,
            totalSeen = totalSeen,
            packageCounts = counts.entries.sortedByDescending { it.value }.map { it.key to it.value },
            entries = entries.toList(),
        )
    }

    private companion object {
        const val MAX_ENTRIES = 60
        const val MAX_PACKAGES = 80
    }
}
