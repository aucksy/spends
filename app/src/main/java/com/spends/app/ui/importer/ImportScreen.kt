package com.spends.app.ui.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onFinished: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onFilePicked)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import data") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().padding(20.dp)) {
            when (val s = state) {
                is ImportUiState.Idle -> IdleContent(onPick = { picker.launch(arrayOf("*/*")) })
                is ImportUiState.Parsing -> CenteredProgress("Reading your file…")
                is ImportUiState.Preview -> PreviewContent(s, onConfirm = viewModel::confirm, onCancel = viewModel::reset)
                is ImportUiState.Committing -> CommittingContent(s)
                is ImportUiState.Done -> DoneContent(s, onFinished)
                is ImportUiState.Error -> ErrorContent(s, onRetry = viewModel::reset)
            }
        }
    }
}

@Composable
private fun IdleContent(onPick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Import your history", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a Monito Excel export (.xls) or any .csv. Every category is kept exactly as-is, and " +
                "duplicates are skipped if you import again. Nothing leaves your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPick, modifier = Modifier.height(52.dp)) { Text("Choose file") }
    }
}

@Composable
private fun CenteredProgress(label: String) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PreviewContent(s: ImportUiState.Preview, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val parsed = s.parsed
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(s.fileName, style = MaterialTheme.typography.titleMedium)
        Text(
            if (s.isMonito) "Monito export detected" else "Spreadsheet detected",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        StatLine("${parsed.count}", "transactions to import")
        StatLine("${parsed.categories.size}", "categories (all preserved)")
        if (parsed.issues.isNotEmpty()) StatLine("${parsed.issues.size}", "rows skipped (couldn't read)")

        val excluded = parsed.categories.filter { it.excludeFromSpend }.map { it.name }
        if (excluded.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Kept out of spend charts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(excluded.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Import ${parsed.count} transactions")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatLine(value: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun CommittingContent(s: ImportUiState.Committing) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        val fraction = if (s.total > 0) s.done.toFloat() / s.total else 0f
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Text("Importing… ${s.done} / ${s.total}", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DoneContent(s: ImportUiState.Done, onFinished: () -> Unit) {
    val sum = s.summary
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Imported ${sum.imported}", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            buildString {
                append("${sum.categoriesCreated} new categories")
                if (sum.duplicatesSkipped > 0) append(" · ${sum.duplicatesSkipped} duplicates skipped")
                if (sum.issues > 0) append(" · ${sum.issues} rows needed review")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onFinished, modifier = Modifier.height(52.dp)) { Text("Done") }
    }
}

@Composable
private fun ErrorContent(s: ImportUiState.Error, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Couldn't import", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Choose another file") }
    }
}
