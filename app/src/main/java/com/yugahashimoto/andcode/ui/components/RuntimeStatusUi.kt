package com.yugahashimoto.andcode.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeOperationResult
import java.util.Locale

/**
 * Rendering shared by every screen that reports on the Android-local runtime.
 *
 * The OpenCode agent settings screen and the shared local runtime screen both show the runtime's
 * state, and each having its own copy is how the same status could read two different ways
 * depending on which screen you opened.
 */
@Composable
fun LocalRuntimeStatus.displayName(): String =
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

/** Progress of an update in flight, with the versions it moves between. */
@Composable
fun RuntimeUpdateProgressCard(status: LocalRuntimeStatus.Updating) {
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

/** What the last install, update, rollback or delete did, including the ones that failed. */
@Composable
fun RuntimeOperationResultCard(result: LocalRuntimeOperationResult) {
    val presentation =
        when (result) {
            is LocalRuntimeOperationResult.UpdateSkipped ->
                RuntimeOperationPresentation(
                    Icons.Default.CheckCircle,
                    stringResource(R.string.update_skipped_title),
                    stringResource(R.string.current_version_label, result.version),
                    false,
                )
            is LocalRuntimeOperationResult.Updated ->
                RuntimeOperationPresentation(
                    Icons.Default.CheckCircle,
                    stringResource(R.string.update_success_title),
                    stringResource(R.string.version_transition, result.fromVersion, result.toVersion),
                    false,
                )
            is LocalRuntimeOperationResult.AutomaticRollback ->
                RuntimeOperationPresentation(
                    Icons.Default.Warning,
                    stringResource(R.string.auto_rollback_title),
                    stringResource(R.string.auto_rollback_detail, result.failedVersion, result.restoredVersion, result.reason),
                    false,
                )
            is LocalRuntimeOperationResult.RolledBack ->
                RuntimeOperationPresentation(
                    Icons.Default.History,
                    stringResource(R.string.rollback_success_title),
                    stringResource(R.string.version_transition, result.fromVersion, result.toVersion),
                    false,
                )
            is LocalRuntimeOperationResult.RollbackFailedRestored ->
                RuntimeOperationPresentation(
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
                RuntimeOperationPresentation(
                    Icons.Default.Error,
                    stringResource(R.string.operation_failed_generic, result.operation),
                    result.message,
                    true,
                )
        }
    SectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(
                presentation.icon,
                contentDescription = stringResource(R.string.cd_operation_result),
                tint = if (presentation.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    presentation.title,
                    fontWeight = FontWeight.SemiBold,
                    color = if (presentation.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(presentation.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class RuntimeOperationPresentation(
    val icon: ImageVector,
    val title: String,
    val detail: String,
    val isError: Boolean,
)

fun formatRuntimeBytes(bytes: Long): String {
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
fun formatRuntimeDuration(milliseconds: Long): String {
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
