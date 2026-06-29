package com.spends.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.spends.app.domain.model.PaymentMethodType

/**
 * A payment instrument the user spends through (PRD §4.7 — "Cards / payment methods"), referenced by
 * [ExpenseEntity.paymentMethodId]. Round A focuses on **credit cards**, each with its own billing cycle
 * ([billingDay]); a `null` paymentMethodId on an expense means "Bank" (bank/UPI/cash on the salary cycle).
 *
 * No FK on the expense side (mirrors [ExpenseEntity.paymentMethodId] / [RecurringRuleEntity.categoryId])
 * so deleting a card never cascades the user's transactions — they just fall back to the Bank bucket.
 *
 * Discovery: cards are auto-proposed from bank/card SMS (last4 + institution). A proposed card starts
 * [reviewed] = false (a candidate in "Cards to review"); the user Confirms it (→ reviewed = true), Edits
 * it, or says "Not a card" (→ [dismissed] = true, kept so the same card isn't proposed again). Manual
 * adds are [reviewed] = true from the start.
 */
@Entity(tableName = "payment_methods")
data class PaymentMethodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: PaymentMethodType = PaymentMethodType.CREDIT_CARD,
    /** Display name, e.g. "HDFC Millennia". */
    val label: String,
    /** Issuing institution as parsed/entered, e.g. "HDFC Bank". */
    val institution: String? = null,
    /** Last four digits of the card/account, e.g. "4821". */
    val last4: String? = null,
    /** Card accent colour ("#RRGGBB"), assigned on creation. */
    val colorHex: String,
    /** Statement day-of-month (1..28). null = not known yet → the card rides the salary cycle until set. */
    val billingDay: Int? = null,
    /** Optional payment-due day-of-month. */
    val dueDay: Int? = null,
    /** false = an auto-discovered candidate awaiting the user's review; true = a confirmed/manual card. */
    val reviewed: Boolean = true,
    /** true = the user said "Not a card"; kept (hidden) so discovery never re-proposes the same instrument. */
    val dismissed: Boolean = false,
    val firstSeenAt: Long,
    val lastActivityAt: Long,
)
