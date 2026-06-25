package com.spends.app.ui.backup

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
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
    val message: String? = null,
    val autoBackupEnabled: Boolean = false,
    val hasBackupPassword: Boolean = false,
    val backups: List<DriveFile>? = null, // non-null => show the restore picker
    val passwordRestore: PendingPasswordRestore? = null, // non-null => prompt for the recovery password
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
            _state.update { it.copy(working = true, message = null) }
            try {
                val bytes = excelExporter.build()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("Couldn't open the chosen file.")
                }
                _state.update { it.copy(working = false, message = "Spreadsheet exported") }
            } catch (e: Exception) {
                _state.update { it.copy(working = false, message = "Couldn't export. ${e.message ?: ""}".trim()) }
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
            _state.update { it.copy(working = true, message = null) }
            try {
                val bytes = backupRepository.buildBackupBytes()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("Couldn't open the chosen file.")
                }
                _state.update { it.copy(working = false, message = "Encrypted backup saved to file") }
            } catch (e: BackupNotProtectedException) {
                _state.update { it.copy(working = false, message = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(working = false, message = "Couldn't save the file. ${e.message ?: ""}".trim()) }
            }
        }
    }

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
            _state.update { it.copy(working = false, backups = null, message = "Restored from Drive") }
        } catch (e: BackupNeedsPasswordException) {
            _state.update { it.copy(working = false, backups = null, passwordRestore = PendingPasswordRestore.Drive(fileId)) }
        }
    }

    /** Complete a restore that needed the recovery password (new-device path). */
    fun restoreWithPassword(password: String) {
        when (val pending = _state.value.passwordRestore) {
            is PendingPasswordRestore.Drive -> withToken { token ->
                try {
                    backupRepository.restoreFromWithPassword(token, pending.fileId, password.toCharArray())
                    _state.update { it.copy(working = false, passwordRestore = null, message = "Restored from Drive") }
                } catch (e: WrongBackupPasswordException) {
                    _state.update { it.copy(working = false, message = e.message) }
                }
            }
            is PendingPasswordRestore.Local -> viewModelScope.launch {
                _state.update { it.copy(working = true, message = null) }
                try {
                    backupRepository.restoreFromBytesWithPassword(readBytes(pending.uri), password.toCharArray())
                    _state.update { it.copy(working = false, passwordRestore = null, message = "Restored from file") }
                } catch (e: WrongBackupPasswordException) {
                    _state.update { it.copy(working = false, message = e.message) }
                } catch (e: Exception) {
                    _state.update { it.copy(working = false, message = friendly(e)) }
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
            _state.update { it.copy(working = true, message = null) }
            try {
                when (val result = driveAuthManager.authorize()) {
                    is DriveAuthManager.AuthResult.Authorized -> op(result.accessToken)
                    is DriveAuthManager.AuthResult.NeedsConsent -> {
                        pendingOp = op
                        consentChannel.send(result.intentSender)
                    }
                }
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
