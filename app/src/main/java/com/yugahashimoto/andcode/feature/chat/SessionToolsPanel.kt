package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.browser.PreviewTarget

/** State shown in the chat screen's right-side Tools panel. */
data class SessionToolsUiState(
    val runningPreviewTargets: List<PreviewTarget.Url> = emptyList(),
    val workspaceLabel: String? = null,
    val isLocalRuntime: Boolean = true,
)

/** Callbacks wired from the chat screen's right-side Tools panel. */
data class SessionToolsActions(
    val onOpenPreview: (PreviewTarget.Url) -> Unit = {},
    val onOpenFiles: () -> Unit = {},
    val onOpenBrowser: () -> Unit = {},
    val onOpenTerminal: () -> Unit = {},
    val onRefreshPreviewAvailability: () -> Unit = {},
)

/**
 * Right-side swipe panel for session tools: live dev-server preview, file explorer, Guest Browser,
 * and terminal. Preview is always pinned at the top and is enabled only while a dev server for the
 * current session is actually listening.
 */
@Composable
fun SessionToolsPanel(
    state: SessionToolsUiState,
    actions: SessionToolsActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .width(280.dp)
                .fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .systemBarsPadding(),
        ) {
            SessionToolsHeader(onClose = onClose)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            SessionToolsList(
                state = state,
                actions = actions,
                onClose = onClose,
                modifier = Modifier.weight(1f),
            )
            state.workspaceLabel?.takeIf { it.isNotBlank() }?.let { workspace ->
                SessionToolsFooter(workspace = workspace)
            }
        }
    }
}

@Composable
private fun SessionToolsHeader(onClose: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.side_panel_tools_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.side_panel_close),
            )
        }
    }
}

@Composable
private fun SessionToolsList(
    state: SessionToolsUiState,
    actions: SessionToolsActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryPreview = state.runningPreviewTargets.firstOrNull()
    val extraPreviews = state.runningPreviewTargets.drop(1)
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SessionToolRow(
            icon = Icons.Default.Language,
            title = stringResource(R.string.chat_preview_link),
            subtitle = primaryPreview?.label ?: stringResource(R.string.side_panel_preview_not_running),
            enabled = primaryPreview != null,
            highlighted = primaryPreview != null,
            onClick = {
                if (primaryPreview != null) {
                    onClose()
                    actions.onOpenPreview(primaryPreview)
                }
            },
        )
        extraPreviews.forEach { target ->
            SessionToolRow(
                icon = Icons.Default.Language,
                title = target.label,
                subtitle = null,
                enabled = true,
                highlighted = false,
                onClick = {
                    onClose()
                    actions.onOpenPreview(target)
                },
            )
        }
        SessionToolRow(
            icon = Icons.Default.Folder,
            title = stringResource(R.string.side_panel_files_title),
            subtitle = stringResource(R.string.side_panel_files_desc),
            enabled = true,
            highlighted = false,
            onClick = {
                onClose()
                actions.onOpenFiles()
            },
        )
        SessionToolRow(
            icon = Icons.Default.OpenInBrowser,
            title = stringResource(R.string.guest_browser_row),
            subtitle = null,
            enabled = true,
            highlighted = false,
            onClick = {
                onClose()
                actions.onOpenBrowser()
            },
        )
        if (state.isLocalRuntime) {
            SessionToolRow(
                icon = Icons.Default.Terminal,
                title = stringResource(R.string.side_panel_terminal_title),
                subtitle = null,
                enabled = true,
                highlighted = false,
                onClick = {
                    onClose()
                    actions.onOpenTerminal()
                },
            )
        }
    }
}

@Composable
private fun SessionToolsFooter(workspace: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = workspace,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SessionToolRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    enabled: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val contentAlpha = if (enabled) 1f else 0.48f
    val iconTint =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
            highlighted -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val containerColor =
        if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.surface
        }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp)
                .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (highlighted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
