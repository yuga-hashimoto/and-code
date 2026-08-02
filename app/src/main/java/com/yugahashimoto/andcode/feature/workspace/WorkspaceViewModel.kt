package com.yugahashimoto.andcode.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.api.OpenCodeApiClient
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import com.yugahashimoto.andcode.data.repository.RuntimeCatalogRepository
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import com.yugahashimoto.andcode.runtime.local.ClaudeCodeController
import com.yugahashimoto.andcode.runtime.local.ClaudeCodeUiState
import com.yugahashimoto.andcode.runtime.local.ClaudePermissionMode
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeManager
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeServiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class RuntimeSummary(
    val id: String,
    val name: String,
    val type: RuntimeType,
    val state: RuntimeState,
    val selected: Boolean,
    /** Which local agent this target runs, or null for remote connections. */
    val agent: LocalAgent?,
)

data class WorkspaceUiState(
    val targets: List<RuntimeSummary> = emptyList(),
    val connections: List<ConnectionProfile> = emptyList(),
    /** Saved connections whose endpoint can no longer be used, so they have no runtime target. */
    val unusableConnections: List<ConnectionProfile> = emptyList(),
    val selectedRuntimeId: String? = null,
    val workspaces: List<WorkspaceRef> = emptyList(),
    val localStatus: LocalRuntimeStatus = LocalRuntimeStatus.NotInstalled,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val claude: ClaudeCodeUiState = ClaudeCodeUiState(),
    val folders: WorkspaceFolderActions = WorkspaceFolderActions(),
    val folderPicker: FolderPickerState = FolderPickerState(),
)

/**
 * Which folders are being erased right now, and which ones failed.
 *
 * Deleting a project folder is not instant — a repository with its history and dependencies is tens
 * of thousands of files — so the row and its dialog stay on screen reporting progress until the
 * folder is actually gone, instead of returning immediately and leaving the entry sitting there.
 */
data class WorkspaceFolderActions(
    val deleting: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
)

/** Browsing state for picking an existing folder out of what the on-device sandbox can see. */
data class FolderPickerState(
    val visible: Boolean = false,
    val path: String = WorkspaceFolders.GUEST_ROOT,
    val directories: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val unavailable: Boolean = false,
    /**
     * False while the phone's own files are still out of reach, which is what the picker offers to
     * fix: without all-files access the listing is the sandbox's private directories and nothing
     * else, and a user looking for a folder they can see in a file manager finds no trace of it.
     */
    val deviceStorageAvailable: Boolean = false,
)

class WorkspaceViewModel(
    private val registry: RuntimeRegistry,
    private val catalog: RuntimeCatalogRepository,
    private val localRuntimeManager: LocalRuntimeManager,
    private val localRuntimeController: LocalRuntimeServiceController,
    private val settings: SecureSettingsRepository,
    private val workspaceHostDir: File,
    private val incompleteConnectionMessage: String = "Connection information is incomplete",
    private val claudeCode: ClaudeCodeController? = null,
    private val deviceStorage: () -> DeviceStorage.Mounts = DeviceStorage::mounts,
    private val folderBrowser: RuntimeFolderBrowser =
        RuntimeFolderBrowser(
            File(workspaceHostDir.absoluteFile.parentFile, RUNTIME_ROOTFS_PATH),
            workspaceHostDir,
            deviceStorage,
        ),
) : ViewModel() {
    private val registeredTick = MutableStateFlow(0)
    private val claudeState: StateFlow<ClaudeCodeUiState> =
        claudeCode?.state ?: MutableStateFlow(ClaudeCodeUiState())
    private val folderActions = MutableStateFlow(WorkspaceFolderActions())
    private val folderPicker = MutableStateFlow(FolderPickerState())

    val state: StateFlow<WorkspaceUiState> =
        combine(
            registry.targets,
            registry.selected,
            catalog.state,
            localRuntimeManager.state,
            combine(registeredTick, claudeState, folderActions, folderPicker, ::LocalState),
        ) { targets, selected, runtime, localStatus, local ->
            val profiles = registry.remoteProfiles()
            // Read imperatively: the registry recomputes this set while building the target list, so
            // it is already up to date by the time `targets` emits.
            val unusableIds = registry.unusableProfileIds.value
            WorkspaceUiState(
                targets =
                    targets.map { target ->
                        RuntimeSummary(
                            id = target.id,
                            name = target.displayName,
                            type = target.type,
                            state = target.state.value,
                            selected = target.id == selected?.id,
                            agent = target.agent,
                        )
                    },
                connections = profiles,
                unusableConnections = profiles.filter { it.id in unusableIds },
                selectedRuntimeId = selected?.id,
                workspaces =
                    WorkspaceFolders.visibleWorkspaces(
                        runtimeWorkspaces = runtime.workspaces,
                        registered = registeredProjects(selected),
                        hidden = settings.hiddenWorkspacePaths.toSet(),
                    ),
                localStatus = localStatus,
                isRefreshing = runtime.isRefreshing,
                error = runtime.error,
                claude = local.claude,
                folders = local.folders,
                folderPicker = local.picker,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, WorkspaceUiState())

    init {
        viewModelScope.launch {
            localRuntimeManager.state.collect { status ->
                if (status is LocalRuntimeStatus.Ready) {
                    // Only fill an empty selection. The local runtime reports Ready again on every
                    // watchdog tick, and selecting it unconditionally used to drag the app back off
                    // a PC connection the user had just picked.
                    if (registry.selectIfUnset(LOCAL_RUNTIME_ID)) catalog.refresh()
                }
            }
        }
    }

    /**
     * Folders registered on this device. They are paths inside the Android runtime's filesystem, so
     * they are only meaningful while the Android-local runtime is selected — listing them for a PC
     * connection would offer working folders that do not exist on that machine.
     */
    private fun registeredProjects(selected: RuntimeTarget?): List<WorkspaceRef> {
        if (selected != null && selected.type != RuntimeType.LOCAL) return emptyList()
        return settings.projectPaths.map { path ->
            WorkspaceRef(id = path, name = WorkspaceFolders.displayName(path), path = path)
        }
    }

    fun addProject(serverPath: String) {
        // Registering a folder again is how a removal is undone: the user pointed at this path, so
        // it belongs back on the list even if they had hidden it earlier.
        settings.hiddenWorkspacePaths = settings.hiddenWorkspacePaths.filter { it != serverPath }
        val current = settings.projectPaths.toMutableList()
        if (serverPath !in current) {
            current += serverPath
            settings.projectPaths = current
        }
        registeredTick.update { it + 1 }
    }

    fun removeProject(serverPath: String) {
        settings.projectPaths = settings.projectPaths.filter { it != serverPath }
        settings.hiddenWorkspacePaths = (settings.hiddenWorkspacePaths + serverPath).distinct()
        folderActions.update { it.copy(failed = it.failed - serverPath) }
        registeredTick.update { it + 1 }
    }

    /**
     * Erases a workspace folder from the device, then drops it from the list.
     *
     * Runs off the main thread and reports progress: a project folder holds a repository's history
     * and its dependencies, so the delete takes long enough that doing it inline froze the screen
     * and returned before the folder was gone.
     */
    fun deleteProjectFiles(serverPath: String) {
        if (serverPath in folderActions.value.deleting) return
        val hostDir =
            if (registry.selected.value?.type == RuntimeType.LOCAL) {
                // Only the workspace mount is this app's own storage. A folder listed by a PC
                // connection just happens to share a path with one of ours, and deleting on that
                // resemblance would wipe unrelated local files.
                WorkspaceFolders.deletableHostDirectory(workspaceHostDir, serverPath)
            } else {
                null
            }
        if (hostDir == null) {
            removeProject(serverPath)
            refresh()
            return
        }
        folderActions.update { it.copy(deleting = it.deleting + serverPath, failed = it.failed - serverPath) }
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { deleteFolder(hostDir) }
            if (deleted) {
                removeProject(serverPath)
                refresh()
            }
            folderActions.update {
                it.copy(
                    deleting = it.deleting - serverPath,
                    failed = if (deleted) it.failed else it.failed + serverPath,
                )
            }
        }
    }

    /** Clears the failure shown on a row, so its dialog can be dismissed. */
    fun dismissDeleteFailure(serverPath: String) {
        folderActions.update { it.copy(failed = it.failed - serverPath) }
    }

    private fun deleteFolder(hostDir: File): Boolean {
        if (!hostDir.exists()) return true
        hostDir.deleteRecursively()
        // What is left on disk is the answer, not the return value: the row is claiming the folder
        // is gone, so it has to be gone.
        return !hostDir.exists()
    }

    fun openFolderPicker() {
        // Re-read on every open, not once at construction: the user leaves for the system settings
        // screen to grant all-files access and comes back to this same picker.
        val deviceStorageAvailable = !deviceStorage().isEmpty
        if (!folderBrowser.isAvailable()) {
            folderPicker.value =
                FolderPickerState(visible = true, unavailable = true, deviceStorageAvailable = deviceStorageAvailable)
            return
        }
        folderPicker.value =
            FolderPickerState(
                visible = true,
                path = WorkspaceFolders.GUEST_ROOT,
                deviceStorageAvailable = deviceStorageAvailable,
            )
        browseFolder(WorkspaceFolders.GUEST_ROOT)
    }

    fun browseFolder(path: String) {
        val normalized = WorkspaceFolders.normalize(path)
        folderPicker.update { it.copy(path = normalized, isLoading = true) }
        viewModelScope.launch {
            val children = withContext(Dispatchers.IO) { folderBrowser.children(normalized) }
            folderPicker.update { current ->
                // A slower listing must not overwrite the folder the user has since moved to.
                if (current.path != normalized) current else current.copy(directories = children, isLoading = false)
            }
        }
    }

    fun browseFolderUp() = browseFolder(WorkspaceFolders.parentOf(folderPicker.value.path))

    fun browseFolderInto(name: String) = browseFolder(WorkspaceFolders.childOf(folderPicker.value.path, name))

    /** Registers the folder currently open in the picker and closes it. */
    fun confirmFolderPicker(): String? {
        val path = folderPicker.value.path
        if (path == WorkspaceFolders.GUEST_ROOT) return null
        addProject(path)
        refresh()
        dismissFolderPicker()
        return path
    }

    fun dismissFolderPicker() {
        folderPicker.value = FolderPickerState()
    }

    /**
     * Called when the user returns from the system screen that grants all-files access.
     *
     * A running runtime is restarted because PRoot binds are fixed at process start: the server
     * that was already serving has no `/sdcard` in its view of the filesystem and would keep
     * reporting "no such directory" for the very folder the picker has just started listing.
     */
    fun onDeviceStorageAccessChanged() {
        if (deviceStorage().isEmpty) return
        if (state.value.localStatus is LocalRuntimeStatus.Ready) restartLocalRuntime()
        if (folderPicker.value.visible) openFolderPicker()
    }

    fun selectRuntime(id: String) {
        registry.select(id)
    }

    /**
     * Saves a PC connection. [activate] makes it the running target, which is what the connection
     * screen wants: the user pressed "connect", so the app has to move to that machine even when a
     * local runtime is already set up and selected.
     */
    fun saveConnection(
        form: ConnectionFormState,
        activate: Boolean = true,
    ) {
        if (!form.canSave) return
        registry.upsertRemote(form.toProfile(), select = activate)
    }

    fun deleteConnection(id: String) {
        registry.deleteRemote(id)
    }

    suspend fun testConnection(form: ConnectionFormState): Result<OpenCodeHealth> {
        if (!form.canSave) {
            return Result.failure(IllegalArgumentException(incompleteConnectionMessage))
        }
        return runCatching { OpenCodeApiClient(form.toProfile()).health() }
    }

    /** [agents] is the setup guide's selection; every other caller means OpenCode alone. */
    fun setupLocalRuntime(agents: Set<LocalAgent> = setOf(LocalAgent.OPEN_CODE)) = localRuntimeController.installAndStart(agents)

    fun startLocalRuntime() = localRuntimeController.start()

    fun stopLocalRuntime() = localRuntimeController.stop()

    fun restartLocalRuntime() = localRuntimeController.restart()

    fun reinstallLocalRuntime() = localRuntimeController.reinstall()

    fun installClaudeCode() = claudeCode?.install() ?: Unit

    fun updateClaudeCode() = claudeCode?.update() ?: Unit

    fun setClaudePermissionMode(
        mode: ClaudePermissionMode,
        sessionId: String? = null,
    ) = claudeCode?.setPermissionMode(mode, sessionId) ?: Unit

    fun beginClaudeSignIn() = claudeCode?.beginSignIn() ?: Unit

    fun submitClaudeSignInCode(code: String) = claudeCode?.submitSignInCode(code) ?: Unit

    fun cancelClaudeSignIn() = claudeCode?.cancelSignIn() ?: Unit

    fun signOutClaude() = claudeCode?.signOut() ?: Unit

    /** Re-reads whether Claude Code is installed, after an install this app did not run itself. */
    fun refreshClaudeCode() = claudeCode?.refresh() ?: Unit

    fun refresh() {
        registry.refresh()
        catalog.refresh()
        claudeCode?.refresh()
    }

    /** The four app-local flows, folded into one so [combine] stays within its arity. */
    private data class LocalState(
        val tick: Int,
        val claude: ClaudeCodeUiState,
        val folders: WorkspaceFolderActions,
        val picker: FolderPickerState,
    )

    private companion object {
        const val LOCAL_RUNTIME_ID = "local-android"

        /** The installed Linux environment, which sits beside the workspace directory. */
        const val RUNTIME_ROOTFS_PATH = "environment/rootfs"
    }
}
