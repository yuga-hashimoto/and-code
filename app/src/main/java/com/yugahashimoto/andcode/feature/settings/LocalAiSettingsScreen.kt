package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.LocalAiModel
import com.yugahashimoto.andcode.runtime.local.LocalAiState

/**
 * Settings for the embedded on-device AI (llama-server over OpenCL).
 *
 * Lists the GGUF models found on the device, lets the user pick one and start/stop the local
 * server. The running provider is advertised to opencode automatically (see LocalAiProviderSync).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAiSettingsScreen(
    state: LocalAiUiState,
    onSelect: (LocalAiModel) -> Unit,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_local_ai_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_local_ai_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StatusBanner(state.runtimeState)

            SettingsSection(title = stringResource(R.string.settings_local_ai_models_section)) {
                if (state.importResult != null) {
                    Text(
                        text = importResultLabel(state.importResult),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                when {
                    state.scanning -> {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.settings_local_ai_scanning))
                        }
                    }
                    state.models.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.settings_local_ai_no_models),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                        ImportButton(
                            importing = state.importing,
                            onClick = onImport,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {
                        state.models.forEachIndexed { index, model ->
                            ModelRow(
                                model = model,
                                selected = model.file == state.selectedModel?.file,
                                onClick = { onSelect(model) },
                            )
                            if (index != state.models.lastIndex) HorizontalDivider()
                        }
                        ImportButton(
                            importing = state.importing,
                            onClick = onImport,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                    enabled = !state.importing,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.settings_local_ai_refresh))
                }
                when (state.runtimeState) {
                    LocalAiState.Running -> {
                        Button(onClick = onStop, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.settings_local_ai_stop))
                        }
                    }
                    else -> {
                        Button(
                            onClick = onStart,
                            modifier = Modifier.weight(1f),
                            enabled = state.selectedModel != null && !state.runtimeState.isTransitioning,
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.settings_local_ai_start))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun importResultLabel(result: ImportResult): String {
    val errors = result.errors.joinToString(", ")
    return when {
        result.ok == 0 && result.errors.isEmpty() -> stringResource(R.string.settings_local_ai_import_none)
        result.ok == 0 && result.errors.isNotEmpty() ->
            stringResource(R.string.settings_local_ai_import_failed, errors)
        result.errors.isEmpty() && result.skipped == 0 ->
            stringResource(R.string.settings_local_ai_import_done, result.ok)
        else ->
            stringResource(R.string.settings_local_ai_import_partial, result.ok, result.skipped, errors)
    }
}

@Composable
private fun ImportButton(
    importing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = !importing,
    ) {
        if (importing) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
        } else {
            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.settings_local_ai_import))
        }
    }
}

private val LocalAiState.isTransitioning: Boolean
    get() = this == LocalAiState.Starting || this == LocalAiState.Stopping

@Composable
private fun StatusBanner(state: LocalAiState) {
    val (label, color) =
        when (state) {
            LocalAiState.Idle -> stringResource(R.string.settings_local_ai_status_idle) to MaterialTheme.colorScheme.onSurfaceVariant
            LocalAiState.Starting -> stringResource(R.string.settings_local_ai_status_starting) to MaterialTheme.colorScheme.primary
            LocalAiState.Running -> stringResource(R.string.settings_local_ai_status_running) to MaterialTheme.colorScheme.tertiary
            LocalAiState.Stopping -> stringResource(R.string.settings_local_ai_status_stopping) to MaterialTheme.colorScheme.primary
            LocalAiState.Failed -> stringResource(R.string.settings_local_ai_status_failed) to MaterialTheme.colorScheme.error
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Chat, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ModelRow(
    model: LocalAiModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = model.sizeLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
