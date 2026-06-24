package com.spends.app.data.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.spends.app.core.time.DateUtils
import com.spends.app.data.db.SpendsDatabase
import com.spends.app.data.db.entity.AllocationEntity
import com.spends.app.data.db.entity.CategoryEntity
import com.spends.app.data.db.entity.ExpenseEntity
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.data.settings.SettingsState
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.DefaultLanding
import com.spends.app.domain.model.Direction
import com.spends.app.domain.model.ThemeMode
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backupMetaStore: DataStore<Preferences> by preferencesDataStore(name = "backup_meta")
private val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")

/**
 * Builds/restores the full-state snapshot (PRD §4.12) and orchestrates it against the user's Drive
 * appDataFolder. A token from [DriveAuthManager] is passed in by the UI for each network op so the
 * short-lived OAuth token is always fresh.
 */
@Singleton
class BackupRepository @Inject constructor(
    private val db: SpendsDatabase,
    private val settingsRepository: SettingsRepository,
    private val driveClient: DriveClient,
    @ApplicationContext private val context: Context,
) {
    private val categoryDao = db.categoryDao()
    private val expenseDao = db.expenseDao()

    val lastBackupAt: Flow<Long?> = context.backupMetaStore.data.map { it[LAST_BACKUP_AT] }

    // ---- Snapshot build / apply (local) ----

    suspend fun buildSnapshot(): Snapshot {
        val settings = settingsRepository.settings.first()
        return Snapshot(
            createdAt = DateUtils.nowMillis(),
            data = SnapshotData(
                settings = settings.toSnapshot(),
                categories = categoryDao.getAllOnce().map { it.toSnapshot() },
                expenses = expenseDao.getAllExpensesOnce().map { it.toSnapshot() },
                allocations = expenseDao.getAllAllocationsOnce().map { it.toSnapshot() },
            ),
        )
    }

    suspend fun applySnapshot(snapshot: Snapshot) {
        db.withTransaction {
            expenseDao.deleteAllAllocations()
            expenseDao.deleteAllExpenses()
            categoryDao.deleteAll()
            categoryDao.insertAll(snapshot.data.categories.map { it.toEntity() })
            expenseDao.insertExpenses(snapshot.data.expenses.map { it.toEntity() })
            expenseDao.insertAllocations(snapshot.data.allocations.map { it.toEntity() })
        }
        settingsRepository.restore(snapshot.data.settings.toState())
    }

    // ---- Drive operations (token supplied by the caller) ----

    /** Upload a fresh snapshot, validate the round-trip, prune to 60 rolling copies. Returns its time. */
    suspend fun backupNow(token: String): Long {
        val snapshot = buildSnapshot()
        val bytes = BackupCodec.encode(snapshot)
        val name = BackupCodec.FILE_NAME_PREFIX + stamp(snapshot.createdAt) + BackupCodec.FILE_EXTENSION
        val fileId = driveClient.create(token, name, bytes)
        // Validate: re-download + decode (cheap guard against truncation).
        BackupCodec.decode(driveClient.download(token, fileId))
        runCatching { prune(token) }
        setLastBackupAt(snapshot.createdAt)
        return snapshot.createdAt
    }

    suspend fun listBackups(token: String): List<DriveFile> =
        driveClient.list(token).filter { it.name.startsWith(BackupCodec.FILE_NAME_PREFIX) }

    /** Download + decode a chosen backup, take a pre-restore safety copy, then apply it. */
    suspend fun restoreFrom(token: String, fileId: String) {
        val snapshot = BackupCodec.decode(driveClient.download(token, fileId))
        runCatching {
            val safety = buildSnapshot()
            val safetyName = BackupCodec.FILE_NAME_PREFIX + "presafety-" + stamp(safety.createdAt) + BackupCodec.FILE_EXTENSION
            driveClient.create(token, safetyName, BackupCodec.encode(safety))
        }
        applySnapshot(snapshot)
    }

    private suspend fun prune(token: String, keep: Int = 60) {
        // list() returns newest-first; delete everything beyond the newest `keep` snapshots.
        listBackups(token).drop(keep).forEach { runCatching { driveClient.delete(token, it.id) } }
    }

    private suspend fun setLastBackupAt(millis: Long) {
        context.backupMetaStore.edit { it[LAST_BACKUP_AT] = millis }
    }

    private fun stamp(millis: Long): String =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(millis))
}

// ---- Entity <-> Snapshot mappers ----

private fun CategoryEntity.toSnapshot() = SnapshotCategory(
    id, name, iconKey, colorHex, isCustom, isArchived, excludeFromSpend, sortOrder, usage.name,
)

private fun SnapshotCategory.toEntity() = CategoryEntity(
    id = id, name = name, iconKey = iconKey, colorHex = colorHex, isCustom = isCustom,
    isArchived = isArchived, excludeFromSpend = excludeFromSpend, sortOrder = sortOrder,
    usage = runCatching { CategoryUsage.valueOf(usage) }.getOrDefault(CategoryUsage.EXPENSE),
)

private fun ExpenseEntity.toSnapshot() = SnapshotExpense(
    id, amountMinor, occurredAt, merchantRaw, note, paymentMethodId, source.name, kind.name,
    direction.name, parseConfidence, dedupeHash, rawCaptureId, createdAt, updatedAt, deletedAt,
)

private fun SnapshotExpense.toEntity() = ExpenseEntity(
    id = id, amountMinor = amountMinor, occurredAt = occurredAt, merchantRaw = merchantRaw, note = note,
    paymentMethodId = paymentMethodId,
    source = runCatching { TxnSource.valueOf(source) }.getOrDefault(TxnSource.IMPORT),
    kind = runCatching { TxnKind.valueOf(kind) }.getOrDefault(TxnKind.EXPENSE),
    direction = runCatching { Direction.valueOf(direction) }.getOrDefault(Direction.DEBIT),
    parseConfidence = parseConfidence, dedupeHash = dedupeHash, rawCaptureId = rawCaptureId,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
)

private fun AllocationEntity.toSnapshot() = SnapshotAllocation(id, expenseId, categoryId, amountMinor)

private fun SnapshotAllocation.toEntity() = AllocationEntity(id, expenseId, categoryId, amountMinor)

private fun SettingsState.toSnapshot() = SnapshotSettings(
    onboardingComplete, themeMode.name, dynamicColor, salaryCycleStartDay, defaultLanding.name,
    carryForwardEnabled, trashRetentionDays,
)

private fun SnapshotSettings.toState() = SettingsState(
    onboardingComplete = onboardingComplete,
    themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(ThemeMode.SYSTEM),
    dynamicColor = dynamicColor,
    salaryCycleStartDay = salaryCycleStartDay,
    defaultLanding = runCatching { DefaultLanding.valueOf(defaultLanding) }.getOrDefault(DefaultLanding.TRANSACTIONS),
    carryForwardEnabled = carryForwardEnabled,
    trashRetentionDays = trashRetentionDays,
)
