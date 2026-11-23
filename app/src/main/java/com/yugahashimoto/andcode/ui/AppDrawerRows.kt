package com.yugahashimoto.andcode.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import com.yugahashimoto.andcode.ui.components.SessionStatus
import com.yugahashimoto.andcode.ui.components.StatusDot

@Composable
internal fun DrawerHeader() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.andcode_logo),
            contentDescription = stringResource(R.string.cd_app_logo),
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
internal fun NewChatRow(onClick: () -> Unit) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 3.dp)
                .heightIn(min = 48.dp)
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
internal fun DrawerSectionHeader(
    text: String,
    collapsed: Boolean = false,
    onToggle: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 8.dp),
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
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

@Composable
internal fun DrawerAgentChips(
    agents: List<DrawerAgent>,
    selectedRuntimeId: String?,
    onSelectAgent: (DrawerAgent) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        agents.forEach { agent ->
            FilterChip(
                selected = agent.runtimeId == selectedRuntimeId,
                onClick = { onSelectAgent(agent) },
                label = {
                    Text(
                        text = stringResource(agent.agent.displayNameRes),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(agent.agent.iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                modifier = Modifier.minimumInteractiveComponentSize(),
            )
        }
    }
}

@Composable
internal fun DrawerProjectsSection(
    workspaces: List<WorkspaceRef>,
    selectedWorkspacePath: String?,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onSelectProject: (WorkspaceRef) -> Unit,
    onAddProject: () -> Unit,
) {
    val addProjectDescription = stringResource(R.string.cd_add_project)
    DrawerSectionHeader(
        text = stringResource(R.string.drawer_projects_title),
        collapsed = collapsed,
        onToggle = onToggle,
        trailing = {
            TextButton(
                onClick = onAddProject,
                modifier = Modifier.semantics { contentDescription = addProjectDescription },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.drawer_add_project),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
    if (workspaces.isNotEmpty()) {
        AnimatedVisibility(visible = !collapsed) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                workspaces.forEach { workspace ->
                    FilterChip(
                        selected = workspace.path == selectedWorkspacePath,
                        onClick = { onSelectProject(workspace) },
                        label = {
                            Text(
                                text = workspace.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun DrawerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    // The visible field stays compact while the clear button keeps a full 48dp touch target that
    // may extend slightly past the rounded surface.
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(start = 12.dp, end = if (query.isEmpty()) 12.dp else 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.drawer_search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle =
                            MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                    )
                }
            }
        }
        if (query.isNotEmpty()) {
            IconButton(
                onClick = { onQueryChange("") },
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.drawer_search_clear),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun SelectionActionBar(
    selectedCount: Int,
    allSelectedPinned: Boolean,
    onCancel: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
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
        IconButton(onClick = onTogglePin) {
            Icon(
                Icons.Default.PushPin,
                contentDescription =
                    stringResource(
                        if (allSelectedPinned) R.string.drawer_unpin_session else R.string.drawer_pin_session,
                    ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
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
internal fun DrawerRecentProjectHeader(
    label: String,
    collapsed: Boolean = false,
    onToggle: () -> Unit = {},
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 16.dp),
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

/** Row menu callbacks; the row itself owns the dropdown so it is anchored to that chat. */
internal data class DrawerChatMenuActions(
    val isPinned: Boolean,
    val onTogglePinned: () -> Unit,
    val onRename: () -> Unit,
    val onSelect: () -> Unit,
    val onArchive: () -> Unit,
    val onDelete: () -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DrawerChatRow(
    title: String,
    relativeTime: String,
    status: SessionStatus = SessionStatus.IDLE,
    hasAttention: Boolean = false,
    isActive: Boolean = false,
    hasUnread: Boolean = false,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    indented: Boolean = false,
    /** Null for a remote runtime; the list now spans every agent, so rows have to say which. */
    agent: LocalAgent? = null,
    menuActions: DrawerChatMenuActions? = null,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(selectionMode) {
        if (selectionMode) menuExpanded = false
    }
    val actions = menuActions

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .combinedClickable(
                        onClick = if (selectionMode) onToggleSelection else onClick,
                        onLongClick =
                            if (!selectionMode && actions != null) {
                                { menuExpanded = true }
                            } else {
                                null
                            },
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = relativeTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hasAttention) {
                Box(
                    modifier =
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                )
            }
            if (agent != null) {
                Icon(
                    painter = painterResource(agent.iconRes),
                    contentDescription = stringResource(agent.displayNameRes),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        if (actions != null) {
            DrawerChatRowMenu(
                expanded = menuExpanded,
                actions = actions,
                onDismiss = { menuExpanded = false },
            )
        }
    }
}

@Composable
private fun DrawerChatRowMenu(
    expanded: Boolean,
    actions: DrawerChatMenuActions,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DrawerChatMenuItem(
            icon = Icons.Default.PushPin,
            label =
                stringResource(
                    if (actions.isPinned) R.string.drawer_unpin_session else R.string.drawer_pin_session,
                ),
            onClick = {
                onDismiss()
                actions.onTogglePinned()
            },
        )
        DrawerChatMenuItem(
            icon = Icons.Default.Edit,
            label = stringResource(R.string.rename_session),
            onClick = {
                onDismiss()
                actions.onRename()
            },
        )
        DrawerChatMenuItem(
            icon = Icons.Default.Checklist,
            label = stringResource(R.string.select),
            onClick = {
                onDismiss()
                actions.onSelect()
            },
        )
        DrawerChatMenuItem(
            icon = Icons.Default.Archive,
            label = stringResource(R.string.drawer_archive_session),
            onClick = {
                onDismiss()
                actions.onArchive()
            },
        )
        DrawerChatMenuItem(
            icon = Icons.Default.Delete,
            label = stringResource(R.string.delete_session),
            destructive = true,
            onClick = {
                onDismiss()
                actions.onDelete()
            },
        )
    }
}

@Composable
private fun DrawerChatMenuItem(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(text = label, color = contentColor) },
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint =
                    if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

@Composable
internal fun DrawerChatRows(
    sessions: List<DrawerRecentSession>,
    selectedSessionIds: Set<String>,
    selectionMode: Boolean,
    indented: Boolean,
    menuActions: (DrawerRecentSession) -> DrawerChatMenuActions,
    onOpenSession: (DrawerRecentSession) -> Unit,
    onToggleSelection: (String) -> Unit,
) {
    Column {
        sessions.forEach { session ->
            key(session.id) {
                DrawerChatRow(
                    title = session.title.ifBlank { session.id },
                    relativeTime = session.relativeTime,
                    status = session.status,
                    hasAttention = session.hasAttention,
                    isActive = session.isActive,
                    hasUnread = session.hasUnread,
                    isSelected = session.id in selectedSessionIds,
                    selectionMode = selectionMode,
                    indented = indented,
                    agent = session.agent,
                    menuActions = menuActions(session),
                    onClick = { onOpenSession(session) },
                    onToggleSelection = { onToggleSelection(session.id) },
                )
            }
        }
    }
}

@Composable
internal fun DrawerDestinationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
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

@Composable
internal fun DeleteSelectionDialog(
    selectedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_session_title)) },
        text = { Text(stringResource(R.string.drawer_delete_selected_body, selectedCount)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
