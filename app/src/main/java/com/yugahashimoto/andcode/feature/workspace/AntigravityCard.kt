package com.yugahashimoto.andcode.feature.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.AntigravityAuthCoordinator
import com.yugahashimoto.andcode.runtime.local.AntigravityControllerState
import com.yugahashimoto.andcode.runtime.local.AntigravityInstallStatus
import com.yugahashimoto.andcode.runtime.local.AntigravityPermissionMode
import com.yugahashimoto.andcode.runtime.local.AntigravityUpdateResult

/**
 * Install, sign-in and permission controls for the official Antigravity agent.
 *
 * Shared between the setup wizard's sign-in step and the dedicated agent settings screen, the same
 * way [ClaudeCodeCard] is - one implementation, so the two never drift apart.
 */
@Composable
fun AntigravityCard(
    antigravity: AntigravityControllerState,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onSelectPermissionMode: (AntigravityPermissionMode) -> Unit,
    onSignIn: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancelSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onOpenUrl: (String) -> Unit,
    /** False in the setup guide, where installation is already driven by the guide's own step. */
    showInstallActions: Boolean = true,
    /** False on the settings screen, whose status card already states the installed version. */
    showVersion: Boolean = true,
) {
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val install = antigravity.install) {
            is AntigravityInstallStatus.Installing -> {
                if (install.step.isNotBlank()) Text(install.step, style = MaterialTheme.typography.bodySmall)
                if (install.progress != null) {
                    LinearProgressIndicator(progress = { install.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            is AntigravityInstallStatus.Failed -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = stringResource(R.string.cd_error),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(install.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (showInstallActions) {
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.claude_retry_install_button)) }
                }
            }
            is AntigravityInstallStatus.Ready ->
                InstalledSection(
                    antigravity,
                    onUpdate,
                    onSelectPermissionMode,
                    onSignIn,
                    onSubmitCode,
                    onCancelSignIn,
                    onSignOut,
                    onOpenUrl,
                    showInstallActions,
                    showVersion,
                )
            AntigravityInstallStatus.Idle ->
                if (antigravity.installed) {
                    InstalledSection(
                        antigravity,
                        onUpdate,
                        onSelectPermissionMode,
                        onSignIn,
                        onSubmitCode,
                        onCancelSignIn,
                        onSignOut,
                        onOpenUrl,
                        showInstallActions,
                        showVersion,
                    )
                } else if (showInstallActions) {
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.antigravity_install_button)) }
                }
        }
    }
}

@Composable
private fun InstalledSection(
    antigravity: AntigravityControllerState,
    onUpdate: () -> Unit,
    onSelectPermissionMode: (AntigravityPermissionMode) -> Unit,
    onSignIn: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancelSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onOpenUrl: (String) -> Unit,
    showInstallActions: Boolean,
    showVersion: Boolean,
) {
    if (showVersion) {
        antigravity.version?.takeIf(String::isNotBlank)?.let { version ->
            Text(
                text = stringResource(R.string.antigravity_installed_version, version),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
    when (val auth = antigravity.auth) {
        AntigravityAuthCoordinator.State.Idle -> {
            Text(stringResource(R.string.antigravity_status_signed_out), style = MaterialTheme.typography.bodySmall)
            SignInButton(onSignIn)
        }
        AntigravityAuthCoordinator.State.Starting -> {
            Text(stringResource(R.string.antigravity_auth_starting), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onCancelSignIn) { Text(stringResource(R.string.claude_auth_cancel)) }
        }
        is AntigravityAuthCoordinator.State.AwaitingBrowser -> BrowserStep(auth, onSubmitCode, onCancelSignIn, onOpenUrl)
        AntigravityAuthCoordinator.State.Verifying -> {
            Text(stringResource(R.string.antigravity_auth_verifying), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is AntigravityAuthCoordinator.State.Failed -> {
            Text(auth.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (auth.transcript.isNotBlank()) {
                Text(auth.transcript, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SignInButton(onSignIn)
        }
        is AntigravityAuthCoordinator.State.SignedIn -> {
            Text(stringResource(R.string.antigravity_signed_in), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.claude_sign_out_button)) }
        }
    }
    PermissionModePicker(selected = antigravity.permissionMode, onSelect = onSelectPermissionMode)
    if (showInstallActions) UpdateSection(antigravity, onUpdate)
}

/**
 * Offers the release this build of the app carries, and says what the last attempt did.
 *
 * Antigravity comes from a version pinned in the app rather than a package repository, so whether an
 * update exists is already known here — no check button, and no network needed to answer it.
 */
@Composable
private fun UpdateSection(
    antigravity: AntigravityControllerState,
    onUpdate: () -> Unit,
) {
    antigravity.lastUpdate?.let { result ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text =
                    when (result) {
                        is AntigravityUpdateResult.Updated ->
                            stringResource(R.string.agent_update_result_updated, result.fromVersion, result.version)
                        is AntigravityUpdateResult.AlreadyLatest ->
                            stringResource(R.string.agent_update_result_latest, result.version)
                    },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (antigravity.updateAvailable) {
        Text(
            text = stringResource(R.string.antigravity_update_available, antigravity.bundledVersion),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.antigravity_update_button))
        }
    }
}

/** Mirrors Claude Code's picker: agy's own `default` mode expects a live TUI, so it is not offered. */
@Composable
private fun PermissionModePicker(
    selected: AntigravityPermissionMode,
    onSelect: (AntigravityPermissionMode) -> Unit,
) {
    var pendingFullAccess by remember { mutableStateOf(false) }
    val requestSelect: (AntigravityPermissionMode) -> Unit = { mode ->
        if (mode == AntigravityPermissionMode.FULL_ACCESS && mode != selected) {
            pendingFullAccess = true
        } else {
            onSelect(mode)
        }
    }
    Text(stringResource(R.string.claude_permission_mode_label), style = MaterialTheme.typography.labelLarge)
    AntigravityPermissionMode.entries.forEach { mode ->
        Row(
            modifier = Modifier.fillMaxWidth().clickable { requestSelect(mode) },
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = mode == selected, onClick = { requestSelect(mode) })
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(mode.labelRes), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(mode.descriptionRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (pendingFullAccess) {
        RiskWarningDialog(
            titleRes = R.string.risk_warning_title,
            bodyRes = R.string.risk_warning_full_access_body,
            onConfirm = {
                pendingFullAccess = false
                onSelect(AntigravityPermissionMode.FULL_ACCESS)
            },
            onDismiss = { pendingFullAccess = false },
        )
    }
}

@Composable
private fun BrowserStep(
    auth: AntigravityAuthCoordinator.State.AwaitingBrowser,
    onSubmitCode: (String) -> Unit,
    onCancelSignIn: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    Text(stringResource(R.string.antigravity_auth_instructions), style = MaterialTheme.typography.bodySmall)
    Button(onClick = { onOpenUrl(auth.url) }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
        Spacer(Modifier.height(0.dp))
        Text(stringResource(R.string.claude_auth_open_browser))
    }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.claude_auth_code_hint)) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSubmitCode(code) }, enabled = code.isNotBlank(), modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.claude_auth_submit_code))
        }
        TextButton(onClick = onCancelSignIn) { Text(stringResource(R.string.claude_auth_cancel)) }
    }
}

@Composable
private fun SignInButton(onSignIn: () -> Unit) {
    var showExplainer by remember { mutableStateOf(false) }
    Button(onClick = { showExplainer = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.antigravity_sign_in_button))
    }
    if (showExplainer) {
        AgentAuthExplainerDialog(
            titleRes = R.string.antigravity_pre_auth_dialog_title,
            bodyRes = R.string.antigravity_pre_auth_dialog_body,
            onConfirm = {
                showExplainer = false
                onSignIn()
            },
            onDismiss = { showExplainer = false },
        )
    }
}
