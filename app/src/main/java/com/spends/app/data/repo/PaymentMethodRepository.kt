package com.spends.app.data.repo

import androidx.room.withTransaction
import com.spends.app.core.category.ColorAssigner
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.db.dao.ExpenseDao
import com.spends.app.data.db.dao.PaymentMethodDao
import com.spends.app.data.db.entity.PaymentMethodEntity
import com.spends.app.domain.model.PaymentMethodType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the user's payment instruments (cards) for the Smart Cycle / Cards feature (PRD §4.7).
 * A `null` paymentMethodId on an expense means "Bank" (the salary-cycle bucket); only explicit cards
 * get their own billing cycle. Deleting or merging a card re-points its expenses inside one transaction
 * so the ledger is never orphaned.
 */
@Singleton
class PaymentMethodRepository @Inject constructor(
    private val dao: PaymentMethodDao,
    private val expenseDao: ExpenseDao,
    private val db: SpendsDatabase,
) {

    /** Confirmed cards the user manages. */
    fun observeConfirmed(): Flow<List<PaymentMethodEntity>> = dao.observeConfirmed()

    /** Auto-discovered candidates awaiting review ("Cards to review"). */
    fun observeCandidates(): Flow<List<PaymentMethodEntity>> = dao.observeCandidates()

    suspend fun getById(id: Long): PaymentMethodEntity? = dao.getById(id)

    /** Add a card the user typed in manually (already reviewed). Returns the new id. */
    suspend fun addManual(
        label: String,
        institution: String?,
        last4: String?,
        type: PaymentMethodType = PaymentMethodType.CREDIT_CARD,
        billingDay: Int? = null,
        dueDay: Int? = null,
    ): Long {
        val now = DateUtils.nowMillis()
        return dao.insert(
            PaymentMethodEntity(
                type = type,
                label = label.ifBlank { institution ?: "Card" },
                institution = institution?.ifBlank { null },
                last4 = last4?.ifBlank { null },
                colorHex = assignColor(label.ifBlank { (institution ?: "") + (last4 ?: "card") }),
                billingDay = billingDay,
                dueDay = dueDay,
                reviewed = true,
                dismissed = false,
                firstSeenAt = now,
                lastActivityAt = now,
            ),
        )
    }

    /** Persist edits to an existing card (label / billing day / etc.). */
    suspend fun updateCard(card: PaymentMethodEntity) = dao.update(card)

    /** Confirm a discovered candidate (carrying any edits the user made on the review card). */
    suspend fun confirmCandidate(card: PaymentMethodEntity) = dao.update(card.copy(reviewed = true, dismissed = false))

    /** "Not a card" — keep the row hidden so discovery never re-proposes this instrument. */
    suspend fun dismissCandidate(id: Long) = dao.dismiss(id)

    /** Delete a card; its expenses fall back to the Bank bucket (paymentMethodId → null). */
    suspend fun delete(id: Long) = db.withTransaction {
        expenseDao.clearPaymentMethod(id, DateUtils.nowMillis())
        dao.deleteById(id)
    }

    /** Merge [sourceId] into [targetId]: re-point its expenses, then delete the now-empty source. */
    suspend fun merge(sourceId: Long, targetId: Long) {
        if (sourceId == targetId) return
        db.withTransaction {
            expenseDao.reassignPaymentMethod(sourceId, targetId, DateUtils.nowMillis())
            dao.deleteById(sourceId)
        }
    }

    /**
     * Auto-discovery: propose a credit card from a parsed SMS ([last4] + [institution]). Caller gates on
     * the institution actually being a credit card. No-ops (returns false) if any row already exists for
     * this instrument (confirmed, candidate, OR dismissed), so it never re-nags about a card the user
     * already handled. Returns true when a NEW candidate was added.
     */
    suspend fun discoverCard(last4: String?, institution: String?): Boolean {
        val l4 = last4?.takeIf { it.isNotBlank() } ?: return false
        val inst = institution?.takeIf { it.isNotBlank() } ?: return false
        if (dao.findByLast4AndInstitution(l4, inst) != null) return false
        // Also skip if the user already CONFIRMED a card with this last4 (e.g. a manual add that carried no
        // institution) — otherwise the same physical card would be proposed a second time.
        if (dao.findConfirmedByLast4(l4) != null) return false
        val now = DateUtils.nowMillis()
        dao.insert(
            PaymentMethodEntity(
                type = PaymentMethodType.CREDIT_CARD,
                label = inst, // a sensible starting name; the user renames it on review
                institution = inst,
                last4 = l4,
                colorHex = assignColor(inst + l4),
                billingDay = null,
                dueDay = null,
                reviewed = false, // a candidate, surfaced in "Cards to review"
                dismissed = false,
                firstSeenAt = now,
                lastActivityAt = now,
            ),
        )
        return true
    }

    /** A confirmed card whose last4 matches — to auto-fill "Paid with" on an SMS-detected expense. */
    suspend fun matchConfirmedByLast4(last4: String?): Long? {
        val l4 = last4?.takeIf { it.isNotBlank() } ?: return null
        return dao.findConfirmedByLast4(l4)?.id
    }

    /** Bump a card's lastActivityAt when it's used (keeps the most-used cards near the top). */
    suspend fun touch(id: Long) = dao.touch(id, DateUtils.nowMillis())

    /** A distinct card colour from the design palette, avoiding hues already taken by other cards. */
    private suspend fun assignColor(seed: String): String {
        val taken = dao.getAllOnce().map { it.colorHex }.toSet()
        return ColorAssigner.colorFor(seed, taken)
    }

    // ---- Backup / restore ----

    suspend fun getAllOnce(): List<PaymentMethodEntity> = dao.getAllOnce()

    suspend fun replaceAll(cards: List<PaymentMethodEntity>) {
        dao.deleteAll()
        dao.insertAll(cards)
    }
}
