package com.yugahashimoto.andcode.feature.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.local.AdbConnectionState
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeDiagnostics
import com.yugahashimoto.andcode.ui.components.RuntimeOperationResultCard
import com.yugahashimoto.andcode.ui.components.RuntimeUpdateProgressCard
import com.yugahashimoto.andcode.ui.components.SectionCard
import com.yugahashimoto.andcode.ui.components.StatusChip
import com.yugahashimoto.andcode.ui.components.displayName
import com.yugahashimoto.andcode.ui.components.formatRuntimeBytes
import com.yugahashimoto.andcode.ui.components.formatRuntimeDuration

@Composable
fun LocalRuntimeManagementScreen(
    state: LocalRuntimeManagementUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRepair: () -> Unit,
    onRequestDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onShowAdbPairDialog: () -> Unit = {},
    onDismissAdbPairDialog: () -> Unit = {},
    onAdbPair: (Int, String) -> Unit = { _, _ -> },
    onAdbConnect: (Int) -> Unit = {},
    onAdbDisconnect: () -> Unit = {},
) {
    val busy = state.runtimeStatus.isBusy() || state.isDeleting
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.local_runtime_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        enabled = !state.isLoading && !busy,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_diagnostics_description))
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            (state.runtimeStatus as? LocalRuntimeStatus.Updating)?.let { status ->
                RuntimeUpdateProgressCard(status)
            }
            state.lastOperation?.let { operation ->
                RuntimeOperationResultCard(operation)
            }

            state.diagnostics?.let { diagnostics ->
                RuntimeSummaryCard(diagnostics)
                RuntimeStorageCard(diagnostics)
                RuntimeToolsCard(diagnostics)
                AdbSetupCard(
                    adbState = state.adbState,
                    isPairing = state.isAdbPairing,
                    isConnecting = state.isAdbConnecting,
                    onShowPairDialog = onShowAdbPairDialog,
                    onConnect = onAdbConnect,
                    onDisconnect = onAdbDisconnect,
                )
                RuntimeLogsCard(diagnostics.logTail)

                if (diagnostics.status.isInstalled()) {
                    RuntimeManagementCard(
                        busy = busy,
                        isDeleting = state.isDeleting,
                        onRepair = onRepair,
                        onRequestDelete = onRequestDelete,
                    )
                }
            }

            state.error?.let { error ->
                ErrorCard(stringResource(R.string.operation_failed_title), error)
            }
        }
    }

    if (state.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text(stringResource(R.string.delete_runtime_confirm_title)) },
            text = {
                Text(stringResource(R.string.delete_runtime_confirm_body))
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) {
                    Text(stringResource(R.string.delete_completely), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (state.showAdbPairDialog) {
        AdbPairDialog(
            isPairing = state.isAdbPairing,
            onDismiss = onDismissAdbPairDialog,
            onPair = onAdbPair,
        )
    }
}

@Composable
private fun RuntimeSummaryCard(diagnostics: LocalRuntimeDiagnostics) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.runtime_status_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            StatusChip(
                text = diagnostics.status.displayName(),
                active = diagnostics.status is LocalRuntimeStatus.Ready,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.local_runtime_shared_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        MetricRow("ABI", diagnostics.abi.ifBlank { "—" })
        diagnostics.process?.let { process ->
            MetricRow("PID", process.pid?.toString() ?: stringResource(R.string.unavailable_value))
            MetricRow(
                stringResource(R.string.memory_label),
                process.rssBytes?.let(::formatRuntimeBytes) ?: stringResource(R.string.unavailable_value),
            )
            MetricRow(stringResource(R.string.uptime_label), formatRuntimeDuration(process.uptimeMillis))
        }
    }
}

@Composable
private fun RuntimeStorageCard(diagnostics: LocalRuntimeDiagnostics) {
    SectionCard {
        Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        MetricRow(stringResource(R.string.runtime_usage_label), formatRuntimeBytes(diagnostics.runtimeBytes))
        MetricRow(stringResource(R.string.device_free_space_label), formatRuntimeBytes(diagnostics.freeBytes))
    }
}

@Composable
private fun RuntimeToolsCard(diagnostics: LocalRuntimeDiagnostics) {
    SectionCard {
        Text(stringResource(R.string.required_tools_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        diagnostics.tools.forEach { tool ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (tool.available) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = stringResource(R.string.cd_tool_status),
                    tint = if (tool.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(tool.label, fontWeight = FontWeight.Medium)
                    Text(
                        tool.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeLogsCard(logTail: String) {
    SectionCard {
        Text(stringResource(R.string.latest_logs_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (logTail.isBlank()) {
            Text(stringResource(R.string.no_logs), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(240.dp),
            ) {
                Text(
                    text = logTail,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RuntimeManagementCard(
    busy: Boolean,
    isDeleting: Boolean,
    onRepair: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    SectionCard {
        Text(stringResource(R.string.management_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onRepair,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Build, contentDescription = stringResource(R.string.cd_repair))
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(stringResource(R.string.repair_and_resetup_button))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onRequestDelete,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isDeleting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = stringResource(R.string.cd_delete_runtime),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(
                if (isDeleting) stringResource(R.string.deleting_label) else stringResource(R.string.delete_runtime_completely_button),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            stringResource(R.string.delete_runtime_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(
    title: String,
    detail: String,
) {
    SectionCard {
        Text(title, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}

private fun LocalRuntimeStatus.isInstalled(): Boolean =
    this !is LocalRuntimeStatus.NotInstalled && this !is LocalRuntimeStatus.UnsupportedAbi

private fun LocalRuntimeStatus.isBusy(): Boolean =
    this is LocalRuntimeStatus.Installing ||
        this is LocalRuntimeStatus.Starting ||
        this is LocalRuntimeStatus.Updating

@Composable
private fun AdbSetupCard(
    adbState: AdbConnectionState,
    isPairing: Boolean,
    isConnecting: Boolean,
    onShowPairDialog: () -> Unit,
    onConnect: (Int) -> Unit,
    onDisconnect: () -> Unit,
) {
    SectionCard {
        Text(
            stringResource(R.string.adb_setup_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.adb_setup_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        val statusText =
            when (adbState) {
                is AdbConnectionState.Disconnected -> stringResource(R.string.adb_status_disconnected)
                is AdbConnectionState.Discovered -> stringResource(R.string.adb_status_discovered, adbState.port)
                is AdbConnectionState.Pairing -> stringResource(R.string.adb_pairing_in_progress)
                is AdbConnectionState.Connected -> stringResource(R.string.adb_status_connected, adbState.port)
                is AdbConnectionState.Error -> adbState.message
            }
        val isError = adbState is AdbConnectionState.Error
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (adbState is AdbConnectionState.Connected) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint =
                    when {
                        adbState is AdbConnectionState.Connected -> MaterialTheme.colorScheme.primary
                        isError -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(18.dp),
            )
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (adbState) {
                is AdbConnectionState.Disconnected, is AdbConnectionState.Error -> {
                    OutlinedButton(onClick = onShowPairDialog, enabled = !isPairing) {
                        Text(stringResource(R.string.adb_pair_button))
                    }
                }
                is AdbConnectionState.Discovered -> {
                    Button(
                        onClick = { onConnect(adbState.port) },
                        enabled = !isConnecting,
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(stringResource(R.string.adb_connect_button))
                    }
                    OutlinedButton(onClick = onShowPairDialog, enabled = !isPairing) {
                        Text(stringResource(R.string.adb_pair_button))
                    }
                }
                is AdbConnectionState.Pairing -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                is AdbConnectionState.Connected -> {
                    OutlinedButton(onClick = onDisconnect) {
                        Text(stringResource(R.string.adb_disconnect_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun AdbPairDialog(
    isPairing: Boolean,
    onDismiss: () -> Unit,
    onPair: (Int, String) -> Unit,
) {
    var portText by remember { mutableStateOf("") }
    var codeText by remember { mutableStateOf("") }
    val port = portText.toIntOrNull()
    val canPair = port != null && port in 1..65535 && codeText.length == 6 && !isPairing

    AlertDialog(
        onDismissRequest = { if (!isPairing) onDismiss() },
        title = { Text(stringResource(R.string.adb_pair_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.adb_pair_dialog_instructions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.adb_pair_port_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = codeText,
                    onValueChange = { codeText = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.adb_pair_code_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isPairing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.adb_pairing_in_progress), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { port?.let { onPair(it, codeText) } },
                enabled = canPair,
            ) {
                Text(stringResource(R.string.adb_pair_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isPairing) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
