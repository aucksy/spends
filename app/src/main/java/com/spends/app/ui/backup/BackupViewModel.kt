package com.spends.app.ui.backup

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.backup.BackupRepository
import com.spends.app.data.backup.DriveAuthManager
import com.spends.app.data.backup.DriveFile
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

data class BackupUiState(
    val lastBackupAt: Long? = null,
    val working: Boolean = false,
    val message: String? = null,
    val autoBackupEnabled: Boolean = false,
    val backups: List<DriveFile>? = null, // non-null => show the restore picker
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val driveAuthManager: DriveAuthManager,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> =
        combine(_state, backupRepository.lastBackupAt, settingsRepository.settings) { s, last, settings ->
            s.copy(lastBackupAt = last, autoBackupEnabled = settings.autoBackupEnabled)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    /** Suggested filename for a local export. */
    val exportFileName: String get() = "spends-backup.json.gz"

    fun setAutoBackup(enabled: Boolean) =
        viewModelScope.launch { settingsRepository.setAutoBackupEnabled(enabled) }

    fun exportToFile(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(working = true, message = null) }
            try {
                val bytes = backupRepository.buildBackupBytes()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("Couldn't open the chosen file.")
                }
                _state.update { it.copy(working = false, message = "Backup saved to file") }
            } catch (e: Exception) {
                _state.update { it.copy(working = false, message = "Couldn't save the file. ${e.message ?: ""}".trim()) }
            }
        }
    }

    fun importFromFile(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(working = true, message = null) }
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Couldn't open the chosen file.")
                }
                backupRepository.restoreFromBytes(bytes)
                _state.update { it.copy(working = false, message = "Restored from file") }
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
        backupRepository.restoreFrom(token, fileId)
        _state.update { it.copy(working = false, backups = null, message = "Restored from Drive") }
    }

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

    private fun friendly(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() }
            ?: "Couldn't reach Google Drive. Make sure Drive backup is set up for this app."
}
