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
import com.spends.app.data.db.entity.RecurringRuleEntity
import com.spends.app.data.settings.SettingsRepository
import com.spends.app.data.settings.SettingsState
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.DefaultLanding
import com.spends.app.domain.model.Direction
import com.spends.app.domain.model.RecurrenceFreq
import com.spends.app.domain.model.ThemeMode
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backupMetaStore: DataStore<Preferences> by preferencesDataStore(name = "backup_meta")
private val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")

/** Raised when a backup can't be made yet because no recovery password has been set (encryption gate). */
class BackupNotProtectedException : Exception("Set a backup password first so your backups are encrypted.")

/** Raised when restoring a backup whose key isn't on this device (e.g. a new phone) — prompt for the password. */
class BackupNeedsPasswordException : Exception("Enter your backup password to restore this backup.")

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
    private val secureKeyStore: SecureKeyStore,
    @ApplicationContext private val context: Context,
) {
    private val categoryDao = db.categoryDao()
    private val expenseDao = db.expenseDao()
    private val recurringDao = db.recurringDao()

    // Serialises Drive operations so the daily worker and a user-tapped "Back up now" can't race
    // (e.g. both create the "Spends Backup" folder at once).
    private val driveMutex = Mutex()

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
                recurring = recurringDao.getAllOnce().map { it.toSnapshot() },
            ),
        )
    }

    suspend fun applySnapshot(snapshot: Snapshot) {
        db.withTransaction {
            expenseDao.deleteAllAllocations()
            expenseDao.deleteAllExpenses()
            recurringDao.deleteAll()
            categoryDao.deleteAll()
            categoryDao.insertAll(snapshot.data.categories.map { it.toEntity() })
            expenseDao.insertExpenses(snapshot.data.expenses.map { it.toEntity() })
            expenseDao.insertAllocations(snapshot.data.allocations.map { it.toEntity() })
            recurringDao.insertAll(snapshot.data.recurring.map { it.toEntity() })
        }
        settingsRepository.restore(snapshot.data.settings.toState())
    }

    // ---- Encryption policy ----

    /** True when this device can make/read encrypted backups without a password (DEK armed). */
    fun isBackupProtected(): Boolean = secureKeyStore.isReady()

    /** True once a recovery password has been set. */
    fun hasBackupPassword(): Boolean = secureKeyStore.hasPassword()

    /** Set/replace the recovery password (PBKDF2 runs off the main thread). Keeps the same device DEK. */
    suspend fun setBackupPassword(password: CharArray) = withContext(Dispatchers.Default) {
        secureKeyStore.setPassword(password)
    }

    /** Encrypt a snapshot for storage. Requires a recovery password so any device can recover the file. */
    private fun protectedBytes(snapshot: Snapshot): ByteArray {
        val bundle = secureKeyStore.wrapBundle() ?: throw BackupNotProtectedException()
        return BackupCrypto.seal(BackupCodec.encode(snapshot), secureKeyStore.dek(), bundle)
    }

    /** Decode a backup, transparently decrypting new (encrypted) files and still accepting legacy plaintext. */
    private fun decodeAny(bytes: ByteArray): Snapshot {
        if (!BackupCrypto.isEncrypted(bytes)) return BackupCodec.decode(bytes) // legacy plaintext gzip
        if (!secureKeyStore.isReady()) throw BackupNeedsPasswordException()
        val plain = try {
            BackupCrypto.openWithDek(bytes, secureKeyStore.dek())
        } catch (e: Exception) {
            // Encrypted with another device's DEK (e.g. a backup from a different phone) — need the password.
            throw BackupNeedsPasswordException()
        }
        return BackupCodec.decode(plain)
    }

    /** Decode an encrypted backup with the recovery password, re-arming this device for zero-hassle after. */
    private fun decodeWithPassword(bytes: ByteArray, password: CharArray): Snapshot {
        val recovered = BackupCrypto.openWithPassword(bytes, password) // throws WrongBackupPasswordException
        secureKeyStore.importRecovered(recovered.dek, recovered.bundle)
        return BackupCodec.decode(recovered.plaintext)
    }

    // ---- Local file backup (no network) ----

    /** Encrypted bytes of the current full snapshot — for writing to a user-picked local file. */
    suspend fun buildBackupBytes(): ByteArray = protectedBytes(buildSnapshot())

    /** Decode + apply a backup read from a local file (replaces all current data). */
    suspend fun restoreFromBytes(bytes: ByteArray) = applySnapshot(decodeAny(bytes))

    /** Decode + apply a local-file backup using the recovery password (new-device path). */
    suspend fun restoreFromBytesWithPassword(bytes: ByteArray, password: CharArray) =
        applySnapshot(decodeWithPassword(bytes, password))

    // ---- Drive operations (token supplied by the caller) ----

    /** Upload a fresh (encrypted) snapshot to the "Spends Backup" folder, validate, prune to 60. */
    suspend fun backupNow(token: String): Long = driveMutex.withLock {
        val folderId = driveClient.ensureBackupFolder(token)
        val snapshot = buildSnapshot()
        val bytes = protectedBytes(snapshot)
        val name = BackupCodec.FILE_NAME_PREFIX + stamp(snapshot.createdAt) + BackupCodec.ENCRYPTED_EXTENSION
        val fileId = driveClient.create(token, name, bytes, folderId)
        // Validate: re-download + decode (cheap guard against truncation).
        decodeAny(driveClient.download(token, fileId))
        runCatching { prune(token, folderId) }
        setLastBackupAt(snapshot.createdAt)
        snapshot.createdAt
    }

    suspend fun listBackups(token: String): List<DriveFile> = driveMutex.withLock {
        val folderId = driveClient.ensureBackupFolder(token)
        driveClient.list(token, folderId).filter { it.name.startsWith(BackupCodec.FILE_NAME_PREFIX) }
    }

    /** Download + decode a chosen backup, take a pre-restore safety copy, then apply it. */
    suspend fun restoreFrom(token: String, fileId: String) = driveMutex.withLock {
        val snapshot = decodeAny(driveClient.download(token, fileId))
        backupSafetyCopy(token)
        applySnapshot(snapshot)
    }

    /** New-device Drive restore: decrypt with the recovery password, safety-copy, then apply. */
    suspend fun restoreFromWithPassword(token: String, fileId: String, password: CharArray) = driveMutex.withLock {
        val snapshot = decodeWithPassword(driveClient.download(token, fileId), password)
        backupSafetyCopy(token) // armed now — the password just imported the DEK
        applySnapshot(snapshot)
    }

    /** Best-effort pre-restore safety copy (skipped silently if not protected yet, e.g. a fresh device). */
    private suspend fun backupSafetyCopy(token: String) {
        runCatching {
            val folderId = driveClient.ensureBackupFolder(token)
            val safety = buildSnapshot()
            val safetyName = BackupCodec.SAFETY_NAME_PREFIX + stamp(safety.createdAt) + BackupCodec.ENCRYPTED_EXTENSION
            driveClient.create(token, safetyName, protectedBytes(safety), folderId)
        }
    }

    private suspend fun prune(token: String, folderId: String, keep: Int = 60) {
        // list() returns newest-first. Real backups and safety copies are pruned on separate budgets
        // so a burst of restores can't evict real backups.
        val files = driveClient.list(token, folderId)
        files.filter { it.name.startsWith(BackupCodec.FILE_NAME_PREFIX) }
            .drop(keep)
            .forEach { runCatching { driveClient.delete(token, it.id) } }
        files.filter { it.name.startsWith(BackupCodec.SAFETY_NAME_PREFIX) }
            .drop(10)
            .forEach { runCatching { driveClient.delete(token, it.id) } }
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

private fun RecurringRuleEntity.toSnapshot() = SnapshotRecurring(
    id = id, amountMinor = amountMinor, kind = kind.name, categoryId = categoryId, merchant = merchant,
    note = note, frequency = frequency.name, intervalCount = intervalCount, anchorDay = anchorDay,
    startDate = startDate, nextRunAt = nextRunAt, lastRunAt = lastRunAt, active = active,
    createdAt = createdAt, updatedAt = updatedAt,
)

private fun SnapshotRecurring.toEntity() = RecurringRuleEntity(
    id = id, amountMinor = amountMinor,
    kind = runCatching { TxnKind.valueOf(kind) }.getOrDefault(TxnKind.EXPENSE),
    categoryId = categoryId, merchant = merchant, note = note,
    frequency = runCatching { RecurrenceFreq.valueOf(frequency) }.getOrDefault(RecurrenceFreq.MONTHLY),
    intervalCount = intervalCount, anchorDay = anchorDay, startDate = startDate, nextRunAt = nextRunAt,
    lastRunAt = lastRunAt, active = active, createdAt = createdAt, updatedAt = updatedAt,
)

private fun SettingsState.toSnapshot() = SnapshotSettings(
    onboardingComplete, themeMode.name, dynamicColor, salaryCycleStartDay, defaultLanding.name,
    carryForwardEnabled, trashRetentionDays, autoBackupEnabled,
    carryForwardAnchorEpochDay, carryForwardOpeningMinor, hideCapturedInLists,
    autoDarkStartMinute, autoDarkEndMinute,
)

private fun SnapshotSettings.toState() = SettingsState(
    onboardingComplete = onboardingComplete,
    themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(ThemeMode.SYSTEM),
    dynamicColor = dynamicColor,
    salaryCycleStartDay = salaryCycleStartDay,
    defaultLanding = runCatching { DefaultLanding.valueOf(defaultLanding) }.getOrDefault(DefaultLanding.TRANSACTIONS),
    carryForwardEnabled = carryForwardEnabled,
    trashRetentionDays = trashRetentionDays,
    autoBackupEnabled = autoBackupEnabled,
    carryForwardAnchorEpochDay = carryForwardAnchorEpochDay,
    carryForwardOpeningMinor = carryForwardOpeningMinor,
    hideCapturedInLists = hideCapturedInLists,
    autoDarkStartMinute = autoDarkStartMinute,
    autoDarkEndMinute = autoDarkEndMinute,
)
