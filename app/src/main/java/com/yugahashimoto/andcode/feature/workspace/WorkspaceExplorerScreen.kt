package com.yugahashimoto.andcode.feature.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.ui.components.FileTypeIcon
import com.yugahashimoto.andcode.ui.components.RetainedPanel
import com.yugahashimoto.andcode.ui.components.SectionCard
import com.yugahashimoto.andcode.ui.components.StatusChip

data class CommitInfo(
    val hash: String,
    val message: String,
    val author: String,
    val relativeTime: String,
)

enum class DiffViewMode { UNIFIED, SPLIT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceExplorerScreen(
    state: WorkspaceExplorerUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenNode: (OpenCodeFileNode) -> Unit,
    onNavigateUp: () -> Unit,
    onSearch: (String) -> Unit,
    onRefreshChanges: () -> Unit,
    branches: List<String> = emptyList(),
    onSwitchBranch: (String) -> Unit = {},
    onCreateBranch: (String) -> Unit = {},
    commits: List<CommitInfo> = emptyList(),
    prTitle: String? = null,
    prStatus: String? = null,
    prDescription: String? = null,
    tabManager: WorkspaceTabManager? = null,
    workspaceDeck: List<String> = emptyList(),
    onOpenTerminal: () -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs =
        listOf(
            stringResource(R.string.tab_files),
            stringResource(R.string.tab_search),
            stringResource(R.string.tab_changes),
            stringResource(R.string.tab_pr),
        )

    Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        if (workspaceDeck.size > 1) {
            WorkspaceDeckRow(workspaceDeck, state.workspace.name)
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.workspace.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    state.workspace.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onOpenTerminal) {
                Icon(Icons.Default.Terminal, contentDescription = stringResource(R.string.tab_type_terminal))
            }
            IconButton(
                onClick = if (selectedTab == 2) onRefreshChanges else onRefresh,
                enabled = !state.isLoadingFiles && !state.isLoadingChanges,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        }

        if (tabManager != null) {
            WorkspaceTabRow(tabManager)
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

        if (tabManager != null) {
            WorkspaceTabContent(tabManager = tabManager) {
                when (selectedTab) {
                    0 -> FilesTab(state, onOpenNode, onNavigateUp)
                    1 -> SearchTab(state, onSearch)
                    2 -> ChangesTab(state, branches, onSwitchBranch, onCreateBranch, commits)
                    else -> PrTab(prTitle, prStatus, prDescription)
                }
            }
        } else {
            when (selectedTab) {
                0 -> FilesTab(state, onOpenNode, onNavigateUp)
                1 -> SearchTab(state, onSearch)
                2 -> ChangesTab(state, branches, onSwitchBranch, onCreateBranch, commits)
                else -> PrTab(prTitle, prStatus, prDescription)
            }
        }
    }
}

@Composable
private fun WorkspaceDeckRow(
    workspaces: List<String>,
    activeName: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        workspaces.forEach { name ->
            FilterChip(
                selected = name == activeName,
                onClick = {},
                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceTabRow(tabManager: WorkspaceTabManager) {
    val workspaceTabs by tabManager.tabs.collectAsState()
    val selectedTabId by tabManager.selectedTabId.collectAsState()
    var tabDropdownId by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }

    if (workspaceTabs.isEmpty()) return

    val selectedIndex = workspaceTabs.indexOfFirst { it.id == selectedTabId }.coerceAtLeast(0)

    Box {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                modifier = Modifier.weight(1f),
                edgePadding = 8.dp,
            ) {
                workspaceTabs.forEach { tab ->
                    Tab(
                        selected = tab.id == selectedTabId,
                        onClick = { tabManager.selectTab(tab.id) },
                        text = { Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier =
                            Modifier.combinedClickable(
                                onClick = { tabManager.selectTab(tab.id) },
                                onLongClick = { tabDropdownId = tab.id },
                            ),
                    )
                }
            }
            Box {
                IconButton(onClick = { showAddMenu = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_tab))
                }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tab_type_agent)) },
                        onClick = {
                            tabManager.addTab(
                                WorkspaceTab(
                                    id = java.util.UUID.randomUUID().toString(),
                                    type = TabType.AGENT,
                                    title = "New",
                                ),
                            )
                            showAddMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tab_type_terminal)) },
                        onClick = {
                            tabManager.addTab(
                                WorkspaceTab(
                                    id = java.util.UUID.randomUUID().toString(),
                                    type = TabType.TERMINAL,
                                    title = "Terminal",
                                ),
                            )
                            showAddMenu = false
                        },
                    )
                }
            }
        }
        DropdownMenu(expanded = tabDropdownId != null, onDismissRequest = { tabDropdownId = null }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tab_close)) },
                onClick = {
                    tabDropdownId?.let { tabManager.removeTab(it) }
                    tabDropdownId = null
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tab_close_others)) },
                onClick = {
                    tabDropdownId?.let { tabManager.closeOthers(it) }
                    tabDropdownId = null
                },
            )
        }
    }
}

@Composable
private fun WorkspaceTabContent(
    tabManager: WorkspaceTabManager,
    content: @Composable () -> Unit,
) {
    val workspaceTabs by tabManager.tabs.collectAsState()
    val selectedTabId by tabManager.selectedTabId.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        workspaceTabs.forEach { tab ->
            RetainedPanel(visible = selectedTabId == tab.id) {
                when {
                    tab.type == TabType.TERMINAL -> TerminalTabPlaceholder()
                    selectedTabId == tab.id -> content()
                    else -> Text(stringResource(R.string.tab_content_placeholder), modifier = Modifier.padding(20.dp))
                }
            }
        }
    }
}

@Composable
private fun TerminalTabPlaceholder() {
    var input by remember { mutableStateOf("") }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E1E))
                .padding(12.dp),
    ) {
        Column {
            Text(
                "$ ",
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF6FCF97),
            )
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color.White,
                    ),
            )
        }
    }
}

@Composable
private fun FilesTab(
    state: WorkspaceExplorerUiState,
    onOpenNode: (OpenCodeFileNode) -> Unit,
    onNavigateUp: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = stringResource(R.string.cd_folder),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        state.currentPath,
                        modifier = Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onNavigateUp, enabled = state.currentPath != ".") {
                        Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.parent_folder))
                    }
                }
            }
        }

        if (state.isLoadingFiles) {
            item { LoadingCard(stringResource(R.string.loading_file_list)) }
        } else if (state.files.isEmpty()) {
            item {
                SectionCard {
                    Text(stringResource(R.string.folder_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(state.files, key = { it.absolute }) { node ->
                SectionCard(modifier = Modifier.clickable { onOpenNode(node) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FileTypeIcon(
                            fileName = node.name,
                            isDirectory = node.type == "directory",
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(node.name, fontWeight = FontWeight.Medium)
                            Text(
                                node.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (node.ignored) StatusChip(stringResource(R.string.ignored_label), active = false)
                    }
                }
            }
        }
        state.error?.let { item { ErrorCard(it) } }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun SearchTab(
    state: WorkspaceExplorerUiState,
    onSearch: (String) -> Unit,
) {
    var query by rememberSaveable(state.workspace.id) { mutableStateOf(state.searchQuery) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.search_files_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search)) },
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onSearch(query) },
                enabled = query.isNotBlank() && !state.isSearching,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.tab_search))
            }
        }

        if (state.isSearching) {
            item { LoadingCard(stringResource(R.string.searching)) }
        }

        if (!state.isSearching && state.searchQuery.isNotBlank() &&
            state.textMatches.isEmpty() && state.fileMatches.isEmpty()
        ) {
            item {
                SectionCard {
                    Text(stringResource(R.string.no_search_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (state.fileMatches.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.section_title_file_names), state.fileMatches.size) }
            items(state.fileMatches, key = { "file-$it" }) { path ->
                SectionCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = stringResource(R.string.cd_file),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Text(path, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (state.textMatches.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.section_title_content), state.textMatches.size) }
            items(
                state.textMatches,
                key = { "${it.path.text}:${it.lineNumber}:${it.absoluteOffset}" },
            ) { match ->
                SectionCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.Code,
                            contentDescription = stringResource(R.string.cd_code_match),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${match.path.text}:${match.lineNumber}",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            SelectionContainer {
                                Text(
                                    match.lines.text.trimEnd(),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        state.error?.let { item { ErrorCard(it) } }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangesTab(
    state: WorkspaceExplorerUiState,
    branches: List<String>,
    onSwitchBranch: (String) -> Unit,
    onCreateBranch: (String) -> Unit,
    commits: List<CommitInfo>,
) {
    var showBranchSheet by remember { mutableStateOf(false) }
    var diffViewMode by remember { mutableStateOf(DiffViewMode.UNIFIED) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Source,
                        contentDescription = stringResource(R.string.cd_git),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.git_label), fontWeight = FontWeight.SemiBold)
                        Text(
                            state.vcsInfo?.branch?.let { stringResource(R.string.branch_label, it) }
                                ?: stringResource(R.string.no_git_info),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable { showBranchSheet = true },
                        )
                    }
                    Text(stringResource(R.string.item_count, state.changes.size))
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = diffViewMode == DiffViewMode.UNIFIED,
                    onClick = { diffViewMode = DiffViewMode.UNIFIED },
                    label = { Text(stringResource(R.string.unified_view)) },
                )
                FilterChip(
                    selected = diffViewMode == DiffViewMode.SPLIT,
                    onClick = { diffViewMode = DiffViewMode.SPLIT },
                    label = { Text(stringResource(R.string.split_view)) },
                )
            }
        }

        if (state.isLoadingChanges) {
            item { LoadingCard(stringResource(R.string.loading_changes)) }
        } else if (state.changes.isEmpty()) {
            item {
                SectionCard {
                    Text(stringResource(R.string.no_uncommitted_changes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(state.changes, key = { it.displayPath }) { change ->
                val detailed = state.diff.firstOrNull { it.displayPath == change.displayPath } ?: change
                ChangeCard(detailed, diffViewMode)
            }
        }

        if (commits.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.commits_title), commits.size) }
            items(commits, key = { it.hash }) { commit ->
                CommitCard(commit)
            }
        }

        state.error?.let { item { ErrorCard(it) } }
        item { Spacer(Modifier.height(72.dp)) }
    }

    if (showBranchSheet) {
        BranchSwitcherSheet(
            currentBranch = state.vcsInfo?.branch.orEmpty(),
            branches = branches,
            onSwitchBranch = { branch ->
                onSwitchBranch(branch)
                showBranchSheet = false
            },
            onCreateBranch = { name ->
                onCreateBranch(name)
                showBranchSheet = false
            },
            onDismiss = { showBranchSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchSwitcherSheet(
    currentBranch: String,
    branches: List<String>,
    onSwitchBranch: (String) -> Unit,
    onCreateBranch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var newBranchName by remember { mutableStateOf("") }
    var showNewBranchField by remember { mutableStateOf(false) }

    val filteredBranches =
        if (searchQuery.isBlank()) {
            branches
        } else {
            branches.filter { it.contains(searchQuery, ignoreCase = true) }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
        ) {
            Text(
                stringResource(R.string.switch_branch),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.search_branches)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search)) },
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(300.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filteredBranches, key = { it }) { branch ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSwitchBranch(branch) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (branch == currentBranch) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.cd_current_branch),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Spacer(Modifier.height(24.dp))
                        }
                        Text(
                            branch,
                            modifier = Modifier.weight(1f),
                            fontWeight = if (branch == currentBranch) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (showNewBranchField) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newBranchName,
                        onValueChange = { newBranchName = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.branch_name_hint)) },
                        singleLine = true,
                    )
                    Button(
                        onClick = { if (newBranchName.isNotBlank()) onCreateBranch(newBranchName) },
                        enabled = newBranchName.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.create_branch))
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { showNewBranchField = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_new_branch))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.new_branch))
                }
            }
        }
    }
}

@Composable
private fun ChangeCard(
    change: OpenCodeFileChange,
    diffViewMode: DiffViewMode,
) {
    var expanded by remember(change.displayPath, change.patch) { mutableStateOf(false) }
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FileTypeIcon(fileName = change.displayPath.substringAfterLast('/'))
            Column(modifier = Modifier.weight(1f)) {
                Text(change.displayPath, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.diff_stat, change.additions.toInt(), change.deletions.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        change.status.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!change.patch.isNullOrBlank()) {
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) stringResource(R.string.collapse) else stringResource(R.string.diff_label))
                }
            }
        }
        if (expanded && !change.patch.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            when (diffViewMode) {
                DiffViewMode.UNIFIED -> UnifiedDiffView(change.patch)
                DiffViewMode.SPLIT -> SplitDiffView(change.patch)
            }
        }
    }
}

@Composable
private fun UnifiedDiffView(patch: String) {
    SelectionContainer {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
        ) {
            patch.lines().forEach { line ->
                val background =
                    when {
                        line.startsWith("+") && !line.startsWith("+++") -> Color(0x2A6FCF97)
                        line.startsWith("-") && !line.startsWith("---") -> Color(0x2AF07178)
                        else -> Color.Transparent
                    }
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(background)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun SplitDiffView(patch: String) {
    val oldLines = mutableListOf<String>()
    val newLines = mutableListOf<String>()
    patch.lines().forEach { line ->
        when {
            line.startsWith("+") && !line.startsWith("+++") -> {
                newLines.add(line.removePrefix("+"))
                oldLines.add("")
            }
            line.startsWith("-") && !line.startsWith("---") -> {
                oldLines.add(line.removePrefix("-"))
                newLines.add("")
            }
            !line.startsWith("@@") && !line.startsWith("+++") && !line.startsWith("---") -> {
                oldLines.add(line.removePrefix(" "))
                newLines.add(line.removePrefix(" "))
            }
        }
    }

    SelectionContainer {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                oldLines.forEach { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(if (line.isNotEmpty()) Color(0x2AF07178) else Color.Transparent)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                newLines.forEach { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(if (line.isNotEmpty()) Color(0x2A6FCF97) else Color.Transparent)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommitCard(commit: CommitInfo) {
    SectionCard(modifier = Modifier.clickable { }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(commit.message, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${commit.author} · ${commit.relativeTime}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                commit.hash.take(7),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PrTab(
    prTitle: String?,
    prStatus: String?,
    prDescription: String?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (prTitle == null) {
            item {
                SectionCard {
                    Text(
                        stringResource(R.string.no_pr_found),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                prTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (prStatus != null) {
                                StatusChip(prStatus, active = prStatus.equals("open", ignoreCase = true))
                            }
                        }
                        if (!prDescription.isNullOrBlank()) {
                            Text(
                                prDescription,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    count: Int,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.item_count, count), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingCard(text: String) {
    SectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator()
            Text(text)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    SectionCard {
        Text(stringResource(R.string.fetch_failed), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
