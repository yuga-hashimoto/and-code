package com.yugahashimoto.andcode.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import com.yugahashimoto.andcode.ui.theme.AndCodeTheme

@Composable
fun AppDrawerContent(
    recentSessions: List<DrawerRecentSession>,
    workspaces: List<WorkspaceRef>,
    selectedWorkspacePath: String?,
    onNewChat: () -> Unit,
    onSelectProject: (WorkspaceRef) -> Unit,
    /** Session id, title, and the runtime that owns it. */
    onOpenSession: (String, String, String?) -> Unit,
    onNavigate: (String) -> Unit,
    /** Installed agents, offered as a switcher only when there is more than one. */
    agents: List<DrawerAgent> = emptyList(),
    selectedRuntimeId: String? = null,
    onSelectAgent: (DrawerAgent) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onArchiveSession: (String) -> Unit = {},
    onRenameSession: (sessionId: String, runtimeId: String?, title: String) -> Unit =
        { _, _, _ -> },
    onBatchDelete: (Set<String>) -> Unit = {},
    onBatchArchive: (Set<String>) -> Unit = {},
    onSetPinned: (Set<String>, Boolean) -> Unit = { _, _ -> },
    collapsedSections: Set<String> = emptySet(),
    onToggleSection: (String) -> Unit = {},
    drawerOpen: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var selectedSessionIds by remember { mutableStateOf(setOf<String>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DrawerRecentSession?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var projectSearchOpen by rememberSaveable { mutableStateOf(false) }
    var projectQuery by rememberSaveable { mutableStateOf("") }
    val selectionMode = selectedSessionIds.isNotEmpty()
    val selectionAllPinned = allSelectedPinned(selectedSessionIds, recentSessions)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // A stale search or selection surviving a close is a trap: the next open looks empty or
    // unexpectedly armed for multi-select.
    LaunchedEffect(drawerOpen) {
        if (!drawerOpen) {
            searchQuery = ""
            projectSearchOpen = false
            projectQuery = ""
            selectedSessionIds = emptySet()
            showDeleteConfirm = false
            renameTarget = null
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    fun menuActionsFor(session: DrawerRecentSession): DrawerChatMenuActions =
        DrawerChatMenuActions(
            isPinned = session.isPinned,
            onTogglePinned = { onSetPinned(setOf(session.id), !session.isPinned) },
            onRename = { renameTarget = session },
            onSelect = { selectedSessionIds = setOf(session.id) },
            onArchive = { onArchiveSession(session.id) },
            onDelete = { onDeleteSession(session.id) },
        )

    fun toggleSelection(id: String) {
        selectedSessionIds =
            if (id in selectedSessionIds) {
                selectedSessionIds - id
            } else {
                selectedSessionIds + id
            }
    }

    fun setPinnedState() {
        onSetPinned(selectedSessionIds, !selectionAllPinned)
        selectedSessionIds = emptySet()
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

                DrawerProjectsSection(
                    workspaces = workspaces,
                    selectedWorkspacePath = selectedWorkspacePath,
                    collapsed = collapsedSections.contains("projects"),
                    onToggle = { onToggleSection("projects") },
                    onSelectProject = onSelectProject,
                    onAddProject = { onNavigate("workspaces") },
                    searchOpen = projectSearchOpen,
                    onToggleSearch = {
                        projectSearchOpen = !projectSearchOpen
                        if (!projectSearchOpen) projectQuery = ""
                    },
                    projectQuery = projectQuery,
                    onProjectQueryChange = { projectQuery = it },
                )

                // Only worth a switcher when there is something to switch between.
                if (agents.size > 1) {
                    DrawerAgentChips(
                        agents = agents,
                        selectedRuntimeId = selectedRuntimeId,
                        onSelectAgent = onSelectAgent,
                    )
                }

                NewChatRow(onClick = onNewChat)

                if (recentSessions.isNotEmpty()) {
                    DrawerSearchField(
                        query = searchQuery,
                        onQueryChange = { newQuery ->
                            searchQuery = newQuery
                            if (selectionMode) {
                                selectedSessionIds =
                                    retainVisibleSelection(selectedSessionIds, recentSessions, newQuery)
                            }
                        },
                    )
                    if (selectionMode) {
                        val singleSelected = selectedSessionIds.singleOrNull()?.let { id -> recentSessions.firstOrNull { it.id == id } }
                        SelectionActionBar(
                            selectedCount = selectedSessionIds.size,
                            allSelectedPinned = selectionAllPinned,
                            onCancel = { selectedSessionIds = emptySet() },
                            onArchive = {
                                // One chat archives straight away with Undo; a batch keeps the list action.
                                val singleId = selectedSessionIds.singleOrNull()
                                if (singleId != null) {
                                    onArchiveSession(singleId)
                                } else {
                                    onBatchArchive(selectedSessionIds)
                                }
                                selectedSessionIds = emptySet()
                            },
                            onDelete = {
                                // One chat goes straight away, with Undo on the snackbar; a batch is
                                // worth a question first.
                                val singleId = selectedSessionIds.singleOrNull()
                                if (singleId != null) {
                                    onDeleteSession(singleId)
                                    selectedSessionIds = emptySet()
                                } else {
                                    showDeleteConfirm = true
                                }
                            },
                            onTogglePin = { setPinnedState() },
                            onRename =
                                singleSelected?.let { session ->
                                    {
                                        renameTarget = session
                                        selectedSessionIds = emptySet()
                                    }
                                },
                        )
                    }
                    DrawerChatList(
                        recentSessions = recentSessions,
                        query = searchQuery,
                        selectedSessionIds = selectedSessionIds,
                        selectionMode = selectionMode,
                        collapsedSections = collapsedSections,
                        onToggleSection = onToggleSection,
                        menuActions = { session -> menuActionsFor(session) },
                        onOpenSession = { session -> onOpenSession(session.id, session.title, session.runtimeId) },
                        onToggleSelection = { id -> toggleSelection(id) },
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f))
            // The places you go less often share one row, so the chat list keeps the height.
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                // An equal share each, so a long label in one language cannot squeeze out the other.
                val item = Modifier.weight(1f)
                DrawerDestinationRow(
                    icon = Icons.Default.Schedule,
                    label = stringResource(R.string.nav_schedules),
                    modifier = item,
                    onClick = { onNavigate("schedules") },
                )
                DrawerDestinationRow(
                    icon = Icons.Default.Settings,
                    label = stringResource(R.string.nav_settings),
                    modifier = item,
                    onClick = { onNavigate("settings") },
                )
            }
        }
    }

    if (showDeleteConfirm) {
        DeleteSelectionDialog(
            selectedCount = selectedSessionIds.size,
            onConfirm = {
                onBatchDelete(selectedSessionIds)
                selectedSessionIds = emptySet()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    renameTarget?.let { session ->
        RenameSessionDialog(
            currentTitle = session.title,
            onDismiss = { renameTarget = null },
            onSave = { title ->
                onRenameSession(session.id, session.runtimeId, title)
                renameTarget = null
            },
        )
    }
}

private fun allSelectedPinned(
    selectedSessionIds: Set<String>,
    recentSessions: List<DrawerRecentSession>,
): Boolean =
    selectedSessionIds.isNotEmpty() &&
        selectedSessionIds.all { id -> recentSessions.firstOrNull { it.id == id }?.isPinned == true }

@Composable
private fun DrawerChatList(
    recentSessions: List<DrawerRecentSession>,
    query: String,
    selectedSessionIds: Set<String>,
    selectionMode: Boolean,
    collapsedSections: Set<String>,
    onToggleSection: (String) -> Unit,
    menuActions: (DrawerRecentSession) -> DrawerChatMenuActions,
    onOpenSession: (DrawerRecentSession) -> Unit,
    onToggleSelection: (String) -> Unit,
) {
    if (query.isNotBlank()) {
        val results = searchResults(recentSessions, query)
        if (results.totalCount == 0) {
            Text(
                text = stringResource(R.string.drawer_search_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Column {
                DrawerChatRows(
                    sessions = results.shown,
                    selectedSessionIds = selectedSessionIds,
                    selectionMode = selectionMode,
                    indented = false,
                    onOpenSession = onOpenSession,
                    menuActions = menuActions,
                    onToggleSelection = onToggleSelection,
                )
                if (results.totalCount > results.shown.size) {
                    Text(
                        text = stringResource(R.string.drawer_search_more, results.shown.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        return
    }

    val browsable = browsableSessions(recentSessions)
    val pinned = browsable.filter { it.isPinned }
    if (pinned.isNotEmpty()) {
        DrawerSectionHeader(
            text = stringResource(R.string.drawer_pinned),
            collapsed = collapsedSections.contains("pinned"),
            onToggle = { onToggleSection("pinned") },
        )
        AnimatedVisibility(visible = !collapsedSections.contains("pinned")) {
            DrawerChatRows(
                sessions = pinned,
                selectedSessionIds = selectedSessionIds,
                selectionMode = selectionMode,
                indented = false,
                onOpenSession = onOpenSession,
                menuActions = menuActions,
                onToggleSelection = onToggleSelection,
            )
        }
    }

    val unpinned = browsable.filterNot { it.isPinned }
    // All chats pinned: the pinned section above already lists everything, so no empty "Recent".
    if (unpinned.isEmpty()) return

    val defaultLabel = stringResource(R.string.drawer_project_default)
    Column {
        // Grouped by project, always. The project/status switch that used to sit here made
        // the drawer ask a question before it answered one, and the status a chat is in
        // already shows on its own row. There is no "Recent chats" heading above the list:
        // it is the drawer's main content, so the heading only cost height.
        groupedByProject(unpinned).forEach { (key, sessions) ->
            val sectionKey = "project_$key"
            DrawerRecentProjectHeader(
                label = sessions.first().projectLabel(defaultLabel),
                collapsed = collapsedSections.contains(sectionKey),
                onToggle = { onToggleSection(sectionKey) },
            )
            AnimatedVisibility(visible = !collapsedSections.contains(sectionKey)) {
                DrawerChatRows(
                    sessions = sessions,
                    selectedSessionIds = selectedSessionIds,
                    selectionMode = selectionMode,
                    indented = true,
                    onOpenSession = onOpenSession,
                    menuActions = menuActions,
                    onToggleSelection = onToggleSelection,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AppDrawerContentPreview() {
    AndCodeTheme {
        AppDrawerContent(
            recentSessions =
                listOf(
                    DrawerRecentSession(
                        "1",
                        "Investigate auth bug",
                        "3 hours ago",
                        "/workspace/android-code",
                        isPinned = true,
                    ),
                    DrawerRecentSession("2", "Update README", "Yesterday", "/workspace/android-code"),
                    DrawerRecentSession("3", "Fix failing tests", "2 days ago", "/workspace/api-server"),
                    DrawerRecentSession("4", "Clean up API responses", "4 days ago", "/workspace/api-server"),
                    DrawerRecentSession("5", "Update dependencies", "1 week ago", null),
                ),
            workspaces =
                listOf(
                    WorkspaceRef("/workspace/android-code", "android-code", "/workspace/android-code"),
                    WorkspaceRef("/workspace/api-server", "api-server", "/workspace/api-server"),
                ),
            selectedWorkspacePath = "/workspace/android-code",
            onNewChat = {},
            onSelectProject = {},
            onOpenSession = { _, _, _ -> },
            onNavigate = {},
        )
    }
}
