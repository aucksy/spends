package com.spends.app.ui.backup

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.backup.BackupNeedsPasswordException
import com.spends.app.data.backup.BackupNotProtectedException
import com.spends.app.data.backup.BackupRepository
import com.spends.app.data.backup.DriveAuthManager
import com.spends.app.data.backup.DriveFile
import com.spends.app.data.backup.WrongBackupPasswordException
import com.spends.app.data.export.ExcelExporter
import com.spends.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A restore that decoded as encrypted-but-not-this-device, so it needs the recovery password. */
sealed interface PendingPasswordRestore {
    data class Drive(val fileId: String) : PendingPasswordRestore
    data class Local(val uri: Uri) : PendingPasswordRestore
}

data class BackupUiState(
    val lastBackupAt: Long? = null,
    val working: Boolean = false,
    val message: String? = null, // calm info/success (e.g. "Backed up just now")
    val blockingError: String? = null, // a LOUD failure the user must acknowledge (e.g. a backup that didn't write)
    val autoBackupEnabled: Boolean = false,
    val hasBackupPassword: Boolean = false,
    val backups: List<DriveFile>? = null, // non-null => show the restore picker
    val passwordRestore: PendingPasswordRestore? = null, // non-null => prompt for the recovery password
    val restoreComplete: Boolean = false, // one-shot: a restore just succeeded (onboarding uses it to go Home)
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val driveAuthManager: DriveAuthManager,
    private val settingsRepository: SettingsRepository,
    private val excelExporter: ExcelExporter,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState(hasBackupPassword = backupRepository.hasBackupPassword()))
    val state: StateFlow<BackupUiState> =
        combine(_state, backupRepository.lastBackupAt, settingsRepository.settings) { s, last, settings ->
            s.copy(lastBackupAt = last, autoBackupEnabled = settings.autoBackupEnabled)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    /** Suggested filename for a local export (encrypted container). */
    val exportFileName: String get() = "spends-backup.spsenc"

    /** Suggested filename for the readable spreadsheet export. */
    val excelFileName: String get() = "Spends.xlsx"

    /** Build a single-sheet .xlsx of all transactions and write it to the chosen file (readable, not encrypted). */
    fun exportExcel(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(working = true, message = null, blockingError = null) }
            try {
                val bytes = excelExporter.build()
                check(bytes.isNotEmpty()) { "The spreadsheet came out empty." }
                writeAllBytes(uri, bytes)
                _state.update { it.copy(working = false, message = "Spreadsheet exported (${bytes.size / 1024} KB)") }
            } catch (e: Exception) {
                deleteFailedFile(uri) // never leave a 0-byte/partial file behind
                _state.update { it.copy(working = false, blockingError = "Couldn't export the spreadsheet, so nothing was saved. ${e.message ?: ""}".trim()) }
            }
        }
    }

    fun setAutoBackup(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setAutoBackupEnabled(enabled) }

    /** Set/replace the recovery password that encrypts every backup. */
    fun setBackupPassword(password: String) {
        viewModelScope.launch {
            _state.update { it.copy(working = true, message = null) }
            try {
                backupRepository.setBackupPassword(password.toCharArray())
                _state.update {
                    it.copy(working = false, hasBackupPassword = true, message = "Backups are now encrypted with your password.")
                }
            } catch (e: Exception) {
                _state.update { it.copy(working = false, message = "Couldn't set the password. ${e.message ?: ""}".trim()) }
            }
        }
    }

    fun exportToFile(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(working = true, message = null, blockingError = null) }
            try {
                // Build the (encrypted) bytes FIRST; if this throws (e.g. no password set) we never write
                // and we delete the empty document the file picker pre-created — no more 0-byte "backups".
                val bytes = backupRepository.buildBackupBytes()
                check(bytes.isNotEmpty()) { "The backup came out empty." }
                writeAllBytes(uri, bytes)
                _state.update { it.copy(working = false, message = "Encrypted backup saved (${bytes.size / 1024} KB)") }
            } catch (e: BackupNotProtectedException) {
                deleteFailedFile(uri)
                _state.update { it.copy(working = false, blockingError = e.message) }
            } catch (e: Exception) {
                deleteFailedFile(uri)
                _state.update { it.copy(working = false, blockingError = "Couldn't save the backup, so nothing was written. ${e.message ?: ""}".trim()) }
            }
        }
    }

    /** Write all [bytes] to [uri]. Throws on a write failure or a *proven* short write (caller deletes the file). */
    private suspend fun writeAllBytes(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: throw IllegalStateException("Couldn't open the chosen file.")
        // Best-effort integrity check. Only a definitive, NON-ZERO mismatch proves a short/corrupt write.
        // If the provider can't re-read the freshly-created doc (null / 0 / throws), the write+flush+close
        // already succeeded — do NOT treat that as a failure, or we'd delete a perfectly good backup.
        val written = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().size }
        }.getOrNull()
        check(written == null || written == 0 || written == bytes.size) {
            "The file didn't save completely ($written of ${bytes.size} bytes)."
        }
    }

    /** Delete a document the SAF picker pre-created, so a failed export never leaves an empty/partial file. */
    private suspend fun deleteFailedFile(uri: Uri) = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }

    fun clearBlockingError() = _state.update { it.copy(blockingError = null) }

    fun importFromFile(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(working = true, message = null) }
            try {
                val bytes = readBytes(uri)
                backupRepository.restoreFromBytes(bytes)
                _state.update { it.copy(working = false, message = "Restored from file") }
            } catch (e: BackupNeedsPasswordException) {
                _state.update { it.copy(working = false, passwordRestore = PendingPasswordRestore.Local(uri)) }
            } catch (e: Exception) {
                _state.update { it.copy(working = false, message = "Couldn't restore that file. ${e.message ?: ""}".trim()) }
            }
        }
    }

    // Consent (account-pick) intents to launch from the UI.
    private val consentChannel = Channel<IntentSender>(Channel.BUFFERED)
    val consentRequests: Flow<IntentSender> = consentChannel.receiveAsFlow()

    private var pendingOp: (suspend (String) -> Unit)? = null

    fun backupNow() = withToken { token ->
        backupRepository.backupNow(token)
        _state.update { it.copy(working = false, message = "Backed up just now") }
    }

    fun openRestore() = withToken { token ->
        val list = backupRepository.listBackups(token)
        _state.update {
            it.copy(
                working = false,
                backups = list,
                message = if (list.isEmpty()) "No backups found in Drive yet" else null,
            )
        }
    }

    fun restore(fileId: String) = withToken { token ->
        try {
            backupRepository.restoreFrom(token, fileId)
            _state.update { it.copy(working = false, backups = null, message = "Restored from Drive", restoreComplete = true) }
        } catch (e: BackupNeedsPasswordException) {
            _state.update { it.copy(working = false, backups = null, passwordRestore = PendingPasswordRestore.Drive(fileId)) }
        }
    }

    /** Consume the one-shot restore-complete flag (after the UI has reacted, e.g. navigated Home). */
    fun consumeRestoreComplete() = _state.update { it.copy(restoreComplete = false) }

    /** Complete a restore that needed the recovery password (new-device path). */
    fun restoreWithPassword(password: String) {
        when (val pending = _state.value.passwordRestore) {
            is PendingPasswordRestore.Drive -> withToken { token ->
                try {
                    backupRepository.restoreFromWithPassword(token, pending.fileId, password.toCharArray())
                    _state.update { it.copy(working = false, passwordRestore = null, message = "Restored from Drive", restoreComplete = true) }
                } catch (e: WrongBackupPasswordException) {
                    // Keep the prompt open (passwordRestore stays) so the user can retry; it shows e.message.
                    _state.update { it.copy(working = false, message = e.message) }
                } catch (e: Exception) {
                    // A non-password failure (network / corrupt backup) AFTER the password was accepted —
                    // dismiss the prompt and surface it plainly, not as if the password were wrong.
                    _state.update { it.copy(working = false, passwordRestore = null, message = friendly(e)) }
                }
            }
            is PendingPasswordRestore.Local -> viewModelScope.launch {
                _state.update { it.copy(working = true, message = null) }
                try {
                    backupRepository.restoreFromBytesWithPassword(readBytes(pending.uri), password.toCharArray())
                    _state.update { it.copy(working = false, passwordRestore = null, message = "Restored from file", restoreComplete = true) }
                } catch (e: WrongBackupPasswordException) {
                    _state.update { it.copy(working = false, message = e.message) }
                } catch (e: Exception) {
                    _state.update { it.copy(working = false, passwordRestore = null, message = friendly(e)) }
                }
            }
            null -> Unit
        }
    }

    fun cancelPasswordRestore() = _state.update { it.copy(passwordRestore = null) }
    fun dismissRestore() = _state.update { it.copy(backups = null) }
    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Resolve an access token (prompting consent if needed) then run [op]. */
    private fun withToken(op: suspend (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(working = true, message = null, blockingError = null) }
            try {
                when (val result = driveAuthManager.authorize()) {
                    is DriveAuthManager.AuthResult.Authorized -> op(result.accessToken)
                    is DriveAuthManager.AuthResult.NeedsConsent -> {
                        pendingOp = op
                        consentChannel.send(result.intentSender)
                    }
                }
            } catch (e: BackupNotProtectedException) {
                _state.update { it.copy(working = false, blockingError = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(working = false, message = friendly(e)) }
            }
        }
    }

    fun onConsentResult(data: Intent?) {
        val op = pendingOp
        pendingOp = null
        if (data == null || op == null) {
            _state.update { it.copy(working = false, message = "Drive sign-in was cancelled") }
            return
        }
        viewModelScope.launch {
            try {
                val token = driveAuthManager.accessTokenFromConsent(data)
                op(token)
            } catch (e: BackupNotProtectedException) {
                _state.update { it.copy(working = false, blockingError = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(working = false, message = friendly(e)) }
            }
        }
    }

    private suspend fun readBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Couldn't open the chosen file.")
    }

    private fun friendly(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() }
            ?: "Couldn't reach Google Drive. Make sure Drive backup is set up for this app."
}
