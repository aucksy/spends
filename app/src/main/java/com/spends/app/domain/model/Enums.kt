package com.spends.app.domain.model

/**
 * The kind of a transaction. **This drives all money math** (PRD §3/§4.17):
 *  - [INCOME]   increases Balance, never counted as spend.
 *  - [EXPENSE]  decreases Balance; counts in spend analytics unless its category is excludeFromSpend.
 *  - [TRANSFER] neutral to Balance and never spend (card bill payments, self-account moves, top-ups).
 */
enum class TxnKind { INCOME, EXPENSE, TRANSFER }

/** The raw bank movement, independent of [TxnKind]. */
enum class Direction { DEBIT, CREDIT }

/** Where a transaction came from. */
enum class TxnSource { MANUAL, SMS, NOTIFICATION, RECURRING, IMPORT }

/** Instrument type for a payment method (PRD §3). */
enum class PaymentMethodType { CREDIT_CARD, DEBIT_CARD, BANK_ACCOUNT, UPI, WALLET }

/** Which screen the app opens on (PRD §4.19). */
enum class DefaultLanding { TRANSACTIONS, ANALYTICS }

/** Theme preference (PRD §4.15). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }
