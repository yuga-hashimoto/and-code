package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.AND_CODE_AGENT_CONTEXT_ASSET
import kotlinx.coroutines.launch

/**
 * Lets the user add one set of instructions that every agent receives.
 *
 * The text is stored in settings and appended to the bundled AndCode context when the runtime
 * writes each agent's own instruction file (OpenCode's instructions file, Claude Code's
 * CLAUDE.md, Antigravity's GEMINI.md and Codex's AGENTS.md), so a rule only has to be written once
 * instead of once per harness. The bundled part is shown read-only: it is what AndCode owns, and
 * the user's addition is kept separate rather than mixed into it.
 */
@Composable
fun AgentInstructionsScreen(
    savedInstructions: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val bundled =
        remember {
            runCatching {
                context.assets.open(AND_CODE_AGENT_CONTEXT_ASSET).bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
    var draft by rememberSaveable(savedInstructions) { mutableStateOf(savedInstructions) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val savedMessage = stringResource(R.string.agent_instructions_saved)

    SettingsScaffold(
        title = stringResource(R.string.agent_instructions_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.agent_instructions_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    label = { Text(stringResource(R.string.agent_instructions_label)) },
                    placeholder = { Text(stringResource(R.string.agent_instructions_hint)) },
                    minLines = 6,
                    maxLines = 14,
                )
            }
            item {
                Button(
                    onClick = {
                        onSave(draft)
                        scope.launch { snackbarHostState.showSnackbar(savedMessage) }
                    },
                    enabled = draft != savedInstructions,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.agent_instructions_save))
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.agent_instructions_builtin_title)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = bundled,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
