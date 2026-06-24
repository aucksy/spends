package com.spends.app.ui.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.importer.ImportException
import com.spends.app.data.importer.ImportRepository
import com.spends.app.data.importer.ImportSummary
import com.spends.app.data.importer.ParsedImport
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object Parsing : ImportUiState
    data class Preview(val parsed: ParsedImport, val fileName: String, val isMonito: Boolean) : ImportUiState
    data class Committing(val done: Int, val total: Int) : ImportUiState
    data class Done(val summary: ImportSummary) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ImportRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state

    fun onFilePicked(uri: Uri) {
        _state.value = ImportUiState.Parsing
        viewModelScope.launch {
            try {
                val name = displayName(uri)
                val parsed = withContext(Dispatchers.IO) {
                    repository.parse(
                        openStream = {
                            context.contentResolver.openInputStream(uri)
                                ?: throw ImportException("Couldn't open that file.")
                        },
                        fileName = name,
                    )
                }
                _state.value = if (parsed.count == 0) {
                    ImportUiState.Error("No transactions found in that file. ${parsed.issues.firstOrNull()?.reason ?: ""}".trim())
                } else {
                    ImportUiState.Preview(parsed, name, isMonito = parsed.isMonito)
                }
            } catch (e: ImportException) {
                _state.value = ImportUiState.Error(e.message ?: "Import failed.")
            } catch (e: Exception) {
                _state.value = ImportUiState.Error("Couldn't read that file. ${e.message ?: ""}".trim())
            }
        }
    }

    fun confirm() {
        val preview = _state.value as? ImportUiState.Preview ?: return
        _state.value = ImportUiState.Committing(0, preview.parsed.count)
        viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    repository.commit(preview.parsed) { done, total ->
                        _state.value = ImportUiState.Committing(done, total)
                    }
                }
                _state.value = ImportUiState.Done(summary)
            } catch (e: Exception) {
                _state.value = ImportUiState.Error("Import failed while saving. ${e.message ?: ""}".trim())
            }
        }
    }

    fun reset() { _state.value = ImportUiState.Idle }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "import"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { name = it }
                }
            }
        }
        return name
    }
}
