package com.spends.app.data.capture

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.spends.app.core.category.IconAssigner
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.repo.AllocationInput
import com.spends.app.data.repo.ExpenseRepository
import com.spends.app.data.repo.TransactionInput
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns parsed bank SMS into transactions (PRD §4.1/§4.3). Auto-categorises by merchant keyword;
 * anything it can't confidently categorise is given a low parse-confidence so it surfaces in the
 * review queue. Dedupes via [TransactionInput.dedupeHash] so re-running the backfill or receiving
 * the same payment over multiple SMS doesn't double-count.
 */
@Singleton
class SmsCaptureRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SpendsDatabase,
    private val expenseRepository: ExpenseRepository,
) {
    private val categoryDao = db.categoryDao()
    private val expenseDao = db.expenseDao()

    // Serialises capture so concurrent broadcasts (duplicate / multipart SMS) can't both pass the
    // read-then-insert dedup check and double-insert; also serialises live capture vs. the backfill.
    private val captureMutex = Mutex()

    data class BackfillResult(val created: Int, val scanned: Int, val needsReview: Int)

    /** Parse + persist one message. Returns true if a transaction was created. */
    suspend fun capture(sender: String?, body: String?, receivedAt: Long): Boolean = captureMutex.withLock {
        val parsed = SmsParser.parse(sender, body, receivedAt)
        if (parsed.result != SmsParser.Result.TRANSACTION) return@withLock false
        val seen = expenseDao.allDedupeHashes().toHashSet()
        persist(parsed, receivedAt, seen) != null
    }

    /** One-time inbox backfill on enable. Bounded to [maxMessages] most-recent SMS. */
    suspend fun backfillInbox(maxMessages: Int = 4000): BackfillResult = withContext(Dispatchers.IO) {
        captureMutex.withLock {
            val seen = expenseDao.allDedupeHashes().toHashSet()
            var created = 0
            var scanned = 0
            var review = 0
            val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
            val uri: Uri = Telephony.Sms.Inbox.CONTENT_URI
            context.contentResolver.query(uri, cols, null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
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
                    val conf = persist(parsed, date, seen)
                    if (conf != null) {
                        created++
                        if (conf < REVIEW_THRESHOLD) review++ // mirror the review-queue filter exactly
                    }
                }
            }
            BackfillResult(created = created, scanned = scanned, needsReview = review)
        }
    }

    /** @return the stored parse confidence if a transaction was created, or null if skipped (dup). */
    private suspend fun persist(parsed: SmsParser.Parsed, fallbackTime: Long, seen: HashSet<String>): Int? {
        val amount = parsed.amountMinor ?: return null
        val kind = parsed.kind ?: return null
        val occurredAt = parsed.occurredAt ?: fallbackTime
        // IST day so the dedup bucket matches the rest of the app's day math.
        val day = DateUtils.toLocalDate(occurredAt).toEpochDay()
        val key = parsed.last4 ?: parsed.merchant?.lowercase() ?: ""
        // Reference number (RRN/UPI ref) keeps genuine same-day repeats distinct while still
        // collapsing the multiple SMS for one payment (which share the ref).
        val hash = sha256("sms|$day|$amount|${kind.name}|$key|${parsed.refNumber ?: ""}")
        if (!seen.add(hash)) return null // duplicate within this run or already in DB

        val (categoryId, confident) = resolveCategory(parsed, kind)
        val confidence = if (confident) parsed.confidence else minOf(parsed.confidence, 60)
        val instrument = listOfNotNull(parsed.institution, parsed.last4?.let { "••$it" })
            .joinToString(" ").ifBlank { null }

        expenseRepository.create(
            TransactionInput(
                amountMinor = amount,
                kind = kind,
                occurredAt = occurredAt,
                merchantRaw = parsed.merchant ?: parsed.institution,
                note = instrument,
                allocations = listOf(AllocationInput(categoryId, amount)),
                source = TxnSource.SMS,
                dedupeHash = hash,
                parseConfidence = confidence,
            ),
        )
        return confidence
    }

    /** @return categoryId + whether we're confident enough to skip review. */
    private suspend fun resolveCategory(parsed: SmsParser.Parsed, kind: TxnKind): Pair<Long, Boolean> {
        // Explicit hint (Loan/EMI, Investments) — confident.
        parsed.categoryHint?.let { hint -> categoryId(hint)?.let { return it to true } }
        return when (kind) {
            TxnKind.TRANSFER -> fallbackCategory() to true // neutral to spend anyway
            TxnKind.INCOME -> (categoryId("Other Income") ?: fallbackCategory()) to false
            TxnKind.EXPENSE -> {
                val guess = guessExpenseCategory(parsed.merchant)
                if (guess != null) {
                    val id = categoryId(guess)
                    if (id != null) return id to true
                }
                fallbackCategory() to false
            }
        }
    }

    private suspend fun categoryId(name: String): Long? = categoryDao.getByName(name)?.id

    private suspend fun fallbackCategory(): Long =
        categoryDao.getByName("Other")?.id ?: categoryDao.getAllOnce().firstOrNull()?.id ?: 1L

    /** Map a merchant to a seed expense category via the icon keyword rules; null if unknown. */
    private fun guessExpenseCategory(merchant: String?): String? {
        if (merchant.isNullOrBlank()) return null
        val key = IconAssigner.keyFor(merchant)
        return iconKeyToCategory[key]
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString { bytes.forEach { append("%02x".format(it)) } }
    }

    companion object {
        const val REVIEW_THRESHOLD = 70

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
