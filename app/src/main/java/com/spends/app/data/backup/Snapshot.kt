package com.spends.app.data.backup

import kotlinx.serialization.Serializable

/**
 * The full app-state backup envelope (PRD §4.12). Everything needed to reproduce the app exactly:
 * all transactions, splits, categories (with assigned icon/color), and every setting. Enums are
 * stored as strings and times as epoch-millis so the JSON stays portable + forward-compatible.
 */
@Serializable
data class Snapshot(
    // Default 1 (not CURRENT_SCHEMA): every app-written backup serializes the field explicitly
    // (encodeDefaults = true since v0.4.0), so the default only fires for a foreign/hand-made file —
    // which must then take the CONSERVATIVE (oldest-schema) restore paths, never the newest.
    val schemaVersion: Int = 1,
    val createdAt: Long,
    val app: String = "spends",
    val data: SnapshotData,
) {
    companion object {
        // v2 adds `recurring`; v3 adds category `iconCustomized`; v4 adds `paymentMethods` + the
        // `smartCycleEnabled` setting; v5 adds `merchantCategories` (the learned merchant→category/note
        // memory, so a new phone keeps the learning). Decoders use ignoreUnknownKeys + field defaults,
        // so older backups (missing any of these) still restore cleanly.
        const val CURRENT_SCHEMA = 5
    }
}

@Serializable
data class SnapshotData(
    val settings: SnapshotSettings,
    val categories: List<SnapshotCategory>,
    val expenses: List<SnapshotExpense>,
    val allocations: List<SnapshotAllocation>,
    val recurring: List<SnapshotRecurring> = emptyList(),
    // Added in v4 (Cards feature). Default keeps older backups deserialising.
    val paymentMethods: List<SnapshotPaymentMethod> = emptyList(),
    // Added in v5 (learned merchant memory). Default keeps older backups deserialising.
    val merchantCategories: List<SnapshotMerchantCategory> = emptyList(),
)

@Serializable
data class SnapshotSettings(
    val onboardingComplete: Boolean,
    val themeMode: String,
    val dynamicColor: Boolean,
    val salaryCycleStartDay: Int,
    val defaultLanding: String,
    val carryForwardEnabled: Boolean,
    val trashRetentionDays: Int,
    val autoBackupEnabled: Boolean = false,
    // Added in v0.10.0; defaults keep older backups deserialising (ignoreUnknownKeys + these defaults).
    val carryForwardAnchorEpochDay: Long = 0,
    val carryForwardOpeningMinor: Long = 0,
    val hideCapturedInLists: Boolean = false,
    // Added in v0.12.0 (auto theme window). Additive defaults keep older backups valid.
    val autoDarkStartMinute: Int = 20 * 60,
    val autoDarkEndMinute: Int = 6 * 60,
    // Added in v0.23.0 (user-chosen daily backup time). Default 02:00; additive default keeps older backups valid.
    val autoBackupMinuteOfDay: Int = 2 * 60,
    // Added in v4 (Cards feature). Default off keeps older backups valid.
    val smartCycleEnabled: Boolean = false,
)

@Serializable
data class SnapshotCategory(
    val id: Long,
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val isCustom: Boolean,
    val isArchived: Boolean,
    val excludeFromSpend: Boolean,
    val sortOrder: Int,
    val usage: String,
    // Added in v3 (#5); default keeps older backups deserialising (ignoreUnknownKeys + this default).
    val iconCustomized: Boolean = false,
)

@Serializable
data class SnapshotExpense(
    val id: Long,
    val amountMinor: Long,
    val occurredAt: Long,
    val merchantRaw: String? = null,
    val note: String? = null,
    val paymentMethodId: Long? = null,
    val source: String,
    val kind: String,
    val direction: String,
    val parseConfidence: Int,
    val dedupeHash: String? = null,
    val rawCaptureId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    // Added in v4 (links a transaction to the recurring rule that created it). Default keeps older backups valid.
    val recurringRuleId: Long? = null,
)

@Serializable
data class SnapshotAllocation(
    val id: Long,
    val expenseId: Long,
    val categoryId: Long,
    val amountMinor: Long,
)

@Serializable
data class SnapshotRecurring(
    val id: Long,
    val amountMinor: Long,
    val kind: String,
    val categoryId: Long,
    val merchant: String? = null,
    val note: String? = null,
    val frequency: String,
    val intervalCount: Int,
    val anchorDay: Int,
    val startDate: Long,
    val nextRunAt: Long,
    val lastRunAt: Long? = null,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    // Added in v4 (#8 occurrence cap). 0 = forever. Default keeps older backups valid.
    val occurrenceLimit: Int = 0,
    // The paid-with instrument for generated transactions (#6). null = Bank. Default keeps older backups
    // valid; payment-method ids are preserved on restore, so the reference stays correct.
    val paymentMethodId: Long? = null,
)

/** Learned merchant→category/note memory (v5) so restores keep what the app has learned. */
@Serializable
data class SnapshotMerchantCategory(
    val merchantKey: String,
    val categoryId: Long,
    val updatedAt: Long,
    val note: String? = null,
)

@Serializable
data class SnapshotPaymentMethod(
    val id: Long,
    val type: String,
    val label: String,
    val institution: String? = null,
    val last4: String? = null,
    val colorHex: String,
    val billingDay: Int? = null,
    val dueDay: Int? = null,
    val reviewed: Boolean = true,
    val dismissed: Boolean = false,
    val firstSeenAt: Long,
    val lastActivityAt: Long,
    // A statement-detected billing day awaiting confirm (#13). Defaulted so older backups still decode.
    val proposedBillingDay: Int? = null,
)
