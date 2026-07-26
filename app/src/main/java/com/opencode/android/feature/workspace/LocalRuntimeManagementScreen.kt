package com.opencode.android.feature.workspace

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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.runtime.LocalRuntimeStatus
import com.opencode.android.runtime.local.LocalRuntimeDiagnostics
import com.opencode.android.runtime.local.LocalRuntimeOperationResult
import com.opencode.android.runtime.local.LocalRuntimeUpdateCheck
import com.opencode.android.ui.components.SectionCard
import com.opencode.android.ui.components.StatusChip
import java.util.Locale

@Composable
fun LocalRuntimeManagementScreen(
    state: LocalRuntimeManagementUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onRepair: () -> Unit,
    onRequestUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onConfirmUpdate: () -> Unit,
    onRequestRollback: () -> Unit,
    onDismissRollback: () -> Unit,
    onConfirmRollback: () -> Unit,
    onRequestDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
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
                        enabled = !state.isLoading && !state.isCheckingUpdate && !busy,
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
                RuntimeUpdateCard(
                    state = state,
                    freeBytes = diagnostics.freeBytes,
                    busy = busy,
                    onCheckForUpdate = onCheckForUpdate,
                    onRequestUpdate = onRequestUpdate,
                    onRequestRollback = onRequestRollback,
                )
                RuntimeToolsCard(diagnostics)
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

    val available = state.updateCheck as? LocalRuntimeUpdateCheck.Available
    if (state.showUpdateConfirmation && available != null) {
        AlertDialog(
            onDismissRequest = onDismissUpdate,
            title = { Text(stringResource(R.string.update_confirm_title, available.release.version)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.update_confirm_body1))
                    Text(stringResource(R.string.update_confirm_body2, available.currentVersion))
                    Text(
                        stringResource(R.string.required_free_space, formatBytes(available.release.asset.requiredFreeBytes)),
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmUpdate) { Text(stringResource(R.string.update_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissUpdate) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (state.showRollbackConfirmation && !state.rollbackVersion.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = onDismissRollback,
            title = { Text(stringResource(R.string.rollback_confirm_title, state.rollbackVersion)) },
            text = {
                Text(stringResource(R.string.rollback_confirm_body))
            },
            confirmButton = {
                TextButton(onClick = onConfirmRollback) { Text(stringResource(R.string.rollback_button)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissRollback) { Text(stringResource(R.string.cancel)) }
            },
        )
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
}

@Composable
private fun RuntimeUpdateProgressCard(status: LocalRuntimeStatus.Updating) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.SystemUpdate, contentDescription = stringResource(R.string.cd_updating))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.runtime_version_transition, status.currentVersion, status.targetVersion),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(status.step, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (status.progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { status.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${(status.progress.coerceIn(0f, 1f) * 100).toInt()}%",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RuntimeOperationResultCard(result: LocalRuntimeOperationResult) {
    val (icon, title, detail, isError) =
        when (result) {
            is LocalRuntimeOperationResult.UpdateSkipped ->
                OperationPresentation(
                    Icons.Default.CheckCircle,
                    stringResource(R.string.update_skipped_title),
                    stringResource(R.string.current_version_label, result.version),
                    false,
                )
            is LocalRuntimeOperationResult.Updated ->
                OperationPresentation(
                    Icons.Default.CheckCircle,
                    stringResource(R.string.update_success_title),
                    stringResource(R.string.version_transition, result.fromVersion, result.toVersion),
                    false,
                )
            is LocalRuntimeOperationResult.AutomaticRollback ->
                OperationPresentation(
                    Icons.Default.Warning,
                    stringResource(R.string.auto_rollback_title),
                    stringResource(R.string.auto_rollback_detail, result.failedVersion, result.restoredVersion, result.reason),
                    false,
                )
            is LocalRuntimeOperationResult.RolledBack ->
                OperationPresentation(
                    Icons.Default.History,
                    stringResource(R.string.rollback_success_title),
                    stringResource(R.string.version_transition, result.fromVersion, result.toVersion),
                    false,
                )
            is LocalRuntimeOperationResult.RollbackFailedRestored ->
                OperationPresentation(
                    Icons.Default.Warning,
                    stringResource(R.string.rollback_failed_restored_title),
                    stringResource(
                        R.string.rollback_failed_restored_detail,
                        result.attemptedVersion,
                        result.restoredVersion,
                        result.reason,
                    ),
                    false,
                )
            is LocalRuntimeOperationResult.Failed ->
                OperationPresentation(
                    Icons.Default.Error,
                    stringResource(R.string.operation_failed_generic, result.operation),
                    result.message,
                    true,
                )
        }
    SectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(
                icon,
                contentDescription = stringResource(R.string.cd_operation_result),
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
        MetricRow("OpenCode", diagnostics.version ?: "—")
        MetricRow("ABI", diagnostics.abi.ifBlank { "—" })
        MetricRow(stringResource(R.string.port_label), diagnostics.port?.toString() ?: "—")
        diagnostics.process?.let { process ->
            MetricRow("PID", process.pid?.toString() ?: stringResource(R.string.unavailable_value))
            MetricRow(
                stringResource(R.string.memory_label),
                process.rssBytes?.let(::formatBytes) ?: stringResource(R.string.unavailable_value),
            )
            MetricRow(stringResource(R.string.uptime_label), formatDuration(process.uptimeMillis))
        }
    }
}

@Composable
private fun RuntimeStorageCard(diagnostics: LocalRuntimeDiagnostics) {
    SectionCard {
        Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        MetricRow(stringResource(R.string.runtime_usage_label), formatBytes(diagnostics.runtimeBytes))
        MetricRow(stringResource(R.string.device_free_space_label), formatBytes(diagnostics.freeBytes))
    }
}

@Composable
private fun RuntimeUpdateCard(
    state: LocalRuntimeManagementUiState,
    freeBytes: Long,
    busy: Boolean,
    onCheckForUpdate: () -> Unit,
    onRequestUpdate: () -> Unit,
    onRequestRollback: () -> Unit,
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.opencode_update_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.isCheckingUpdate) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(8.dp))

        when (val check = state.updateCheck) {
            null -> {
                Text(stringResource(R.string.check_update_source_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onCheckForUpdate,
                    enabled = !state.isCheckingUpdate && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_check_update))
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.check_for_update_button))
                }
            }
            is LocalRuntimeUpdateCheck.UpToDate -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.cd_up_to_date),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(R.string.up_to_date_label, check.currentVersion), fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCheckForUpdate,
                    enabled = !state.isCheckingUpdate && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.recheck_button))
                }
            }
            is LocalRuntimeUpdateCheck.Available -> {
                val requiredBytes = check.release.asset.requiredFreeBytes
                val enoughSpace = freeBytes >= requiredBytes
                Text(
                    stringResource(R.string.update_available_label, check.release.version),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                MetricRow(stringResource(R.string.current_version_metric), check.currentVersion)
                MetricRow(stringResource(R.string.download_size_label), formatBytes(check.release.asset.sizeBytes))
                MetricRow(stringResource(R.string.required_free_space_metric), formatBytes(requiredBytes))
                if (!enoughSpace) {
                    Text(
                        stringResource(R.string.insufficient_space, formatBytes(freeBytes)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (check.release.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.release_notes_label), fontWeight = FontWeight.Medium)
                    Text(
                        check.release.releaseNotes,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onRequestUpdate,
                    enabled = !busy && !state.isCheckingUpdate && enoughSpace,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = stringResource(R.string.cd_update))
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.update_to_button, check.release.version))
                }
            }
        }

        state.updateError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        state.rollbackVersion?.let { rollbackVersion ->
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.previous_version_label), fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.revert_available_note, rollbackVersion),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRequestRollback,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.History, contentDescription = stringResource(R.string.cd_rollback))
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(stringResource(R.string.rollback_to_button, rollbackVersion))
            }
        }
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

private data class OperationPresentation(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val detail: String,
    val isError: Boolean,
)

@Composable
private fun LocalRuntimeStatus.displayName(): String =
    when (this) {
        LocalRuntimeStatus.NotInstalled -> stringResource(R.string.runtime_status_not_installed)
        is LocalRuntimeStatus.Installing -> stringResource(R.string.runtime_status_setting_up)
        is LocalRuntimeStatus.Starting -> stringResource(R.string.runtime_status_starting)
        is LocalRuntimeStatus.Updating -> stringResource(R.string.runtime_status_updating)
        is LocalRuntimeStatus.Stopped -> stringResource(R.string.runtime_status_stopped)
        is LocalRuntimeStatus.Ready -> stringResource(R.string.runtime_status_ready_running)
        is LocalRuntimeStatus.Broken -> stringResource(R.string.runtime_status_problem)
        is LocalRuntimeStatus.UnsupportedAbi -> stringResource(R.string.runtime_status_unsupported)
    }

private fun LocalRuntimeStatus.isInstalled(): Boolean =
    this !is LocalRuntimeStatus.NotInstalled && this !is LocalRuntimeStatus.UnsupportedAbi

private fun LocalRuntimeStatus.isBusy(): Boolean =
    this is LocalRuntimeStatus.Installing ||
        this is LocalRuntimeStatus.Starting ||
        this is LocalRuntimeStatus.Updating

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

@Composable
private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0 -> stringResource(R.string.duration_hours_minutes, hours, minutes)
        minutes > 0 -> stringResource(R.string.duration_minutes_seconds, minutes, seconds)
        else -> stringResource(R.string.duration_seconds, seconds)
    }
}
