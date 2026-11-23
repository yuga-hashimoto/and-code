package com.yugahashimoto.andcode.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.WorkspaceRef

// The drawer's compact pickers: agent switcher, project header and project chips.

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
                .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        agents.forEach { agent ->
            val selected = agent.runtimeId == selectedRuntimeId
            val name = stringResource(agent.agent.displayNameRes)
            Surface(
                onClick = { onSelectAgent(agent) },
                // The chosen agent spells out its name; the others stay icons, so four agents fit
                // a phone-width drawer without truncating any of them.
                modifier =
                    Modifier
                        .height(38.dp)
                        .then(if (selected) Modifier.weight(1f) else Modifier.width(44.dp))
                        .semantics { this.selected = selected },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)) else null,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    Icon(
                        painter = painterResource(agent.agent.iconRes),
                        contentDescription = if (selected) null else name,
                        modifier = Modifier.size(18.dp),
                    )
                    if (selected) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
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
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    projectQuery: String,
    onProjectQueryChange: (String) -> Unit,
) {
    val addProjectDescription = stringResource(R.string.cd_add_project)
    DrawerSectionHeader(
        text = stringResource(R.string.drawer_projects_title),
        collapsed = collapsed,
        onToggle = onToggle,
        trailing = {
            // Capped, so a long translation at a large font size cannot push the search icon out.
            TextButton(
                onClick = onAddProject,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier =
                    Modifier
                        .height(32.dp)
                        .widthIn(max = 150.dp)
                        .semantics { contentDescription = addProjectDescription },
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.drawer_add_project),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (workspaces.isNotEmpty()) {
                IconButton(
                    onClick = onToggleSearch,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(R.string.cd_search_projects),
                        tint =
                            if (searchOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    )
    if (workspaces.isNotEmpty()) {
        AnimatedVisibility(visible = !collapsed) {
            Column(modifier = Modifier.padding(bottom = 4.dp)) {
                if (searchOpen) {
                    DrawerSearchField(
                        query = projectQuery,
                        onQueryChange = onProjectQueryChange,
                        hint = stringResource(R.string.drawer_project_search_hint),
                    )
                }
                DrawerProjectChips(
                    workspaces = filterDrawerProjects(workspaces, projectQuery),
                    selectedWorkspacePath = selectedWorkspacePath,
                    onSelectProject = onSelectProject,
                )
            }
        }
    }
}

@Composable
internal fun DrawerProjectChips(
    workspaces: List<WorkspaceRef>,
    selectedWorkspacePath: String?,
    onSelectProject: (WorkspaceRef) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        workspaces.forEach { workspace ->
            val selected = workspace.path == selectedWorkspacePath
            Surface(
                onClick = { onSelectProject(workspace) },
                modifier = Modifier.semantics { this.selected = selected },
                shape = RoundedCornerShape(100.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                border =
                    BorderStroke(
                        1.dp,
                        if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        if (selected) Icons.Default.FolderOpen else Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(workspace.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}
