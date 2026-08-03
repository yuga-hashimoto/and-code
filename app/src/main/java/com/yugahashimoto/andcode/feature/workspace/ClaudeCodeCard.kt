package com.yugahashimoto.andcode.feature.workspace

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.OpenInNew
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
import com.yugahashimoto.andcode.runtime.local.ClaudeAuthCoordinator
import com.yugahashimoto.andcode.runtime.local.ClaudeCodeUiState
import com.yugahashimoto.andcode.runtime.local.ClaudeInstallStatus
import com.yugahashimoto.andcode.runtime.local.ClaudePermissionMode
import com.yugahashimoto.andcode.runtime.local.ClaudeUpdateResult

/**
 * Install and sign-in controls for the Android-local Claude Code agent.
 *
 * Every stage reports something concrete — which install step is running, which account is signed
 * in, why an attempt failed — because the failure mode this replaces was a button that appeared to
 * do nothing at all.
 */
@Composable
fun ClaudeCodeCard(
    claude: ClaudeCodeUiState,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onSelectPermissionMode: (ClaudePermissionMode) -> Unit,
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
        when (val install = claude.install) {
            is ClaudeInstallStatus.Installing -> {
                Text(stringResource(install.step), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is ClaudeInstallStatus.Failed -> {
                SelectionContainer {
                    Text(
                        text = install.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (showInstallActions) {
                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Build, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(stringResource(R.string.claude_retry_install_button))
                    }
                }
            }
            is ClaudeInstallStatus.Ready ->
                InstalledSection(
                    claude,
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
            ClaudeInstallStatus.Idle ->
                if (claude.installed) {
                    InstalledSection(
                        claude,
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
                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Build, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(stringResource(R.string.claude_install_button))
                    }
                }
        }
    }
}

@Composable
private fun InstalledSection(
    claude: ClaudeCodeUiState,
    onUpdate: () -> Unit,
    onSelectPermissionMode: (ClaudePermissionMode) -> Unit,
    onSignIn: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancelSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onOpenUrl: (String) -> Unit,
    showInstallActions: Boolean,
    showVersion: Boolean,
) {
    if (showVersion) claude.version?.takeIf(String::isNotBlank)?.let { InstalledVersionRow(it) }
    when (val auth = claude.auth) {
        ClaudeAuthCoordinator.State.Starting -> {
            Text(stringResource(R.string.claude_auth_starting), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onCancelSignIn) { Text(stringResource(R.string.claude_auth_cancel)) }
        }
        is ClaudeAuthCoordinator.State.AwaitingBrowser -> BrowserStep(auth, onSubmitCode, onCancelSignIn, onOpenUrl)
        ClaudeAuthCoordinator.State.Verifying -> {
            Text(stringResource(R.string.claude_auth_verifying), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is ClaudeAuthCoordinator.State.Failed -> {
            Text(auth.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (auth.transcript.isNotBlank()) {
                Text(
                    text = auth.transcript,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SignInButton(onSignIn)
        }
        is ClaudeAuthCoordinator.State.SignedIn -> SignedInRow(auth.account, onSignOut)
        ClaudeAuthCoordinator.State.Idle ->
            claude.signedInAccount?.let { account -> SignedInRow(account, onSignOut) } ?: run {
                Text(
                    text = stringResource(R.string.claude_status_signed_out),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SignInButton(onSignIn)
            }
    }
    PermissionModePicker(selected = claude.permissionMode, onSelect = onSelectPermissionMode)
    if (showInstallActions) {
        claude.lastUpdate?.let { UpdateResultRow(it) }
        OutlinedButton(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.claude_update_button))
        }
    }
}

/**
 * States which version is installed.
 *
 * The update button lives on this card, so without the version here there was no way to tell what
 * an update started from or arrived at.
 */
@Composable
private fun InstalledVersionRow(version: String) {
    Text(
        text = stringResource(R.string.claude_installed_version, version),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
}

/** Says whether the last update moved the version or found nothing newer. */
@Composable
private fun UpdateResultRow(result: ClaudeUpdateResult) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text =
                when (result) {
                    is ClaudeUpdateResult.Updated ->
                        stringResource(R.string.agent_update_result_updated, result.fromVersion, result.version)
                    is ClaudeUpdateResult.AlreadyLatest ->
                        stringResource(R.string.agent_update_result_latest, result.version)
                },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Picks the permission mode applied to new Claude Code sessions.
 *
 * Existing conversations keep the mode they started with: widening permissions mid-session would
 * retroactively change what the user already agreed to.
 */
@Composable
private fun PermissionModePicker(
    selected: ClaudePermissionMode,
    onSelect: (ClaudePermissionMode) -> Unit,
) {
    var pendingFullAccess by remember { mutableStateOf(false) }
    val requestSelect: (ClaudePermissionMode) -> Unit = { mode ->
        if (mode == ClaudePermissionMode.FULL_ACCESS && mode != selected) {
            pendingFullAccess = true
        } else {
            onSelect(mode)
        }
    }
    Text(
        text = stringResource(R.string.claude_permission_mode_label),
        style = MaterialTheme.typography.labelLarge,
    )
    ClaudePermissionMode.entries.forEach { mode ->
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
                onSelect(ClaudePermissionMode.FULL_ACCESS)
            },
            onDismiss = { pendingFullAccess = false },
        )
    }
}

@Composable
private fun BrowserStep(
    auth: ClaudeAuthCoordinator.State.AwaitingBrowser,
    onSubmitCode: (String) -> Unit,
    onCancelSignIn: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    Text(stringResource(R.string.claude_auth_instructions), style = MaterialTheme.typography.bodySmall)
    Button(onClick = { onOpenUrl(auth.url) }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.OpenInNew, contentDescription = null)
        Spacer(Modifier.padding(horizontal = 4.dp))
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
        Button(
            onClick = { onSubmitCode(code) },
            enabled = code.isNotBlank(),
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.claude_auth_submit_code))
        }
        TextButton(onClick = onCancelSignIn) { Text(stringResource(R.string.claude_auth_cancel)) }
    }
}

@Composable
private fun SignInButton(onSignIn: () -> Unit) {
    var showExplainer by remember { mutableStateOf(false) }
    Button(onClick = { showExplainer = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.claude_sign_in_button))
    }
    if (showExplainer) {
        AgentAuthExplainerDialog(
            titleRes = R.string.claude_pre_auth_dialog_title,
            bodyRes = R.string.claude_pre_auth_dialog_body,
            onConfirm = {
                showExplainer = false
                onSignIn()
            },
            onDismiss = { showExplainer = false },
        )
    }
}

@Composable
private fun SignedInRow(
    account: String,
    onSignOut: () -> Unit,
) {
    Text(
        text = stringResource(R.string.claude_signed_in_as, account),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
    )
    OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.claude_sign_out_button))
    }
}
