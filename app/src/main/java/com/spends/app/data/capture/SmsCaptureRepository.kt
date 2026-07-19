package com.spends.app.data.capture

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.room.withTransaction
import com.spends.app.core.category.IconAssigner
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.db.entity.IgnoredPatternEntity
import com.spends.app.data.db.entity.MerchantCategoryEntity
import com.spends.app.data.db.entity.PendingCaptureEntity
import com.spends.app.data.repo.AllocationInput
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.PaymentMethodRepository
import com.spends.app.data.repo.TransactionInput
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Review-only bank-SMS capture (PRD §4.1). NOTHING is added automatically:
 *  - **Live** texts surface as an Add/Edit/Ignore notification. "Add" calls [captureReturningId] (the
 *    explicit silent add); "Edit"/tap opens the editor on an unsaved [draftFor] draft and only writes on
 *    Save via [commitDraft]; "Ignore" dismisses. So opening the editor never persists anything (#4).
 *  - **Historical** texts are pulled in via [scanHistory] over a user-chosen date range and land in the
 *    `pending_captures` table — a review queue that never touches balance/analytics until confirmed
 *    (tapping a row opens the editor and only writes on Save via [confirmPendingEdited], #9).
 *
 * Dedup is layered so a re-scan / multipart SMS / a manual entry the user already typed never double up.
 */
@Singleton
class SmsCaptureRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SpendsDatabase,
    private val expenseRepository: ExpenseRepository,
    private val paymentMethodRepository: PaymentMethodRepository,
) {
    private val categoryDao = db.categoryDao()
    private val expenseDao = db.expenseDao()
    private val pendingDao = db.pendingCaptureDao()
    private val merchantDao = db.merchantCategoryDao()
    private val ignoredPatternDao = db.ignoredPatternDao()

    // Serialises capture so concurrent broadcasts / a scan can't both pass the read-then-insert check.
    private val captureMutex = Mutex()

    /** Outcome of a historical inbox scan. */
    data class ScanResult(val scanned: Int, val queued: Int, val skippedDuplicate: Int)

    /** A read-only summary of a parsed SMS for the live capture prompt. */
    data class CapturePreview(val amountMinor: Long, val kind: TxnKind, val title: String)

    // ---- Live capture (notification path) ----

    /** Parse only (no DB write) to decide whether to prompt. Null when it isn't a transaction. */
    fun preview(sender: String?, body: String?, receivedAt: Long): CapturePreview? {
        val parsed = SmsParser.parse(sender, body, receivedAt)
        if (parsed.result != SmsParser.Result.TRANSACTION) return null
        val amount = parsed.amountMinor ?: return null
        val kind = parsed.kind ?: return null
        return CapturePreview(amount, kind, parsed.merchant ?: parsed.institution ?: "Transaction")
    }

    /** Live notification "Add" (the explicit, silent add): create the ledger transaction. Returns its id. */
    suspend fun captureReturningId(sender: String?, body: String?, receivedAt: Long): Long? = captureMutex.withLock {
        val parsed = SmsParser.parse(sender, body, receivedAt)
        if (parsed.result != SmsParser.Result.TRANSACTION) return@withLock null
        val amount = parsed.amountMinor ?: return@withLock null
        val kind = parsed.kind ?: return@withLock null
        val occurredAt = parsed.occurredAt ?: receivedAt
        val hash = hashFor(parsed, occurredAt, amount, kind)
        if (hash in expenseDao.allDedupeHashes().toHashSet()) return@withLock null // already in the ledger
        val categoryId = resolveCategory(parsed, kind)
        // Silent one-tap "Add" (no editor): tag by last4 only — the PRECISE match. The looser bank-name
        // fallback is deliberately confined to the review editor (#3), where the user can see + correct it,
        // so a same-bank non-card debit is never silently mis-attributed to a card here.
        val pmId = paymentMethodRepository.matchConfirmedByLast4(parsed.last4)
        // Live, user-confirmed captures are tagged NOTIFICATION (a deliberate add) — distinct from the
        // bulk historical scan (SMS) so the hide/delete-bulk controls never touch these (#11).
        createLedgerTxn(amount, kind, occurredAt, parsed.merchant, categoryId, hash, TxnSource.NOTIFICATION, pmId)
    }

    /**
     * Parse a live SMS into an UNSAVED draft for the editor (notification "Edit"/tap). Writes NOTHING —
     * the transaction is created only when the user Saves, via [commitDraft] (#4). Null when not a txn.
     */
    suspend fun draftFor(sender: String?, body: String?, receivedAt: Long): CaptureDraft? {
        val parsed = SmsParser.parse(sender, body, receivedAt)
        if (parsed.result != SmsParser.Result.TRANSACTION) return null
        val amount = parsed.amountMinor ?: return null
        val kind = parsed.kind ?: return null
        val occurredAt = parsed.occurredAt ?: receivedAt
        return CaptureDraft(
            amountMinor = amount,
            kind = kind,
            categoryId = resolveCategory(parsed, kind),
            merchant = parsed.merchant?.ifBlank { null },
            occurredAt = occurredAt,
            dedupeHash = hashFor(parsed, occurredAt, amount, kind),
            // Pre-match the instrument so the editor's "Paid with" is filled in for review (#3).
            paymentMethodId = paymentMethodRepository.matchInstrument(parsed.last4, parsed.institution),
        )
    }

    /** Save an edited live-capture draft to the ledger (explicit Save). Tags NOTIFICATION + the dedupe hash. */
    suspend fun commitDraft(
        amountMinor: Long,
        kind: TxnKind,
        categoryId: Long,
        merchant: String?,
        note: String?,
        occurredAt: Long,
        dedupeHash: String,
        paymentMethodId: Long? = null,
    ): Long = captureMutex.withLock {
        // Same guard the silent "Add" path has: never double-count an SMS already in the ledger (e.g. a
        // re-delivered alert, or one the user already added). Returns a sentinel; the editor only needs
        // to know Save finished — it ignores the id.
        if (dedupeHash in expenseDao.allDedupeHashes().toHashSet()) return@withLock -1L
        val newId = expenseRepository.create(
            TransactionInput(
                amountMinor = amountMinor,
                kind = kind,
                occurredAt = occurredAt,
                merchantRaw = merchant?.ifBlank { null },
                note = note?.ifBlank { null },
                allocations = listOf(AllocationInput(categoryId, amountMinor)),
                paymentMethodId = paymentMethodId,
                source = TxnSource.NOTIFICATION,
                dedupeHash = dedupeHash,
                parseConfidence = 100,
            ),
        )
        learnMerchantCategory(merchant?.ifBlank { null }, categoryId)
        newId
    }

    // ---- Review queue (pending captures) ----

    fun observePending(): Flow<List<PendingCaptureEntity>> = pendingDao.observeAll()
    fun observePendingCount(): Flow<Int> = pendingDao.observeCount()

    /** #7: live count of SMS-captured ledger transactions (independent of the review queue). */
    fun observeCapturedCount(): Flow<Int> = expenseDao.observeCapturedCount()

    // ---- Learn from "Ignore" (#7, conservative: suppress the NAG, never lose the transaction) ----

    /** A stable pattern key for an alert: sender header + merchant (or institution) + amount + kind. */
    private fun ignoreKey(sender: String?, parsed: SmsParser.Parsed): String {
        val header = SenderAllowlist.headerOf(sender) ?: sender?.uppercase() ?: ""
        val who = parsed.merchant?.lowercase()?.trim()?.takeIf { it.isNotBlank() }
            ?: parsed.institution?.lowercase() ?: ""
        return "$header|$who|${parsed.amountMinor ?: 0}|${parsed.kind?.name ?: ""}"
    }

    /** Record that the user ignored this exact alert; the count drives suppression (#7). */
    suspend fun recordIgnore(sender: String?, body: String?, receivedAt: Long) {
        val parsed = SmsParser.parse(sender, body, receivedAt)
        if (parsed.result != SmsParser.Result.TRANSACTION) return
        val key = ignoreKey(sender, parsed)
        val current = ignoredPatternDao.countFor(key) ?: 0
        ignoredPatternDao.upsert(IgnoredPatternEntity(key, current + 1, DateUtils.nowMillis()))
    }

    /** True once this exact pattern has been ignored enough times to stop posting its live alert (#7). */
    suspend fun isPatternSuppressed(sender: String?, body: String?, receivedAt: Long): Boolean {
        val parsed = SmsParser.parse(sender, body, receivedAt)
        if (parsed.result != SmsParser.Result.TRANSACTION) return false
        return (ignoredPatternDao.countFor(ignoreKey(sender, parsed)) ?: 0) >= IGNORE_SUPPRESS_THRESHOLD
    }

    /**
     * Silently drop a parsed SMS into the review queue (#7) — used when its alert is suppressed, so a
     * suppressed-but-genuine transaction is still reviewable and NEVER lost. Skips anything already in the
     * ledger or queue (dedupe hash). Returns the queued row id, or null if not a txn / already known.
     */
    suspend fun queueForReview(sender: String?, body: String?, receivedAt: Long): Long? = captureMutex.withLock {
        val parsed = SmsParser.parse(sender, body, receivedAt)
        if (parsed.result != SmsParser.Result.TRANSACTION) return@withLock null
        val amount = parsed.amountMinor ?: return@withLock null
        val kind = parsed.kind ?: return@withLock null
        val occurredAt = parsed.occurredAt ?: receivedAt
        val hash = hashFor(parsed, occurredAt, amount, kind)
        if (hash in expenseDao.allDedupeHashes().toHashSet()) return@withLock null
        if (hash in pendingDao.allHashes().toHashSet()) return@withLock null
        val categoryId = resolveCategory(parsed, kind)
        pendingDao.insert(
            PendingCaptureEntity(
                amountMinor = amount,
                kind = kind,
                occurredAt = occurredAt,
                merchant = parsed.merchant?.ifBlank { null },
                last4 = parsed.last4,
                institution = parsed.institution,
                categoryId = categoryId,
                parseConfidence = parsed.confidence,
                dedupeHash = hash,
                receivedAt = receivedAt,
                createdAt = DateUtils.nowMillis(),
                rawBody = body,
                sender = sender,
            ),
        ).takeIf { it != -1L }
    }

    /**
     * Scan the SMS inbox for [startMillis, endExclusiveMillis) and queue every parseable transaction
     * for review. Skips anything already in the ledger or queue (dedupe hash) AND anything that would
     * duplicate one of the user's own manual/imported entries (same day + amount + kind) — the exact
     * bug that made enabling capture double the manual transactions.
     */
    suspend fun scanHistory(startMillis: Long, endExclusiveMillis: Long, maxMessages: Int = 8000): ScanResult =
        withContext(Dispatchers.IO) {
            captureMutex.withLock {
                val seenHashes = (expenseDao.allDedupeHashes() + pendingDao.allHashes()).toHashSet()
                // Keys of the user's OWN (non-captured) transactions, so a scan never re-queues them.
                val manualKeys = expenseDao.getAllExpensesOnce()
                    .filter { it.deletedAt == null && it.source != TxnSource.SMS }
                    .mapTo(HashSet()) { manualKey(it.occurredAt, it.amountMinor, it.kind) }

                var scanned = 0
                var queued = 0
                var skipped = 0
                val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
                val uri: Uri = Telephony.Sms.Inbox.CONTENT_URI
                val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ?"
                val args = arrayOf(startMillis.toString(), endExclusiveMillis.toString())
                context.contentResolver.query(uri, cols, selection, args, "${Telephony.Sms.DATE} DESC")?.use { c ->
                    val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
                    val iBody = c.getColumnIndex(Telephony.Sms.BODY)
                    val iDate = c.getColumnIndex(Telephony.Sms.DATE)
                    while (c.moveToNext() && scanned < maxMessages) {
                        scanned++
                        val sender = if (iAddr >= 0) c.getString(iAddr) else null
                        val body = if (iBody >= 0) c.getString(iBody) else null
                        val date = if (iDate >= 0) c.getLong(iDate) else DateUtils.nowMillis()
                        val parsed = SmsParser.parse(sender, body, date)
                        if (parsed.result != SmsParser.Result.TRANSACTION) continue
                        val amount = parsed.amountMinor ?: continue
                        val kind = parsed.kind ?: continue
                        val occurredAt = parsed.occurredAt ?: date
                        val hash = hashFor(parsed, occurredAt, amount, kind)
                        if (hash in seenHashes || manualKey(occurredAt, amount, kind) in manualKeys) {
                            skipped++
                            continue
                        }
                        val categoryId = resolveCategory(parsed, kind)
                        val rowId = pendingDao.insert(
                            PendingCaptureEntity(
                                amountMinor = amount,
                                kind = kind,
                                occurredAt = occurredAt,
                                merchant = parsed.merchant?.ifBlank { null },
                                last4 = parsed.last4,
                                institution = parsed.institution,
                                categoryId = categoryId,
                                parseConfidence = parsed.confidence,
                                dedupeHash = hash,
                                receivedAt = date,
                                createdAt = DateUtils.nowMillis(),
                                rawBody = body, // #10/#12: keep the source SMS for View-SMS + search
                                sender = sender,
                            ),
                        )
                        if (rowId != -1L) {
                            seenHashes.add(hash)
                            queued++
                        } else {
                            skipped++ // lost the unique-index race
                        }
                    }
                }
                ScanResult(scanned = scanned, queued = queued, skippedDuplicate = skipped)
            }
        }

    /**
     * Scan the SMS inbox for [startMillis, endExclusiveMillis) and PROPOSE the credit cards it finds
     * (a credit-card sender + a parseable last4) as review candidates — the Cards tab "Scan for cards".
     * Never silent: each lands in "Cards to review" for the user to Confirm / Edit / dismiss. Writes
     * NOTHING to the ledger. Returns the count of NEW candidates (existing/dismissed cards are skipped).
     */
    suspend fun scanInboxForCards(startMillis: Long, endExclusiveMillis: Long, maxMessages: Int = 8000): Int =
        withContext(Dispatchers.IO) {
            // Serialise with the rest of capture (matching scanHistory) so two scans — or a scan racing
            // another card-discovery — can't both pass discoverCard's read-then-insert check and double-add.
            captureMutex.withLock {
                var added = 0
                // Many banks (Axis, IDFC First, …) issue credit cards under their BANK sender header, so a
                // type-only filter missed them (#7). Accept a dedicated credit-card sender OR a bank-header
                // alert whose body clearly names a "Credit Card". Never silent — each still lands in review.
                val creditCardBodyRe = Regex("credit\\s*card", RegexOption.IGNORE_CASE)
                // A statement-GENERATED alert (#13): the SMS arrives on the day the statement generates, which
                // is the card's billing-day anchor. Requires "is/has been/was generated" near "statement/bill"
                // so a due-date reminder OR a future "will be generated" notice (different days) is not used.
                val statementGeneratedRe = Regex(
                    "(?:e-?statement|statement|bill)\\b.{0,80}?\\b(?:has been|is|was)\\s+generated",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                )
                // last4 -> the day-of-month of each statement-generated SMS; we propose the most common day.
                val statementDays = HashMap<String, MutableList<Int>>()
                val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
                val uri: Uri = Telephony.Sms.Inbox.CONTENT_URI
                val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} < ?"
                val args = arrayOf(startMillis.toString(), endExclusiveMillis.toString())
                context.contentResolver.query(uri, cols, selection, args, "${Telephony.Sms.DATE} DESC")?.use { c ->
                    val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
                    val iBody = c.getColumnIndex(Telephony.Sms.BODY)
                    val iDate = c.getColumnIndex(Telephony.Sms.DATE)
                    var scanned = 0
                    while (c.moveToNext() && scanned < maxMessages) {
                        scanned++
                        val sender = if (iAddr >= 0) c.getString(iAddr) else null
                        val inst = SenderAllowlist.lookup(sender) ?: continue
                        val body = if (iBody >= 0) c.getString(iBody) else null
                        // A dedicated credit-card sender, OR a BANK-header alert whose body names a "Credit
                        // Card" (Axis/IDFC issue cards under their bank header). Restricted to BANK so wallets
                        // / payment apps (e.g. CRED) don't propose card-named candidates from their messages.
                        val looksLikeCard = inst.type == InstitutionType.CREDIT_CARD ||
                            (inst.type == InstitutionType.BANK && body != null && creditCardBodyRe.containsMatchIn(body))
                        if (!looksLikeCard) continue
                        val date = if (iDate >= 0) c.getLong(iDate) else DateUtils.nowMillis()
                        val last4 = SmsParser.parse(sender, body, date).last4 ?: continue
                        if (paymentMethodRepository.discoverCard(last4, inst.name)) added++
                        // #13: record this card's statement-generation day for a billing-day proposal.
                        if (body != null && statementGeneratedRe.containsMatchIn(body)) {
                            statementDays.getOrPut(last4) { mutableListOf() }.add(DateUtils.toLocalDate(date).dayOfMonth)
                        }
                    }
                }
                // Propose the most common statement day per card (#13) — attaches only to cards without a
                // confirmed billing day, and awaits the user's confirm on the Cards screen (never silent).
                statementDays.forEach { (last4, days) ->
                    modeDay(days)?.let { paymentMethodRepository.proposeBillingDay(last4, it) }
                }
                added
            }
        }

    /** The most frequent day in [days]; ties resolve to the smaller day. Null for an empty list (#13). */
    private fun modeDay(days: List<Int>): Int? =
        days.groupingBy { it }.eachCount().entries
            .maxWithOrNull(compareBy({ it.value }, { -it.key }))
            ?.key

    /** Confirm one pending capture into the ledger (optionally overriding the guessed category). */
    suspend fun confirmPending(id: Long, categoryId: Long? = null): Long? = captureMutex.withLock {
        val p = pendingDao.getById(id) ?: return@withLock null
        val finalCategory = categoryId ?: p.categoryId
        // Quick-confirm (no editor) → precise last4 match only; bank-name fallback is editor-only (#3).
        val pmId = paymentMethodRepository.matchConfirmedByLast4(p.last4)
        // Bulk-scanned confirmations stay TxnSource.SMS so the "hide / delete bulk-captured" controls
        // target exactly these (and not the live NOTIFICATION ones).
        val newId = createLedgerTxn(p.amountMinor, p.kind, p.occurredAt, p.merchant, finalCategory, p.dedupeHash, TxnSource.SMS, pmId)
        // Remember the user's category choice for this merchant so future captures pre-fill it (#14).
        learnMerchantCategory(p.merchant, finalCategory)
        pendingDao.deleteById(id)
        newId
    }

    /** Load a queued capture so the editor can prefill from it (review → "Review and Add", #9). */
    suspend fun getPending(id: Long): PendingCaptureEntity? = pendingDao.getById(id)

    /** Re-categorise a queued capture in place — review-only, NEVER writes to the ledger (#9). */
    suspend fun setPendingCategory(id: Long, categoryId: Long) = pendingDao.setCategory(id, categoryId)

    /**
     * Confirm a pending capture the user reviewed/edited in the full editor (#9). Creates the ledger txn
     * from the EDITED values while preserving the capture's TxnSource.SMS tag, its dedupe hash (so a
     * re-scan won't re-queue it), and the merchant→category learning — the three things a plain editor
     * save would drop — then removes the queued row. No-op (null) if the row is already gone.
     *
     * [paymentMethodId] is the instrument the user confirmed/corrected in the review editor (#3): the editor
     * seeds it from the auto-match, so whatever it passes here (including a deliberate null = Bank) wins —
     * we do NOT re-match and override the user's choice.
     */
    suspend fun confirmPendingEdited(
        id: Long,
        amountMinor: Long,
        kind: TxnKind,
        categoryId: Long,
        merchant: String?,
        note: String?,
        occurredAt: Long,
        paymentMethodId: Long? = null,
    ): Long? = captureMutex.withLock {
        val p = pendingDao.getById(id) ?: return@withLock null
        val newId = expenseRepository.create(
            TransactionInput(
                amountMinor = amountMinor,
                kind = kind,
                occurredAt = occurredAt,
                merchantRaw = merchant?.ifBlank { null },
                note = note?.ifBlank { null },
                allocations = listOf(AllocationInput(categoryId, amountMinor)),
                paymentMethodId = paymentMethodId,
                source = TxnSource.SMS,
                dedupeHash = p.dedupeHash,
                parseConfidence = 100,
            ),
        )
        learnMerchantCategory(merchant?.ifBlank { null } ?: p.merchant, categoryId)
        pendingDao.deleteById(id)
        newId
    }

    /** Confirm every pending capture as-is. Returns the number added. */
    suspend fun confirmAllPending(): Int = captureMutex.withLock {
        val all = pendingDao.getAllOnce()
        for (p in all) {
            // Bulk "Confirm all" (no per-item editor) → precise last4 match only; bank-name fallback is
            // editor-only (#3) so this never silently mis-attributes a same-bank non-card debit to a card.
            val pmId = paymentMethodRepository.matchConfirmedByLast4(p.last4)
            createLedgerTxn(p.amountMinor, p.kind, p.occurredAt, p.merchant, p.categoryId, p.dedupeHash, TxnSource.SMS, pmId)
            learnMerchantCategory(p.merchant, p.categoryId)
        }
        pendingDao.deleteAll()
        all.size
    }

    suspend fun rejectPending(id: Long) = pendingDao.deleteById(id)

    suspend fun clearPending() = pendingDao.deleteAll()

    /** #5: permanently remove every SMS-captured ledger transaction. Returns how many were deleted. */
    suspend fun deleteAllCaptured(): Int = db.withTransaction {
        val count = expenseDao.countCaptured()
        expenseDao.deleteCapturedAllocations()
        expenseDao.deleteCapturedExpenses()
        count
    }

    // ---- helpers ----

    /** #6: merchant = the real merchant only (no bank-name fallback); note is left for the user to fill. */
    private suspend fun createLedgerTxn(
        amount: Long,
        kind: TxnKind,
        occurredAt: Long,
        merchant: String?,
        categoryId: Long,
        hash: String,
        source: TxnSource,
        paymentMethodId: Long? = null,
    ): Long = expenseRepository.create(
        TransactionInput(
            amountMinor = amount,
            kind = kind,
            occurredAt = occurredAt,
            merchantRaw = merchant?.ifBlank { null },
            note = null,
            allocations = listOf(AllocationInput(categoryId, amount)),
            paymentMethodId = paymentMethodId,
            source = source,
            dedupeHash = hash,
            parseConfidence = 100, // user-reviewed
        ),
    )

    // ---- Merchant→category learning (#14) ----

    private fun merchantKeyOf(merchant: String?): String? =
        merchant?.lowercase()?.trim()?.takeIf { it.isNotBlank() }

    /** Remember that this merchant maps to [categoryId] so future captures pre-select it. */
    private suspend fun learnMerchantCategory(merchant: String?, categoryId: Long) {
        val key = merchantKeyOf(merchant) ?: return
        merchantDao.upsert(MerchantCategoryEntity(key, categoryId, DateUtils.nowMillis()))
    }

    /**
     * Learn from a manual recategorization of a captured transaction (the user correcting a guess in
     * the timeline). No-op for non-capture rows or rows without a merchant.
     */
    suspend fun learnFromTransaction(expenseId: Long, categoryId: Long) {
        val expense = expenseDao.getByIdWithAllocations(expenseId)?.expense ?: return
        if (expense.source == TxnSource.SMS || expense.source == TxnSource.NOTIFICATION) {
            learnMerchantCategory(expense.merchantRaw, categoryId)
        }
    }

    /** Drop learned mappings whose category was since deleted (so a stale id is never reused). */
    suspend fun pruneLearnedOrphans() = runCatching { merchantDao.pruneOrphans() }

    private fun hashFor(parsed: SmsParser.Parsed, occurredAt: Long, amount: Long, kind: TxnKind): String {
        // Fixed-zone day bucket so existing hashes stay valid and a re-scan after travel can't slip past dedupe.
        val day = DateUtils.dedupeEpochDay(occurredAt)
        val key = parsed.last4 ?: parsed.merchant?.lowercase() ?: ""
        return sha256("sms|$day|$amount|${kind.name}|$key|${parsed.refNumber ?: ""}")
    }

    /** Day+amount+kind key used to skip captures that would duplicate the user's own entries (fixed-zone day). */
    private fun manualKey(occurredAt: Long, amount: Long, kind: TxnKind): String {
        val day = DateUtils.dedupeEpochDay(occurredAt)
        return "$day|$amount|${kind.name}"
    }

    /** @return the resolved category id (learned merchant mapping first, then best guess; falls back to "Other"). */
    private suspend fun resolveCategory(parsed: SmsParser.Parsed, kind: TxnKind): Long {
        // #14: if the user has categorised this merchant before, reuse that choice.
        merchantKeyOf(parsed.merchant)?.let { key -> merchantDao.categoryFor(key)?.let { return it } }
        parsed.categoryHint?.let { hint -> categoryId(hint)?.let { return it } }
        return when (kind) {
            TxnKind.INCOME -> categoryId("Other Income") ?: fallbackCategory()
            TxnKind.EXPENSE -> guessExpenseCategory(parsed.merchant)?.let { categoryId(it) } ?: fallbackCategory()
        }
    }

    private suspend fun categoryId(name: String): Long? = categoryDao.getByName(name)?.id

    private suspend fun fallbackCategory(): Long =
        categoryDao.getByName("Other")?.id ?: categoryDao.getAllOnce().firstOrNull()?.id ?: 1L

    /** Map a merchant to a seed expense category via the icon keyword rules; null if unknown. */
    private fun guessExpenseCategory(merchant: String?): String? {
        if (merchant.isNullOrBlank()) return null
        return iconKeyToCategory[IconAssigner.keyFor(merchant)]
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString { bytes.forEach { append("%02x".format(it)) } }
    }

    companion object {
        const val REVIEW_THRESHOLD = 70

        /** #7: after this many ignores of the SAME alert pattern, stop notifying (queue silently instead). */
        private const val IGNORE_SUPPRESS_THRESHOLD = 3

        private val iconKeyToCategory = mapOf(
            "food" to "Food", "fastfood" to "Food", "coffee" to "Food",
            "grocery" to "Groceries", "shopping" to "Shopping", "clothing" to "Shopping",
            "entertainment" to "Entertainment", "music" to "Entertainment",
            "health" to "Health", "fitness" to "Fitness", "travel" to "Travel",
            "fuel" to "Fuel", "utilities" to "Utilities", "bills" to "Bills", "rent" to "Rent",
            "subscriptions" to "Subscriptions", "personal_care" to "Personal Care",
            "education" to "Education", "investments" to "Investments", "loan_emi" to "Loan/EMI",
            "gifts" to "Gifts", "transport" to "Transport", "car" to "Transport",
        )
    }
}
