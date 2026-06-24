package com.spends.app.data.backup

import kotlinx.serialization.Serializable

/**
 * The full app-state backup envelope (PRD §4.12). Everything needed to reproduce the app exactly:
 * all transactions, splits, categories (with assigned icon/color), and every setting. Enums are
 * stored as strings and times as epoch-millis so the JSON stays portable + forward-compatible.
 */
@Serializable
data class Snapshot(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val createdAt: Long,
    val app: String = "spends",
    val data: SnapshotData,
) {
    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

@Serializable
data class SnapshotData(
    val settings: SnapshotSettings,
    val categories: List<SnapshotCategory>,
    val expenses: List<SnapshotExpense>,
    val allocations: List<SnapshotAllocation>,
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
)

@Serializable
data class SnapshotAllocation(
    val id: Long,
    val expenseId: Long,
    val categoryId: Long,
    val amountMinor: Long,
)
