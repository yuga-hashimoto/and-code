package com.yugahashimoto.andcode.ui.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.feature.browser.GuestBrowserScreen
import com.yugahashimoto.andcode.feature.workspace.CodeViewerScreen
import com.yugahashimoto.andcode.feature.workspace.CodeViewerViewModel
import com.yugahashimoto.andcode.feature.workspace.LocalRuntimeManagementScreen
import com.yugahashimoto.andcode.feature.workspace.LocalRuntimeManagementViewModel
import com.yugahashimoto.andcode.feature.workspace.RemoteConnectionScreen
import com.yugahashimoto.andcode.feature.workspace.TerminalScreen
import com.yugahashimoto.andcode.feature.workspace.TerminalViewModel
import com.yugahashimoto.andcode.feature.workspace.WorkspaceExplorerScreen
import com.yugahashimoto.andcode.feature.workspace.WorkspaceExplorerViewModel
import com.yugahashimoto.andcode.feature.workspace.WorkspaceViewModel
import com.yugahashimoto.andcode.feature.workspace.WorkspacesScreen
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import com.yugahashimoto.andcode.ui.ViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun NavGraphBuilder.workspaceNavGraph(
    navController: NavController,
    workspaceViewModel: WorkspaceViewModel,
    // Getters, not values: NavHost remembers the destination lambdas, so a value read here is the
    // one that existed when the graph was built. That is how the explorer came to be unreachable —
    // the workspace the user had just tapped still read as null and the screen popped straight back.
    selectedWorkspace: () -> WorkspaceRef?,
    onSelectWorkspace: (WorkspaceRef?) -> Unit,
    selectedRuntime: () -> RuntimeTarget?,
    app: AndCodeApplication,
    onImportFolder: () -> Unit,
    onShowCloneDialog: () -> Unit,
    completeOnboardingAndGoToChat: () -> Unit,
) {
    composable(ROUTE_REMOTE_CONNECTION) {
        RemoteConnectionScreen(
            onTestConnection = workspaceViewModel::testConnection,
            // Saving here is the user pressing "connect", so the PC becomes the active runtime even
            // when an Android-local runtime is already set up and selected.
            onSaveConnection = { form -> workspaceViewModel.saveConnection(form, activate = true) },
            onBack = { navController.popBackStack() },
            onConnected = completeOnboardingAndGoToChat,
        )
    }

    composable(ROUTE_WORKSPACES) {
        // Collected here rather than passed in: NavHost remembers the graph, so a state value
        // handed to this builder would stay frozen at whatever it was on first composition and
        // every later update — runtime installs, health checks — would never reach the screen.
        val workspaceState by workspaceViewModel.state.collectAsState()
        WorkspacesScreen(
            state = workspaceState,
            onSelectRuntime = workspaceViewModel::selectRuntime,
            // This screen edits the connection list and has its own per-target "select" action, so
            // saving must not move the running target under the user.
            onSaveConnection = { form -> workspaceViewModel.saveConnection(form, activate = false) },
            onDeleteConnection = workspaceViewModel::deleteConnection,
            onTestConnection = workspaceViewModel::testConnection,
            onRefresh = workspaceViewModel::refresh,
            onOpenWorkspace = { workspace ->
                onSelectWorkspace(workspace)
                navController.navigate(WORKSPACE_DETAIL_ROUTE)
            },
            onImportFolder = onImportFolder,
            onCloneGithub = onShowCloneDialog,
            onChooseFolder = workspaceViewModel::openFolderPicker,
            onBrowseFolderInto = workspaceViewModel::browseFolderInto,
            onBrowseFolderUp = workspaceViewModel::browseFolderUp,
            onConfirmFolder = { workspaceViewModel.confirmFolderPicker() },
            onDismissFolderPicker = workspaceViewModel::dismissFolderPicker,
            onDeviceStorageAccessChanged = workspaceViewModel::onDeviceStorageAccessChanged,
            onRemoveProject = workspaceViewModel::removeProject,
            onDeleteProjectFiles = workspaceViewModel::deleteProjectFiles,
            onDismissDeleteFailure = workspaceViewModel::dismissDeleteFailure,
            onBack = { navController.popBackStack() },
        )
    }

    composable(LOCAL_RUNTIME_MANAGEMENT_ROUTE) {
        val managementViewModel: LocalRuntimeManagementViewModel =
            viewModel(
                key = "local-runtime-management",
                factory =
                    ViewModelFactory {
                        LocalRuntimeManagementViewModel(
                            runtimeState = app.localRuntimeManager.state,
                            lastOperationState = app.localRuntimeManager.lastOperation,
                            diagnosticsProvider = {
                                withContext(Dispatchers.IO) {
                                    app.localRuntimeDiagnosticsCollector.collect()
                                }
                            },
                            repairAction = app.localRuntimeController::reinstall,
                            deleteAction = app.localRuntimeController::delete,
                            getString = { app.getString(it) },
                            adbState = app.adbConnectionManager.state,
                            adbPairAction = app.adbConnectionManager::pair,
                            adbConnectAction = app.adbConnectionManager::connect,
                            adbDisconnectAction = app.adbConnectionManager::disconnect,
                            adbStartDiscovery = app.adbConnectionManager::startDiscovery,
                        )
                    },
            )
        val managementState by managementViewModel.state.collectAsState()
        LaunchedEffect(managementState.deleteCompleted) {
            if (managementState.deleteCompleted) {
                managementViewModel.consumeDeleteCompleted()
                workspaceViewModel.refresh()
                navController.popBackStack()
            }
        }
        LocalRuntimeManagementScreen(
            state = managementState,
            onBack = { navController.popBackStack() },
            onRefresh = managementViewModel::refresh,
            onRepair = managementViewModel::repair,
            onRequestDelete = managementViewModel::requestDelete,
            onDismissDelete = managementViewModel::dismissDelete,
            onConfirmDelete = managementViewModel::confirmDelete,
            onShowAdbPairDialog = managementViewModel::showAdbPairDialog,
            onDismissAdbPairDialog = managementViewModel::dismissAdbPairDialog,
            onAdbPair = managementViewModel::adbPair,
            onAdbConnect = managementViewModel::adbConnect,
            onAdbDisconnect = managementViewModel::adbDisconnect,
        )
    }

    composable(WORKSPACE_DETAIL_ROUTE) {
        val workspace = selectedWorkspace()
        val runtime = selectedRuntime()
        if (workspace == null || runtime == null) {
            LaunchedEffect(Unit) { navController.popBackStack() }
        } else {
            val explorerViewModel: WorkspaceExplorerViewModel =
                viewModel(
                    key = "workspace-explorer-${runtime.id}-${workspace.id}",
                    factory =
                        ViewModelFactory {
                            WorkspaceExplorerViewModel(runtime, workspace)
                        },
                )
            val explorerState by explorerViewModel.state.collectAsState()
            WorkspaceExplorerScreen(
                state = explorerState,
                onBack = { navController.popBackStack() },
                onRefresh = explorerViewModel::refresh,
                onOpenNode = { node ->
                    if (node.type == "directory") {
                        explorerViewModel.open(node)
                    } else {
                        navController.navigate(codeViewerRoute(runtime.id, workspace.path, node.path))
                    }
                },
                onNavigateUp = explorerViewModel::navigateUp,
                onSearch = explorerViewModel::search,
                onRefreshChanges = explorerViewModel::refreshChanges,
                onOpenTerminal = { navController.navigate(ROUTE_TERMINAL) },
            )
        }
    }

    composable(ROUTE_TERMINAL) {
        val terminalViewModel: TerminalViewModel =
            viewModel(
                key = "terminal",
                factory =
                    ViewModelFactory {
                        TerminalViewModel(app.commandRunner)
                    },
            )
        val terminalState by terminalViewModel.state.collectAsState()
        TerminalScreen(
            state = terminalState,
            onCommand = terminalViewModel::executeCommand,
            onInputChange = terminalViewModel::updateInput,
            onClear = terminalViewModel::clear,
        )
    }

    composable(GUEST_BROWSER_ROUTE_PATTERN) { backStack ->
        val requestedUrl = backStack.arguments?.getString(GUEST_BROWSER_ARG_URL)?.let { decodeRouteArg(it) }
        GuestBrowserScreen(
            initialUrl =
                requestedUrl
                    ?: app.localRuntimeManager.installedPort()?.let { "http://127.0.0.1:$it/" }.orEmpty(),
            onBack = { navController.popBackStack() },
        )
    }

    composable(CODE_VIEWER_ROUTE_PATTERN) { backStack ->
        val arguments =
            runCatching {
                Triple(
                    decodeRouteArg(requireNotNull(backStack.arguments?.getString("runtimeId"))),
                    decodeRouteArg(requireNotNull(backStack.arguments?.getString("workspacePath"))),
                    decodeRouteArg(requireNotNull(backStack.arguments?.getString("filePath"))),
                )
            }.getOrNull()
        val runtime = arguments?.first?.let { app.runtimeRegistry.target(it) }
        if (arguments == null || runtime == null) {
            LaunchedEffect(Unit) { navController.popBackStack() }
        } else {
            val viewerViewModel: CodeViewerViewModel =
                viewModel(
                    key = "code-viewer-${backStack.id}",
                    factory = ViewModelFactory { CodeViewerViewModel(runtime, arguments.second, arguments.third) },
                )
            val viewerState by viewerViewModel.state.collectAsState()
            val preferences by app.preferences.state.collectAsState()
            CodeViewerScreen(
                state = viewerState,
                syntaxThemeKey = preferences.syntaxTheme,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
