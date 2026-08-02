package com.yugahashimoto.andcode.feature.workspace

import android.content.Context
import android.net.nsd.NsdManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.security.ConnectionQrPayload
import com.yugahashimoto.andcode.core.storage.DeviceStorageAccess
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeType
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import com.yugahashimoto.andcode.ui.components.SectionCard
import com.yugahashimoto.andcode.ui.components.StatusChip
import com.yugahashimoto.andcode.ui.runtimeAgentIcon
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val DISCOVERY_TIMEOUT_MILLIS = 10_000L

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun WorkspacesScreen(
    state: WorkspaceUiState,
    onSelectRuntime: (String) -> Unit,
    onSaveConnection: (ConnectionFormState) -> Unit,
    onDeleteConnection: (String) -> Unit,
    onTestConnection: suspend (ConnectionFormState) -> Result<OpenCodeHealth>,
    onRefresh: () -> Unit,
    onOpenWorkspace: (WorkspaceRef) -> Unit,
    onImportFolder: () -> Unit = {},
    onCloneGithub: () -> Unit = {},
    onChooseFolder: () -> Unit = {},
    onBrowseFolderInto: (String) -> Unit = {},
    onBrowseFolderUp: () -> Unit = {},
    onConfirmFolder: () -> Unit = {},
    onDismissFolderPicker: () -> Unit = {},
    onDeviceStorageAccessChanged: () -> Unit = {},
    onRemoveProject: (String) -> Unit = {},
    onDeleteProjectFiles: (String) -> Unit = {},
    onDismissDeleteFailure: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val localRuntimeActive =
        state.targets
            .firstOrNull { it.selected }
            ?.let { it.type == RuntimeType.LOCAL } ?: true

    var editing by remember { mutableStateOf<ConnectionFormState?>(null) }
    var discoveryDialogOpen by remember { mutableStateOf(false) }
    var isDiscovering by remember { mutableStateOf(false) }
    var discoveredServers by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val qrScanLauncher =
        rememberLauncherForActivityResult(ScanContract()) { result ->
            val text = result.contents ?: return@rememberLauncherForActivityResult
            ConnectionQrPayload.parse(text)?.let { payload ->
                editing =
                    ConnectionFormState(
                        name = payload.name.orEmpty(),
                        baseUrl = payload.url.orEmpty(),
                        username = payload.username?.takeIf { it.isNotBlank() } ?: "opencode",
                        password = payload.password.orEmpty(),
                        allowInsecureLan = payload.insecure,
                    )
            }
        }

    fun startLanDiscovery() {
        discoveryDialogOpen = true
        discoveredServers = emptyList()
        // Best-effort: see RemoteConnectionScreen. A browse failure shows an empty result instead of
        // crashing, leaving manual entry available.
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            isDiscovering = false
            return
        }
        isDiscovering = true
        coroutineScope.launch {
            runCatching {
                withTimeoutOrNull(DISCOVERY_TIMEOUT_MILLIS) {
                    LanDiscovery(nsdManager).discover().collect { server ->
                        discoveredServers =
                            (discoveredServers + server)
                                .distinctBy { it.host to it.port }
                    }
                }
            }
            isDiscovering = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.workspaces_title)) },
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
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = ConnectionFormState() }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_pc_connection_description))
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.workspaces_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.workspaces_subtitle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            qrScanLauncher.launch(
                                ScanOptions()
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(false),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.cd_scan_qr))
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(stringResource(R.string.add_via_qr))
                    }
                    OutlinedButton(
                        onClick = { startLanDiscovery() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.WifiFind, contentDescription = stringResource(R.string.cd_lan_discovery))
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(stringResource(R.string.discover_on_lan))
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.runtime_targets_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            items(state.targets, key = { it.id }) { target ->
                val remoteProfile = state.connections.firstOrNull { it.id == target.id }
                SectionCard(
                    modifier =
                        Modifier.clickable(
                            enabled =
                                when (target.agent) {
                                    LocalAgent.CLAUDE_CODE -> state.claude.installed
                                    LocalAgent.OPEN_CODE -> state.localStatus is LocalRuntimeStatus.Ready
                                    LocalAgent.ANTIGRAVITY -> target.state is RuntimeState.Connected
                                    null -> true
                                },
                        ) {
                            onSelectRuntime(target.id)
                        },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            painter = painterResource(runtimeAgentIcon(target.agent)),
                            contentDescription = stringResource(R.string.cd_runtime_type),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(runtimeSummaryLabel(target), fontWeight = FontWeight.SemiBold)
                            Text(
                                text = targetSubtitle(target, state.localStatus, remoteProfile?.baseUrl),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (target.selected) {
                            StatusChip(stringResource(R.string.in_use_label), active = true)
                        } else if (target.type == RuntimeType.REMOTE) {
                            TextButton(onClick = { onSelectRuntime(target.id) }) { Text(stringResource(R.string.select)) }
                        }
                        if (remoteProfile != null) {
                            IconButton(onClick = { editing = ConnectionFormState.from(remoteProfile) }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_description))
                            }
                        }
                    }
                }
            }

            // Connections whose endpoint no longer builds a runtime have no target row above, so
            // list them here instead of letting them disappear with no way to fix or remove them.
            items(state.unusableConnections, key = { "unusable-${it.id}" }) { profile ->
                SectionCard(
                    modifier = Modifier.clickable { editing = ConnectionFormState.from(profile) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Default.Computer,
                            contentDescription = stringResource(R.string.cd_connection_error),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = stringResource(R.string.remote_url_invalid),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        IconButton(onClick = { editing = ConnectionFormState.from(profile) }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_description))
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.workspace_folders_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(stringResource(R.string.item_count, state.workspaces.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Importing a folder and cloning a repository both write into the Android runtime's
                // filesystem, so they only produce a usable working folder while that runtime is the
                // active one. Offering them for a PC connection would register a path that does not
                // exist on that machine.
                if (localRuntimeActive) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onImportFolder,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Default.DriveFolderUpload,
                                contentDescription = stringResource(R.string.cd_import_folder),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.workspace_import_folder))
                        }
                        OutlinedButton(
                            onClick = onCloneGithub,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = stringResource(R.string.cd_clone_github),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.workspace_clone_github))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Neither of the two above reaches a folder the environment already has — one
                    // cloned from the terminal, or anything outside /workspace — so those could not
                    // be made a working folder at all.
                    OutlinedButton(
                        onClick = onChooseFolder,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = stringResource(R.string.cd_choose_folder),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.workspace_choose_folder))
                    }
                }
            }

            if (state.workspaces.isEmpty()) {
                item {
                    SectionCard {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = stringResource(R.string.cd_folder),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.no_workspaces_title), fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.no_workspaces_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                items(state.workspaces, key = { it.id }) { workspace ->
                    WorkspaceProjectRow(
                        workspace = workspace,
                        isDeleting = workspace.path in state.folders.deleting,
                        deleteFailed = workspace.path in state.folders.failed,
                        // Only the /workspace mount is this app's own storage; a folder elsewhere in
                        // the environment can be dropped from the list but is not ours to erase.
                        canDeleteFiles = localRuntimeActive && WorkspaceFolders.isInsideWorkspaceRoot(workspace.path),
                        onOpen = { onOpenWorkspace(workspace) },
                        onRemove = { onRemoveProject(workspace.path) },
                        onDeleteFiles = { onDeleteProjectFiles(workspace.path) },
                        onDismissDeleteFailure = { onDismissDeleteFailure(workspace.path) },
                    )
                }
            }

            state.error?.let { error ->
                item {
                    SectionCard {
                        Text(
                            stringResource(R.string.status_fetch_failed),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    editing?.let { initial ->
        ConnectionDialog(
            initial = initial,
            onDismiss = { editing = null },
            onSave = {
                onSaveConnection(it)
                editing = null
            },
            onDelete =
                if (state.connections.any { it.id == initial.id }) {
                    {
                        onDeleteConnection(initial.id)
                        editing = null
                    }
                } else {
                    null
                },
            onTest = onTestConnection,
        )
    }

    if (discoveryDialogOpen) {
        AlertDialog(
            onDismissRequest = { discoveryDialogOpen = false },
            title = { Text(stringResource(R.string.discovered_servers_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isDiscovering) {
                        Text(
                            stringResource(R.string.discovering_servers),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (discoveredServers.isEmpty()) {
                        if (!isDiscovering) {
                            Text(
                                stringResource(R.string.no_servers_found),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        discoveredServers.forEach { server ->
                            SectionCard(
                                modifier =
                                    Modifier.clickable {
                                        editing =
                                            ConnectionFormState(
                                                name = server.name,
                                                baseUrl = server.baseUrl,
                                                allowInsecureLan = true,
                                            )
                                        discoveryDialogOpen = false
                                    },
                            ) {
                                Text(server.name, fontWeight = FontWeight.Medium)
                                Text(
                                    server.baseUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { discoveryDialogOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (state.folderPicker.visible) {
        FolderPickerDialog(
            picker = state.folderPicker,
            onOpenChild = onBrowseFolderInto,
            onUp = onBrowseFolderUp,
            onConfirm = onConfirmFolder,
            onDismiss = onDismissFolderPicker,
            onDeviceStorageAccessChanged = onDeviceStorageAccessChanged,
        )
    }
}

/**
 * Walks the on-device environment so an existing directory can be made a working folder.
 *
 * Selects the folder that is open, not one highlighted in the list: the user has to be able to pick
 * a directory whose own contents they just looked at, and that is also how they reach a folder with
 * no subfolders at all.
 */
@Composable
private fun FolderPickerDialog(
    picker: FolderPickerState,
    onOpenChild: (String) -> Unit,
    onUp: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onDeviceStorageAccessChanged: () -> Unit = {},
) {
    val atRoot = picker.path == WorkspaceFolders.GUEST_ROOT
    val requestDeviceStorage = rememberDeviceStorageRequest(onDeviceStorageAccessChanged)
    var showAllFilesWarning by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_choose_folder)) },
        text = {
            Column {
                if (picker.unavailable) {
                    Text(
                        stringResource(R.string.workspace_folder_picker_unavailable),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                Text(
                    picker.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (picker.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    if (!atRoot) {
                        item {
                            FolderPickerRow(
                                icon = Icons.Default.ArrowUpward,
                                label = stringResource(R.string.workspace_folder_picker_up),
                                onClick = onUp,
                            )
                        }
                    }
                    // Only at the root: that is where the device's own storage would be listed, so
                    // it is where its absence is the thing the user is looking at.
                    if (atRoot && !picker.deviceStorageAvailable) {
                        item {
                            FolderPickerRow(
                                icon = Icons.Default.PhoneAndroid,
                                label = stringResource(R.string.workspace_device_storage_row),
                                onClick = { showAllFilesWarning = true },
                            )
                            Text(
                                stringResource(R.string.workspace_device_storage_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    items(picker.directories, key = { it }) { name ->
                        FolderPickerRow(
                            icon = Icons.Default.Folder,
                            label = name,
                            onClick = { onOpenChild(name) },
                        )
                    }
                    if (picker.directories.isEmpty() && !picker.isLoading) {
                        item {
                            Text(
                                stringResource(R.string.workspace_folder_picker_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            // The environment root itself is not a project; anything below it can be one.
            Button(onClick = onConfirm, enabled = !picker.unavailable && !atRoot) {
                Text(stringResource(R.string.workspace_folder_picker_select))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )

    if (showAllFilesWarning) {
        RiskWarningDialog(
            titleRes = R.string.risk_warning_all_files_title,
            bodyRes = R.string.risk_warning_all_files_body,
            onConfirm = {
                showAllFilesWarning = false
                requestDeviceStorage()
            },
            onDismiss = { showAllFilesWarning = false },
        )
    }
}

/**
 * Sends the user wherever this Android release grants all-files access, and reports back when they
 * return so the caller can list what has just become reachable.
 *
 * Android 11 moved the grant from a permission dialog to a settings screen, so which of the two is
 * launched depends on the platform rather than on anything the caller knows.
 */
@Composable
private fun rememberDeviceStorageRequest(onChanged: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val access = remember(context) { DeviceStorageAccess(context) }
    val settingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onChanged() }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    return {
        val permission = access.runtimePermissions().firstOrNull()
        if (permission != null) {
            permissionLauncher.launch(permission)
        } else {
            // A few devices ship without the per-app screen; the full app list still has the toggle.
            runCatching { settingsLauncher.launch(access.settingsIntent()) }
                .onFailure { runCatching { settingsLauncher.launch(access.settingsFallbackIntent()) } }
        }
    }
}

@Composable
private fun FolderPickerRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(label, maxLines = 1)
    }
}

@Composable
private fun WorkspaceProjectRow(
    workspace: WorkspaceRef,
    isDeleting: Boolean,
    deleteFailed: Boolean,
    canDeleteFiles: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onDeleteFiles: () -> Unit,
    onDismissDeleteFailure: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // The dialog is the thing that reports progress, so a failure has to bring it back even if the
    // user closed it while the delete was running.
    LaunchedEffect(deleteFailed) {
        if (deleteFailed) confirmDelete = true
    }

    SectionCard(modifier = Modifier.clickable(enabled = !isDeleting) { onOpen() }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.cd_folder), tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(workspace.name, fontWeight = FontWeight.Medium)
                Text(
                    workspace.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                if (isDeleting) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.workspace_deleting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (isDeleting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.workspace_more_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workspace_remove_from_list)) },
                            onClick = {
                                menuOpen = false
                                onRemove()
                            },
                        )
                        if (canDeleteFiles) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_delete_files)) },
                                onClick = {
                                    menuOpen = false
                                    confirmDelete = true
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        // Stays up for the whole delete: this used to close on the tap and leave the row behind
        // until some later refresh happened to drop it, which read as the delete having failed.
        AlertDialog(
            onDismissRequest = {
                if (!isDeleting) {
                    confirmDelete = false
                    onDismissDeleteFailure()
                }
            },
            title = { Text(stringResource(R.string.workspace_delete_files)) },
            text = {
                Column {
                    Text(stringResource(R.string.workspace_delete_files_confirm, workspace.name))
                    if (isDeleting) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.workspace_deleting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (deleteFailed) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.workspace_delete_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDeleteFiles, enabled = !isDeleting) {
                    Text(stringResource(if (deleteFailed) R.string.workspace_delete_retry else R.string.delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDismissDeleteFailure()
                    },
                    enabled = !isDeleting,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Names a runtime the same way the chat picker does, so the two never disagree. */
@Composable
private fun runtimeSummaryLabel(target: RuntimeSummary): String {
    val agent = target.agent ?: return target.name
    return stringResource(R.string.local_agent_on_device, stringResource(agent.displayNameRes))
}

@Composable
private fun targetSubtitle(
    target: RuntimeSummary,
    localStatus: LocalRuntimeStatus,
    remoteUrl: String?,
): String =
    if (target.agent == LocalAgent.CLAUDE_CODE) {
        when (val runtimeState = target.state) {
            is RuntimeState.Connected -> stringResource(R.string.claude_installed_version, runtimeState.version)
            is RuntimeState.Failed -> compactRuntimeError(runtimeState.message)
            is RuntimeState.Unavailable -> stringResource(R.string.claude_status_not_installed)
            RuntimeState.Connecting -> stringResource(R.string.claude_status_installing)
            RuntimeState.Disconnected -> stringResource(R.string.claude_status_not_installed)
        }
    } else if (target.agent == LocalAgent.ANTIGRAVITY) {
        when (val runtimeState = target.state) {
            // Both of these now read like the OpenCode and Claude Code rows, which they did not: an
            // installed Antigravity showed a bare "1.1.7" beside "OpenCode 1.18.5", and an
            // uninstalled one showed the target's raw English reason ("Antigravity is not installed
            // or incompatible with this ABI") where Claude Code shows "Not installed".
            is RuntimeState.Connected -> stringResource(R.string.antigravity_installed_version, runtimeState.version)
            RuntimeState.Connecting -> stringResource(R.string.claude_status_installing)
            is RuntimeState.Failed -> compactRuntimeError(runtimeState.message)
            is RuntimeState.Unavailable -> stringResource(R.string.runtime_status_not_installed)
            RuntimeState.Disconnected -> stringResource(R.string.runtime_status_not_installed)
        }
    } else {
        when (target.type) {
            RuntimeType.REMOTE ->
                when (val runtimeState = target.state) {
                    is RuntimeState.Connected -> stringResource(R.string.connected_at_url, runtimeState.version, remoteUrl.orEmpty())
                    RuntimeState.Connecting -> stringResource(R.string.connecting_at, remoteUrl.orEmpty())
                    is RuntimeState.Failed -> compactRuntimeError(runtimeState.message)
                    is RuntimeState.Unavailable -> runtimeState.reason
                    RuntimeState.Disconnected -> remoteUrl.orEmpty()
                }
            RuntimeType.LOCAL ->
                when (localStatus) {
                    LocalRuntimeStatus.NotInstalled -> stringResource(R.string.runtime_status_not_installed)
                    is LocalRuntimeStatus.Installing -> stringResource(R.string.setting_up_with_step, localStatus.step)
                    is LocalRuntimeStatus.Starting -> stringResource(R.string.starting_opencode_version, localStatus.version)
                    is LocalRuntimeStatus.Updating ->
                        stringResource(
                            R.string.updating_with_step,
                            localStatus.currentVersion,
                            localStatus.targetVersion,
                            localStatus.step,
                        )
                    is LocalRuntimeStatus.Stopped -> stringResource(R.string.installed_stopped, localStatus.version)
                    is LocalRuntimeStatus.Ready -> stringResource(R.string.ready_running, localStatus.version)
                    is LocalRuntimeStatus.Broken -> compactRuntimeError(localStatus.reason)
                    is LocalRuntimeStatus.UnsupportedAbi -> stringResource(R.string.unsupported_abi, localStatus.abi)
                }
        }
    }

@Composable
private fun compactRuntimeError(message: String): String {
    val firstUsefulLine =
        message.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    val compact = firstUsefulLine.take(160)
    return when {
        compact.isBlank() -> stringResource(R.string.generic_runtime_problem)
        compact.length < firstUsefulLine.length -> "$compact…"
        else -> compact
    }
}
