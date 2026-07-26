package com.opencode.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.runtime.WorkspaceRef
import com.opencode.android.ui.components.SessionStatus
import com.opencode.android.ui.components.StatusDot
import com.opencode.android.ui.theme.OpenCodeAndroidTheme

data class DrawerRecentSession(
    val id: String,
    val title: String,
    val relativeTime: String,
    val directory: String? = null,
    val isActive: Boolean = false,
    val hasUnread: Boolean = false,
    val status: SessionStatus = SessionStatus.IDLE,
    val hasAttention: Boolean = false,
)

private fun DrawerRecentSession.projectKey(): String =
    directory?.trimEnd('/')?.takeIf { it.isNotBlank() && it != "/root" && it != "/workspace" }.orEmpty()

private fun DrawerRecentSession.projectLabel(defaultLabel: String): String =
    projectKey().substringAfterLast('/').takeIf { it.isNotBlank() } ?: defaultLabel

@Composable
fun AppDrawerContent(
    recentSessions: List<DrawerRecentSession>,
    workspaces: List<WorkspaceRef>,
    selectedWorkspacePath: String?,
    onNewChat: () -> Unit,
    onSelectProject: (WorkspaceRef) -> Unit,
    onOpenSession: (String, String) -> Unit,
    onNavigate: (String) -> Unit,
    onDeleteSession: (String) -> Unit = {},
    onArchiveSession: (String) -> Unit = {},
    onBatchDelete: (Set<String>) -> Unit = {},
    onBatchArchive: (Set<String>) -> Unit = {},
    sidebarGrouping: String = "project",
    onGroupingChange: (String) -> Unit = {},
    collapsedSections: Set<String> = emptySet(),
    onToggleSection: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedSessionIds by remember { mutableStateOf(setOf<String>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val selectionMode = selectedSessionIds.isNotEmpty()

    fun toggleSelection(id: String) {
        selectedSessionIds =
            if (id in selectedSessionIds) {
                selectedSessionIds - id
            } else {
                selectedSessionIds + id
            }
    }

    fun enterSelectionMode(id: String) {
        selectedSessionIds = setOf(id)
    }

    Surface(
        modifier =
            modifier
                .fillMaxHeight()
                .width(288.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 6.dp),
            ) {
                DrawerHeader()
                NewChatRow(onClick = onNewChat)

                DrawerSectionHeader(stringResource(R.string.drawer_projects_title))
                workspaces.forEach { workspace ->
                    DrawerProjectRow(
                        label = workspace.name,
                        path = workspace.path,
                        selected = workspace.path == selectedWorkspacePath,
                        onClick = { onSelectProject(workspace) },
                    )
                }
                DrawerAddProjectRow(onClick = { onNavigate("workspaces") })

                if (recentSessions.isNotEmpty()) {
                    val defaultLabel = stringResource(R.string.drawer_project_default)
                    DrawerSectionHeader(
                        text = stringResource(R.string.drawer_recent_chats),
                        collapsed = collapsedSections.contains("recent"),
                        onToggle = { onToggleSection("recent") },
                    )
                    AnimatedVisibility(visible = !collapsedSections.contains("recent")) {
                        Column {
                            if (selectionMode) {
                                SelectionActionBar(
                                    selectedCount = selectedSessionIds.size,
                                    onCancel = { selectedSessionIds = emptySet() },
                                    onArchive = {
                                        onBatchArchive(selectedSessionIds)
                                        selectedSessionIds = emptySet()
                                    },
                                    onDelete = { showDeleteConfirm = true },
                                )
                            }
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = sidebarGrouping == "project",
                                    onClick = { onGroupingChange("project") },
                                    label = { Text(stringResource(R.string.grouping_project)) },
                                )
                                FilterChip(
                                    selected = sidebarGrouping == "status",
                                    onClick = { onGroupingChange("status") },
                                    label = { Text(stringResource(R.string.grouping_status)) },
                                )
                            }
                            if (sidebarGrouping == "status") {
                                val statusGroups =
                                    listOf(
                                        stringResource(R.string.status_group_running) to setOf(SessionStatus.RUNNING),
                                        stringResource(R.string.status_group_waiting) to setOf(SessionStatus.WAITING),
                                        stringResource(
                                            R.string.status_group_done,
                                        ) to setOf(SessionStatus.COMPLETED_UNREAD, SessionStatus.IDLE),
                                        stringResource(R.string.status_group_error) to setOf(SessionStatus.ERROR),
                                    )
                                statusGroups.forEach { (label, statuses) ->
                                    val sessions = recentSessions.filter { it.status in statuses }
                                    if (sessions.isNotEmpty()) {
                                        val sectionKey = "status_$label"
                                        DrawerRecentProjectHeader(
                                            label = label,
                                            collapsed = collapsedSections.contains(sectionKey),
                                            onToggle = { onToggleSection(sectionKey) },
                                        )
                                        AnimatedVisibility(visible = !collapsedSections.contains(sectionKey)) {
                                            Column {
                                                sessions.forEach { session ->
                                                    DrawerChatRow(
                                                        title = session.title.ifBlank { session.id },
                                                        status = session.status,
                                                        hasAttention = session.hasAttention,
                                                        isActive = session.isActive,
                                                        hasUnread = session.hasUnread,
                                                        isSelected = session.id in selectedSessionIds,
                                                        selectionMode = selectionMode,
                                                        onClick = { onOpenSession(session.id, session.title) },
                                                        onLongClick = { enterSelectionMode(session.id) },
                                                        onToggleSelection = { toggleSelection(session.id) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                val grouped =
                                    recentSessions
                                        .groupBy { it.projectKey() }
                                        .toList()
                                        .sortedByDescending { (_, sessions) ->
                                            sessions.firstOrNull()?.let { recentSessions.indexOf(it) } ?: Int.MAX_VALUE
                                        }
                                grouped.forEach { (key, sessions) ->
                                    val sectionKey = "project_$key"
                                    val sorted = sessions.sortedByDescending { it.hasAttention }
                                    DrawerRecentProjectHeader(
                                        label = sessions.first().projectLabel(defaultLabel),
                                        collapsed = collapsedSections.contains(sectionKey),
                                        onToggle = { onToggleSection(sectionKey) },
                                    )
                                    AnimatedVisibility(visible = !collapsedSections.contains(sectionKey)) {
                                        Column {
                                            sorted.forEach { session ->
                                                DrawerChatRow(
                                                    title = session.title.ifBlank { session.id },
                                                    status = session.status,
                                                    hasAttention = session.hasAttention,
                                                    isActive = session.isActive,
                                                    hasUnread = session.hasUnread,
                                                    isSelected = session.id in selectedSessionIds,
                                                    selectionMode = selectionMode,
                                                    indented = true,
                                                    onClick = { onOpenSession(session.id, session.title) },
                                                    onLongClick = { enterSelectionMode(session.id) },
                                                    onToggleSelection = { toggleSelection(session.id) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f))
            DrawerDestinationRow(
                icon = Icons.Default.Settings,
                label = stringResource(R.string.nav_settings),
                onClick = { onNavigate("settings") },
            )
            Spacer(Modifier.padding(bottom = 3.dp))
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_session_title)) },
            text = {
                Text(
                    stringResource(R.string.drawer_delete_selected_body, selectedSessionIds.size),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onBatchDelete(selectedSessionIds)
                    selectedSessionIds = emptySet()
                    showDeleteConfirm = false
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.drawer_selected_count, selectedCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onArchive) {
            Icon(
                Icons.Default.Archive,
                contentDescription = stringResource(R.string.drawer_archive_session),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete_session),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun DrawerHeader() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Terminal,
            contentDescription = stringResource(R.string.cd_app_logo),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NewChatRow(onClick: () -> Unit) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 3.dp)
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = stringResource(R.string.cd_new_chat),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.new_chat),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun DrawerSectionHeader(
    text: String,
    collapsed: Boolean = false,
    onToggle: () -> Unit = {},
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 16.dp, top = 17.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
            contentDescription = stringResource(R.string.cd_expand_collapse),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DrawerProjectRow(
    label: String,
    path: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp)
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                Color.Transparent
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = stringResource(R.string.cd_folder),
                tint =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (path != null) {
                    Text(
                        text = path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerAddProjectRow(onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = stringResource(R.string.cd_add_project),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(R.string.drawer_add_project),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DrawerRecentProjectHeader(
    label: String,
    collapsed: Boolean = false,
    onToggle: () -> Unit = {},
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
            contentDescription = stringResource(R.string.cd_expand_collapse),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Icon(
            Icons.Default.Folder,
            contentDescription = stringResource(R.string.cd_folder),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerChatRow(
    title: String,
    status: SessionStatus = SessionStatus.IDLE,
    hasAttention: Boolean = false,
    isActive: Boolean = false,
    hasUnread: Boolean = false,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    indented: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onToggleSelection: () -> Unit = {},
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = if (selectionMode) onToggleSelection else onClick,
                    onLongClick = if (selectionMode) null else onLongClick,
                )
                .padding(
                    start = if (indented) 48.dp else 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        StatusDot(status = status)
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasAttention) {
            Box(
                modifier =
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
            )
        }
    }
}

@Composable
private fun DrawerDestinationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(
            icon,
            contentDescription = stringResource(R.string.cd_nav_item),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true)
@Composable
private fun AppDrawerContentPreview() {
    OpenCodeAndroidTheme {
        AppDrawerContent(
            recentSessions =
                listOf(
                    DrawerRecentSession("1", "認証バグの調査", "3時間前", "/workspace/opencode-android"),
                    DrawerRecentSession("2", "READMEの更新", "昨日", "/workspace/opencode-android"),
                    DrawerRecentSession("3", "テスト失敗を修正", "2日前", "/workspace/api-server"),
                    DrawerRecentSession("4", "APIレスポンスを整理", "4日前", "/workspace/api-server"),
                    DrawerRecentSession("5", "依存関係を更新", "1週間前", null),
                ),
            workspaces =
                listOf(
                    WorkspaceRef("/workspace/opencode-android", "opencode-android", "/workspace/opencode-android"),
                    WorkspaceRef("/workspace/api-server", "api-server", "/workspace/api-server"),
                ),
            selectedWorkspacePath = "/workspace/opencode-android",
            onNewChat = {},
            onSelectProject = {},
            onOpenSession = { _, _ -> },
            onNavigate = {},
        )
    }
}
