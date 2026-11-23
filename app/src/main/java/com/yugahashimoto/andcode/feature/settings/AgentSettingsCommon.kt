package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yugahashimoto.andcode.R

/**
 * The status text every agent settings card shows.
 *
 * Codex, Claude Code and Antigravity each carried their own `statusLabel()` extension with the same
 * five cases in the same order; this is the one place they are decided, so a new install state only
 * has to be described once.
 */
@Composable
internal fun agentStatusLabel(
    installing: Boolean,
    failed: Boolean,
    installed: Boolean,
    ready: Boolean,
): String =
    when {
        installing -> stringResource(R.string.runtime_status_setting_up)
        failed -> stringResource(R.string.agent_status_install_failed)
        !installed -> stringResource(R.string.runtime_status_not_installed)
        ready -> stringResource(R.string.agent_status_ready)
        else -> stringResource(R.string.agent_status_sign_in_required)
    }

/** The installed-version metric, omitted entirely while the version is unknown. */
@Composable
internal fun agentVersionMetrics(version: String?): List<AgentMetric> =
    version
        ?.takeIf(String::isNotBlank)
        ?.let { listOf(AgentMetric(stringResource(R.string.agent_version_label), it)) }
        .orEmpty()

/** The status card every agent settings screen opens with, with its version metric filled in. */
@Composable
internal fun AgentSettingsStatusCard(
    status: String,
    active: Boolean,
    version: String?,
    extraMetrics: List<AgentMetric> = emptyList(),
    content: @Composable ColumnScope.() -> Unit,
) {
    AgentStatusCard(
        status = status,
        active = active,
        metrics = agentVersionMetrics(version) + extraMetrics,
        content = content,
    )
}

/** The MCP row every agent screen carries; it is always the same destination and wording. */
@Composable
internal fun AgentMcpSettingsRow(onOpenMcp: () -> Unit) {
    SettingsRow(
        icon = Icons.Default.Extension,
        title = stringResource(R.string.mcp_settings_row),
        onClick = onOpenMcp,
    )
}
