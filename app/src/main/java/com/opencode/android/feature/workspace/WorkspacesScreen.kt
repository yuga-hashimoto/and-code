package com.opencode.android.feature.workspace

import android.content.Context
import android.net.nsd.NsdManager
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.opencode.android.R
import com.opencode.android.core.api.OpenCodeHealth
import com.opencode.android.core.security.ConnectionQrPayload
import com.opencode.android.runtime.LocalRuntimeStatus
import com.opencode.android.runtime.RuntimeState
import com.opencode.android.runtime.RuntimeType
import com.opencode.android.runtime.WorkspaceRef
import com.opencode.android.ui.components.SectionCard
import com.opencode.android.ui.components.StatusChip
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
    onSetupLocal: () -> Unit,
    onStartLocal: () -> Unit,
    onStopLocal: () -> Unit,
    onReinstallLocal: () -> Unit,
    onOpenLocalManagement: () -> Unit,
    onImportFolder: () -> Unit = {},
    onCloneGithub: () -> Unit = {},
    onRemoveProject: (String) -> Unit = {},
    onDeleteProjectFiles: (String) -> Unit = {},
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
                        Modifier.clickable(enabled = target.type == RuntimeType.REMOTE || state.localStatus is LocalRuntimeStatus.Ready) {
                            onSelectRuntime(target.id)
                        },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = if (target.type == RuntimeType.LOCAL) Icons.Default.Android else Icons.Default.Computer,
                            contentDescription = stringResource(R.string.cd_runtime_type),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(target.name, fontWeight = FontWeight.SemiBold)
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
                    if (target.type == RuntimeType.LOCAL) {
                        Spacer(Modifier.height(12.dp))
                        when (val local = state.localStatus) {
                            LocalRuntimeStatus.NotInstalled -> {
                                Button(onClick = onSetupLocal, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Build, contentDescription = stringResource(R.string.cd_setup))
                                    Spacer(Modifier.padding(horizontal = 4.dp))
                                    Text(stringResource(R.string.setup_this_device_button))
                                }
                            }
                            is LocalRuntimeStatus.Installing -> {
                                Text(local.step, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(8.dp))
                                if (local.progress != null) {
                                    LinearProgressIndicator(
                                        progress = { local.progress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                            is LocalRuntimeStatus.Starting -> {
                                Text(stringResource(R.string.starting_opencode_version, local.version))
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            is LocalRuntimeStatus.Updating -> {
                                Text(local.step, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(8.dp))
                                if (local.progress != null) {
                                    LinearProgressIndicator(
                                        progress = { local.progress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                            is LocalRuntimeStatus.Stopped -> {
                                Button(onClick = onStartLocal, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.cd_start))
                                    Spacer(Modifier.padding(horizontal = 4.dp))
                                    Text(stringResource(R.string.start_opencode_button))
                                }
                            }
                            is LocalRuntimeStatus.Ready -> {
                                OutlinedButton(onClick = onStopLocal, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.cd_stop))
                                    Spacer(Modifier.padding(horizontal = 4.dp))
                                    Text(stringResource(R.string.stop_local_runtime_button))
                                }
                            }
                            is LocalRuntimeStatus.Broken -> {
                                Button(onClick = onReinstallLocal, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Build, contentDescription = stringResource(R.string.cd_repair))
                                    Spacer(Modifier.padding(horizontal = 4.dp))
                                    Text(stringResource(R.string.repair_and_resetup_button))
                                }
                            }
                            is LocalRuntimeStatus.UnsupportedAbi -> Unit
                        }
                        if (state.localStatus !is LocalRuntimeStatus.UnsupportedAbi) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onOpenLocalManagement,
                                enabled =
                                    state.localStatus !is LocalRuntimeStatus.Installing &&
                                        state.localStatus !is LocalRuntimeStatus.Starting &&
                                        state.localStatus !is LocalRuntimeStatus.Updating,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_diagnostics))
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                Text(stringResource(R.string.diagnostics_and_management_button))
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
                        onOpen = { onOpenWorkspace(workspace) },
                        onRemove = { onRemoveProject(workspace.path) },
                        onDeleteFiles = { onDeleteProjectFiles(workspace.path) },
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
}

@Composable
private fun WorkspaceProjectRow(
    workspace: WorkspaceRef,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onDeleteFiles: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    SectionCard(modifier = Modifier.clickable { onOpen() }) {
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
            }
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.workspace_delete_files)) },
            text = { Text(stringResource(R.string.workspace_delete_files_confirm, workspace.name)) },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    onDeleteFiles()
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun targetSubtitle(
    target: RuntimeSummary,
    localStatus: LocalRuntimeStatus,
    remoteUrl: String?,
): String =
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
