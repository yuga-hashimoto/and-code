package com.yugahashimoto.andcode.feature.settings

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.BuildConfig
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.diagnostics.CrashLog
import com.yugahashimoto.andcode.ui.components.LabelValueRow
import com.yugahashimoto.andcode.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(
    onDismiss: () -> Unit,
    appVersion: String,
    connectionStatus: String,
    runtimeStatus: String,
) {
    val clipboard = LocalClipboardManager.current
    val buildType = BuildConfig.BUILD_TYPE

    val runtime = Runtime.getRuntime()
    val totalMemoryMb = runtime.totalMemory() / (1024L * 1024L)
    val freeMemoryMb = runtime.freeMemory() / (1024L * 1024L)
    val maxMemoryMb = runtime.maxMemory() / (1024L * 1024L)

    val statFs = remember { StatFs(Environment.getDataDirectory().path) }
    val availableBytes = statFs.availableBytes
    val totalBytes = statFs.totalBytes

    val context = LocalContext.current
    // The dialog shown at launch is easy to close by accident, so the record stays reachable here
    // until it has been copied.
    val lastCrash = remember { CrashLog.read(context) }

    val markdown =
        buildString {
            appendLine("# AndCode Diagnostics")
            appendLine()
            appendLine("## App")
            appendLine("- Version: $appVersion")
            appendLine("- Build type: $buildType")
            appendLine()
            appendLine("## Connection")
            appendLine("- Status: $connectionStatus")
            appendLine("- Runtime: $runtimeStatus")
            appendLine()
            appendLine("## Memory")
            appendLine("- Total: ${totalMemoryMb}MB")
            appendLine("- Free: ${freeMemoryMb}MB")
            appendLine("- Max: ${maxMemoryMb}MB")
            appendLine()
            appendLine("## Storage")
            appendLine("- Available: ${formatBytes(availableBytes)}")
            appendLine("- Total: ${formatBytes(totalBytes)}")
            if (lastCrash != null) {
                appendLine()
                appendLine("## Last crash")
                appendLine("```")
                appendLine(lastCrash)
                appendLine("```")
            }
        }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.diagnostics_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagnosticsSectionHeader(stringResource(R.string.diagnostics_section_app))
                    LabelValueRow(label = stringResource(R.string.diagnostics_version), value = appVersion)
                    LabelValueRow(label = stringResource(R.string.diagnostics_build_type), value = buildType)
                }
            }

            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagnosticsSectionHeader(stringResource(R.string.diagnostics_section_connection))
                    LabelValueRow(label = stringResource(R.string.diagnostics_status), value = connectionStatus)
                    LabelValueRow(label = stringResource(R.string.diagnostics_runtime), value = runtimeStatus)
                }
            }

            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagnosticsSectionHeader(stringResource(R.string.diagnostics_section_memory))
                    LabelValueRow(label = stringResource(R.string.diagnostics_total), value = "${totalMemoryMb}MB")
                    LabelValueRow(label = stringResource(R.string.diagnostics_free), value = "${freeMemoryMb}MB")
                    LabelValueRow(label = stringResource(R.string.diagnostics_max), value = "${maxMemoryMb}MB")
                }
            }

            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagnosticsSectionHeader(stringResource(R.string.diagnostics_section_storage))
                    LabelValueRow(label = stringResource(R.string.diagnostics_available), value = formatBytes(availableBytes))
                    LabelValueRow(label = stringResource(R.string.diagnostics_total), value = formatBytes(totalBytes))
                }
            }

            if (lastCrash != null) {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiagnosticsSectionHeader(stringResource(R.string.diagnostics_section_last_crash))
                        Text(
                            text = lastCrash.lineSequence().take(CRASH_PREVIEW_LINES).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(markdown))
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.diagnostics_copy_button))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DiagnosticsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val CRASH_PREVIEW_LINES = 12

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        "%.1f GB".format(gb)
    } else {
        "%.0f MB".format(bytes / (1024.0 * 1024.0))
    }
}
