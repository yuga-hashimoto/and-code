package com.yugahashimoto.andcode.feature.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.CodexInstallStatus
import com.yugahashimoto.andcode.runtime.local.CodexUiState
import com.yugahashimoto.andcode.runtime.local.CodexUpdateResult

/**
 * Install and sign-in controls for the Android-local Codex agent.
 *
 * Every stage says something concrete - installing, why an install failed, whether an account is
 * signed in - so a failed download never looks like a button that did nothing.
 */
@Composable
fun CodexCard(
    codex: CodexUiState,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    /** False in the setup guide, where installation and updates are driven by the guide's own step. */
    showUpdateAction: Boolean = true,
) {
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val install = codex.install) {
            is CodexInstallStatus.Installing -> {
                Text(install.step ?: stringResource(R.string.codex_installing), style = MaterialTheme.typography.bodySmall)
                val progress = install.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            is CodexInstallStatus.Failed -> {
                SelectionContainer {
                    Text(
                        install.message ?: stringResource(R.string.codex_error_install_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                InstallButton(R.string.codex_retry_install_button, onInstall)
            }
            CodexInstallStatus.Idle ->
                if (codex.installed) {
                    InstalledSection(codex, onUpdate, onSignIn, onSignOut, showUpdateAction)
                } else {
                    Text(
                        text = stringResource(R.string.codex_needs_runtime_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    InstallButton(R.string.codex_install_button, onInstall)
                }
        }
    }
}

@Composable
private fun InstallButton(
    labelRes: Int,
    onInstall: () -> Unit,
) {
    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Build, contentDescription = null)
        Spacer(Modifier.padding(horizontal = 4.dp))
        Text(stringResource(labelRes))
    }
}

@Composable
private fun InstalledSection(
    codex: CodexUiState,
    onUpdate: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    showUpdateAction: Boolean,
) {
    if (codex.signedIn) {
        Text(
            text = stringResource(R.string.codex_status_signed_in),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.codex_sign_out_button))
        }
    } else {
        Text(
            text = stringResource(R.string.codex_status_signed_out),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.codex_sign_in_button))
        }
    }
    if (showUpdateAction) {
        codex.lastUpdate?.let { UpdateResultRow(it) }
        OutlinedButton(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.codex_update_button))
        }
    }
}

/**
 * Says whether the last update moved the version or found nothing newer.
 *
 * The release is resolved at press time rather than from a pinned build, so the same button can
 * legitimately do nothing; without this the only feedback was the progress bar disappearing.
 */
@Composable
private fun UpdateResultRow(result: CodexUpdateResult) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text =
                when (result) {
                    is CodexUpdateResult.Updated ->
                        stringResource(R.string.agent_update_result_updated, result.fromVersion, result.version)
                    is CodexUpdateResult.AlreadyLatest ->
                        stringResource(R.string.agent_update_result_latest, result.version)
                },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
