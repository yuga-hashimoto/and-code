package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.workspace.ClaudeCodeCard
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.local.AntigravityAuthCoordinator
import com.yugahashimoto.andcode.runtime.local.AntigravityControllerState
import com.yugahashimoto.andcode.runtime.local.AntigravityInstallStatus
import com.yugahashimoto.andcode.runtime.local.ClaudeAuthCoordinator
import com.yugahashimoto.andcode.runtime.local.ClaudeCodeUiState
import com.yugahashimoto.andcode.runtime.local.ClaudeInstallStatus
import com.yugahashimoto.andcode.runtime.local.ClaudePermissionMode
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeUpdateCheck
import com.yugahashimoto.andcode.ui.components.RuntimeOperationResultCard
import com.yugahashimoto.andcode.ui.components.RuntimeUpdateProgressCard
import com.yugahashimoto.andcode.ui.components.SectionCard
import com.yugahashimoto.andcode.ui.components.displayName
import com.yugahashimoto.andcode.ui.components.formatRuntimeBytes
import com.yugahashimoto.andcode.ui.runtimeAgentIcon

/**
 * Lists the agents so their settings sit under the agent they belong to.
 *
 * Provider credentials, the model catalogue and MCP servers are all OpenCode's, and having them at
 * the top level implied they applied to every agent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsScreen(
    onOpenOpenCode: () -> Unit,
    onOpenClaudeCode: () -> Unit,
    onOpenAntigravity: () -> Unit,
    onBack: () -> Unit,
) {
    AgentSettingsScaffold(title = stringResource(R.string.settings_agents_row), onBack = onBack) {
        SettingsSection(title = stringResource(R.string.settings_agents_section)) {
            AgentRow(LocalAgent.OPEN_CODE, onOpenOpenCode)
            SettingsDivider()
            AgentRow(LocalAgent.CLAUDE_CODE, onOpenClaudeCode)
            SettingsDivider()
            AgentRow(LocalAgent.ANTIGRAVITY, onOpenAntigravity)
        }
    }
}

/** Antigravity's own settings: the same install, sign-in and permission controls as the setup guide. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AntigravityAgentSettingsScreen(
    antigravity: AntigravityControllerState,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onSelectPermissionMode: (com.yugahashimoto.andcode.runtime.local.AntigravityPermissionMode) -> Unit,
    onSignIn: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancelSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
) {
    AgentSettingsScaffold(title = stringResource(LocalAgent.ANTIGRAVITY.displayNameRes), onBack = onBack) {
        AgentCardSection {
            AgentStatusCard(
                status = antigravity.statusLabel(),
                active = antigravity.isReady(),
                metrics =
                    antigravity.version?.takeIf(String::isNotBlank)?.let { version ->
                        listOf(AgentMetric(stringResource(R.string.agent_version_label), version))
                    }.orEmpty(),
            ) {
                com.yugahashimoto.andcode.feature.workspace.AntigravityCard(
                    antigravity = antigravity,
                    onInstall = onInstall,
                    onUpdate = onUpdate,
                    onSelectPermissionMode = onSelectPermissionMode,
                    onSignIn = onSignIn,
                    onSubmitCode = onSubmitCode,
                    onCancelSignIn = onCancelSignIn,
                    onSignOut = onSignOut,
                    onOpenUrl = onOpenUrl,
                    showVersion = false,
                )
            }
        }
        SettingsSection(title = stringResource(R.string.settings_agents_section)) {
            SettingsRow(
                icon = Icons.Default.Extension,
                title = stringResource(R.string.mcp_settings_row),
                onClick = onOpenMcp,
            )
        }
    }
}

/** Claude Code's own settings: the same install, sign-in and permission controls as Workspaces. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudeCodeAgentSettingsScreen(
    claude: ClaudeCodeUiState,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onSelectPermissionMode: (ClaudePermissionMode) -> Unit,
    onSignIn: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancelSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
) {
    AgentSettingsScaffold(title = stringResource(LocalAgent.CLAUDE_CODE.displayNameRes), onBack = onBack) {
        AgentCardSection {
            AgentStatusCard(
                status = claude.statusLabel(),
                active = claude.isReady(),
                metrics =
                    claude.version?.takeIf(String::isNotBlank)?.let { version ->
                        listOf(AgentMetric(stringResource(R.string.agent_version_label), version))
                    }.orEmpty(),
            ) {
                ClaudeCodeCard(
                    claude = claude,
                    onInstall = onInstall,
                    onUpdate = onUpdate,
                    onSelectPermissionMode = onSelectPermissionMode,
                    onSignIn = onSignIn,
                    onSubmitCode = onSubmitCode,
                    onCancelSignIn = onCancelSignIn,
                    onSignOut = onSignOut,
                    onOpenUrl = onOpenUrl,
                    showVersion = false,
                )
            }
        }
        SettingsSection(title = stringResource(R.string.settings_agents_section)) {
            SettingsRow(
                icon = Icons.Default.Extension,
                title = stringResource(R.string.mcp_settings_row),
                onClick = onOpenMcp,
            )
        }
    }
}

/**
 * OpenCode's own settings.
 *
 * Unlike Claude Code and Antigravity, OpenCode runs as a server, so the state that matters is
 * whether that server is up — and until now nothing in the app could start it again once it was
 * not. The start, stop and restart controls, the installed version and the update and rollback
 * that move it live here rather than on the shared local runtime screen, which reports the Linux
 * environment all three agents share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeAgentSettingsScreen(
    state: OpenCodeAgentUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onRequestUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onConfirmUpdate: () -> Unit,
    onRequestRollback: () -> Unit,
    onDismissRollback: () -> Unit,
    onConfirmRollback: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenProviderSettings: () -> Unit,
    onOpenModelVisibility: () -> Unit,
    onOpenMcp: () -> Unit,
    onBack: () -> Unit,
) {
    AgentSettingsScaffold(title = stringResource(LocalAgent.OPEN_CODE.displayNameRes), onBack = onBack) {
        AgentCardSection {
            (state.status as? LocalRuntimeStatus.Updating)?.let { RuntimeUpdateProgressCard(it) }
            state.lastOperation?.let { RuntimeOperationResultCard(it) }

            AgentStatusCard(
                status = state.status.displayName(),
                active = state.status is LocalRuntimeStatus.Ready,
                metrics = openCodeMetrics(state),
            ) {
                if (state.installed) {
                    OpenCodeProcessControls(state, onStart, onStop, onRestart)
                } else {
                    Text(
                        stringResource(R.string.opencode_not_installed_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Build, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(stringResource(R.string.opencode_open_setup_button))
                    }
                }
            }

            if (state.installed) {
                OpenCodeUpdateCard(state, onCheckForUpdate, onRequestUpdate, onRequestRollback)
            }

            state.error?.let { error ->
                SectionCard {
                    Text(
                        stringResource(R.string.operation_failed_title),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SettingsSection(title = stringResource(R.string.settings_agents_section)) {
            SettingsRow(
                icon = Icons.Default.Key,
                title = stringResource(R.string.provider_settings_row),
                onClick = onOpenProviderSettings,
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Default.Visibility,
                title = stringResource(R.string.model_visibility_row),
                onClick = onOpenModelVisibility,
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Default.Extension,
                title = stringResource(R.string.mcp_settings_row),
                onClick = onOpenMcp,
            )
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
                        stringResource(R.string.required_free_space, formatRuntimeBytes(available.release.asset.requiredFreeBytes)),
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
            confirmButton = { TextButton(onClick = onConfirmUpdate) { Text(stringResource(R.string.update_confirm_button)) } },
            dismissButton = { TextButton(onClick = onDismissUpdate) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (state.showRollbackConfirmation && !state.rollbackVersion.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = onDismissRollback,
            title = { Text(stringResource(R.string.rollback_confirm_title, state.rollbackVersion)) },
            text = { Text(stringResource(R.string.rollback_confirm_body)) },
            confirmButton = { TextButton(onClick = onConfirmRollback) { Text(stringResource(R.string.rollback_button)) } },
            dismissButton = { TextButton(onClick = onDismissRollback) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Start, stop and restart for the OpenCode server.
 *
 * Stop stays enabled while the server is coming up: a start that hangs is exactly when the user
 * needs a way out of it.
 */
@Composable
private fun OpenCodeProcessControls(
    state: OpenCodeAgentUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    val running = state.status is LocalRuntimeStatus.Ready
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running) {
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(stringResource(R.string.runtime_stop_button))
            }
            OutlinedButton(onClick = onRestart, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(stringResource(R.string.runtime_restart_button))
            }
        } else {
            Button(onClick = onStart, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                }
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(stringResource(R.string.runtime_start_button))
            }
            if (state.status is LocalRuntimeStatus.Starting) {
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.runtime_stop_button))
                }
            }
        }
    }
}

/** OpenCode's release controls: what is installed, what is available and what it can go back to. */
@Composable
private fun OpenCodeUpdateCard(
    state: OpenCodeAgentUiState,
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
        Spacer(Modifier.padding(vertical = 4.dp))

        when (val check = state.updateCheck) {
            null -> {
                Text(stringResource(R.string.check_update_source_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(vertical = 5.dp))
                OutlinedButton(
                    onClick = onCheckForUpdate,
                    enabled = !state.isCheckingUpdate && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_check_update))
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.check_for_update_button))
                }
            }
            is LocalRuntimeUpdateCheck.UpToDate -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.cd_up_to_date),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(R.string.up_to_date_label, check.currentVersion), fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.padding(vertical = 4.dp))
                OutlinedButton(
                    onClick = onCheckForUpdate,
                    enabled = !state.isCheckingUpdate && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.recheck_button))
                }
            }
            is LocalRuntimeUpdateCheck.Available -> {
                val requiredBytes = check.release.asset.requiredFreeBytes
                val enoughSpace = state.freeBytes >= requiredBytes
                Text(
                    stringResource(R.string.update_available_label, check.release.version),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.padding(vertical = 3.dp))
                AgentMetricRow(stringResource(R.string.current_version_metric), check.currentVersion)
                AgentMetricRow(stringResource(R.string.download_size_label), formatRuntimeBytes(check.release.asset.sizeBytes))
                AgentMetricRow(stringResource(R.string.required_free_space_metric), formatRuntimeBytes(requiredBytes))
                if (!enoughSpace) {
                    Text(
                        stringResource(R.string.insufficient_space, formatRuntimeBytes(state.freeBytes)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (check.release.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.padding(vertical = 5.dp))
                    Text(stringResource(R.string.release_notes_label), fontWeight = FontWeight.Medium)
                    Text(
                        check.release.releaseNotes,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.padding(vertical = 6.dp))
                Button(
                    onClick = onRequestUpdate,
                    enabled = !state.busy && !state.isCheckingUpdate && enoughSpace,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = stringResource(R.string.cd_update))
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.update_to_button, check.release.version))
                }
            }
        }

        state.updateError?.let { error ->
            Spacer(Modifier.padding(vertical = 4.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        state.rollbackVersion?.let { rollbackVersion ->
            Spacer(Modifier.padding(vertical = 7.dp))
            HorizontalDivider()
            Spacer(Modifier.padding(vertical = 7.dp))
            Text(stringResource(R.string.previous_version_label), fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.revert_available_note, rollbackVersion),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(vertical = 4.dp))
            OutlinedButton(
                onClick = onRequestRollback,
                enabled = !state.busy,
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
private fun openCodeMetrics(state: OpenCodeAgentUiState): List<AgentMetric> =
    buildList {
        state.version?.takeIf(String::isNotBlank)?.let {
            add(AgentMetric(stringResource(R.string.agent_version_label), it))
        }
        state.port?.let { add(AgentMetric(stringResource(R.string.port_label), it.toString())) }
    }

@Composable
private fun ClaudeCodeUiState.statusLabel(): String =
    when {
        install is ClaudeInstallStatus.Installing -> stringResource(R.string.runtime_status_setting_up)
        install is ClaudeInstallStatus.Failed -> stringResource(R.string.agent_status_install_failed)
        !installed && install !is ClaudeInstallStatus.Ready -> stringResource(R.string.runtime_status_not_installed)
        isReady() -> stringResource(R.string.agent_status_ready)
        else -> stringResource(R.string.agent_status_sign_in_required)
    }

@Composable
private fun ClaudeCodeUiState.isReady(): Boolean =
    (installed || install is ClaudeInstallStatus.Ready) &&
        (auth is ClaudeAuthCoordinator.State.SignedIn || !signedInAccount.isNullOrBlank())

@Composable
private fun AntigravityControllerState.statusLabel(): String =
    when {
        install is AntigravityInstallStatus.Installing -> stringResource(R.string.runtime_status_setting_up)
        install is AntigravityInstallStatus.Failed -> stringResource(R.string.agent_status_install_failed)
        !installed && install !is AntigravityInstallStatus.Ready -> stringResource(R.string.runtime_status_not_installed)
        isReady() -> stringResource(R.string.agent_status_ready)
        else -> stringResource(R.string.agent_status_sign_in_required)
    }

@Composable
private fun AntigravityControllerState.isReady(): Boolean =
    (installed || install is AntigravityInstallStatus.Ready) &&
        auth is AntigravityAuthCoordinator.State.SignedIn

/** Wraps the status cards so every agent screen indents them the same way as its rows. */
@Composable
private fun AgentCardSection(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        content()
    }
}

@Composable
private fun AgentRow(
    agent: LocalAgent,
    onClick: () -> Unit,
) {
    SettingsRow(
        painter = painterResource(runtimeAgentIcon(agent)),
        title = stringResource(agent.displayNameRes),
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
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
        ) {
            content()
        }
    }
}
