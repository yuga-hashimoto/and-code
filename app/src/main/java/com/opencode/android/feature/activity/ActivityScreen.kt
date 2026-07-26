package com.opencode.android.feature.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.core.api.OpenCodeSession
import com.opencode.android.runtime.PermissionResponse
import com.opencode.android.ui.components.SectionCard
import com.opencode.android.ui.components.StatusChip

@Composable
fun ActivityScreen(
    state: ActivityUiState,
    onRefresh: () -> Unit,
    onInspectSession: (OpenCodeSession) -> Unit,
    onOpenSession: (String, String) -> Unit,
    onPermission: (String, PermissionResponse, Boolean) -> Unit = { _, _, _ -> },
    onRenameSession: (String, String) -> Unit = { _, _ -> },
    onDeleteSession: (String) -> Unit = {},
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs =
        listOf(
            stringResource(R.string.tool_status_running),
            stringResource(R.string.tab_approvals),
            stringResource(R.string.nav_sessions),
            stringResource(R.string.tab_logs),
        )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.activity_screen_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(stringResource(R.string.activity_screen_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        }

        PrimaryTabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                )
            }
        }

        when (selectedTab) {
            0 -> RunningTab(state, onInspectSession, onOpenSession)
            1 -> ApprovalTab(state, onPermission)
            2 -> SessionsTab(state, onInspectSession, onOpenSession, onRenameSession, onDeleteSession)
            else -> LogsTab(state)
        }
    }
}

@Composable
private fun RunningTab(
    state: ActivityUiState,
    onInspectSession: (OpenCodeSession) -> Unit,
    onOpenSession: (String, String) -> Unit,
) {
    val activeSessions = state.sessions.filter { it.parentId == null && it.id in state.activeSessionIds }
    ActivityList(emptyText = stringResource(R.string.no_running_tasks), isEmpty = activeSessions.isEmpty()) {
        items(activeSessions, key = { it.id }) { session ->
            SectionCard(modifier = Modifier.clickable { onInspectSession(session) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = stringResource(R.string.cd_session),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(session.title.ifBlank { session.id }, fontWeight = FontWeight.SemiBold)
                        Text(
                            session.directory.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusChip(stringResource(R.string.tool_status_running), active = true)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onOpenSession(session.id, session.title) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.open_chat_button))
                }
            }
        }
    }
}

@Composable
private fun ApprovalTab(
    state: ActivityUiState,
    onPermission: (String, PermissionResponse, Boolean) -> Unit,
) {
    ActivityList(emptyText = stringResource(R.string.no_pending_approvals), isEmpty = state.permissions.isEmpty()) {
        items(state.permissions, key = { it.id }) { permission ->
            val busy = permission.id in state.permissionBusyIds
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = stringResource(R.string.cd_permission),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(permission.permission, fontWeight = FontWeight.SemiBold)
                        permission.patterns.forEach { pattern ->
                            Text(
                                pattern,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { onPermission(permission.id, PermissionResponse.REJECT, false) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.reject))
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { onPermission(permission.id, PermissionResponse.ONCE, false) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.allow_once))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onPermission(permission.id, PermissionResponse.ALWAYS, true) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.always_allow))
                }
            }
        }
    }
}

@Composable
private fun SessionsTab(
    state: ActivityUiState,
    onInspectSession: (OpenCodeSession) -> Unit,
    onOpenSession: (String, String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    var renaming by remember { mutableStateOf<OpenCodeSession?>(null) }
    var deleting by remember { mutableStateOf<OpenCodeSession?>(null) }
    var menuExpandedFor by remember { mutableStateOf<String?>(null) }

    val topLevelSessions = state.sessions.filter { it.parentId == null }

    ActivityList(emptyText = stringResource(R.string.no_sessions), isEmpty = topLevelSessions.isEmpty()) {
        items(topLevelSessions, key = { it.id }) { session ->
            SectionCard(modifier = Modifier.clickable { onInspectSession(session) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = stringResource(R.string.cd_session),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(session.title.ifBlank { session.slug ?: session.id }, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            session.directory.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpandedFor = session.id }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.session_options))
                        }
                        DropdownMenu(
                            expanded = menuExpandedFor == session.id,
                            onDismissRequest = { menuExpandedFor = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename_session)) },
                                onClick = {
                                    menuExpandedFor = null
                                    renaming = session
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_session)) },
                                onClick = {
                                    menuExpandedFor = null
                                    deleting = session
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onOpenSession(session.id, session.title) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.continue_chat_button))
                }
            }
        }
    }

    renaming?.let { session ->
        var title by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.rename_session_title)) },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.session_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameSession(session.id, title)
                        renaming = null
                    },
                    enabled = title.isNotBlank(),
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deleting?.let { session ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_session_title)) },
            text = { Text(stringResource(R.string.delete_session_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSession(session.id)
                        deleting = null
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun LogsTab(state: ActivityUiState) {
    ActivityList(emptyText = stringResource(R.string.no_event_logs), isEmpty = state.logs.isEmpty()) {
        items(state.logs, key = { "${it.timestamp}-${it.title}-${it.sessionId}" }) { log ->
            SectionCard {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = stringResource(R.string.cd_event_log),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(log.title, fontWeight = FontWeight.Medium)
                        log.detail?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        log.sessionId?.let {
                            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityList(
    emptyText: String,
    isEmpty: Boolean,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isEmpty) {
            item {
                SectionCard {
                    Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            content()
        }
        item {
            Spacer(Modifier.height(72.dp))
        }
    }
}
