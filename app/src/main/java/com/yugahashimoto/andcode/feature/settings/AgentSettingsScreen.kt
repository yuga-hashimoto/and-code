package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.workspace.ClaudeCodeCard
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.local.ClaudeCodeUiState
import com.yugahashimoto.andcode.runtime.local.ClaudePermissionMode
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
    antigravity: com.yugahashimoto.andcode.runtime.local.AntigravityControllerState,
    onInstall: () -> Unit,
    onSelectPermissionMode: (com.yugahashimoto.andcode.runtime.local.AntigravityPermissionMode) -> Unit,
    onSignIn: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancelSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenLocalRuntime: () -> Unit,
    onBack: () -> Unit,
) {
    AgentSettingsScaffold(title = stringResource(LocalAgent.ANTIGRAVITY.displayNameRes), onBack = onBack) {
        SettingsSection(title = stringResource(R.string.settings_agents_section)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                com.yugahashimoto.andcode.feature.workspace.AntigravityCard(
                    antigravity = antigravity,
                    onInstall = onInstall,
                    onSelectPermissionMode = onSelectPermissionMode,
                    onSignIn = onSignIn,
                    onSubmitCode = onSubmitCode,
                    onCancelSignIn = onCancelSignIn,
                    onSignOut = onSignOut,
                    onOpenUrl = onOpenUrl,
                )
            }
            SettingsDivider()
            SettingsRow(
                icon = Icons.Default.Extension,
                title = stringResource(R.string.mcp_settings_row),
                onClick = onOpenMcp,
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Default.Terminal,
                title = stringResource(R.string.settings_local_runtime_row),
                onClick = onOpenLocalRuntime,
            )
        }
    }
}

/** OpenCode's own settings, gathered from what used to be the top-level system list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeAgentSettingsScreen(
    onOpenProviderSettings: () -> Unit,
    onOpenModelVisibility: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenLocalRuntime: () -> Unit,
    onBack: () -> Unit,
) {
    AgentSettingsScaffold(title = stringResource(LocalAgent.OPEN_CODE.displayNameRes), onBack = onBack) {
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
            SettingsDivider()
            SettingsRow(
                icon = Icons.Default.Terminal,
                title = stringResource(R.string.settings_local_runtime_row),
                onClick = onOpenLocalRuntime,
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
        SettingsSection(title = stringResource(R.string.settings_agents_section)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
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
                )
            }
            SettingsDivider()
            SettingsRow(
                icon = Icons.Default.Extension,
                title = stringResource(R.string.mcp_settings_row),
                onClick = onOpenMcp,
            )
        }
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
                    .verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}
