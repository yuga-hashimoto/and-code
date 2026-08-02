package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface LegalDocState {
    data object Loading : LegalDocState

    data class Loaded(val lines: List<String>) : LegalDocState

    data object Failed : LegalDocState
}

/** Renders a bundled legal/OSS document asset offline - no network request is made to show it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalDocumentScreen(
    document: LegalDocument,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val languageTag = context.resources.configuration.locales[0].language
    val state by
        produceState<LegalDocState>(initialValue = LegalDocState.Loading, document, languageTag) {
            value =
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.assets.open(document.assetPathFor(languageTag)).bufferedReader().use { it.readText() }
                    }
                }.fold(
                    onSuccess = { text -> LegalDocState.Loaded(text.lines()) },
                    onFailure = { LegalDocState.Failed },
                )
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(document.titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            LegalDocState.Loading ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            LegalDocState.Failed ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.legal_doc_load_error))
                }
            is LegalDocState.Loaded ->
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(current.lines) { line -> LegalDocLine(line) }
                    }
                }
        }
    }
}

@Composable
private fun LegalDocLine(line: String) {
    when {
        line.startsWith("# ") ->
            Text(
                line.removePrefix("# "),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        line.startsWith("## ") ->
            Text(
                line.removePrefix("## "),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
        line.startsWith("### ") ->
            Text(
                line.removePrefix("### "),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
        line.startsWith("- ") || line.startsWith("* ") ->
            Text("•  ${line.drop(2)}", style = MaterialTheme.typography.bodyMedium)
        line.isBlank() -> Text("", style = MaterialTheme.typography.bodySmall)
        else -> Text(line, style = MaterialTheme.typography.bodyMedium)
    }
}
